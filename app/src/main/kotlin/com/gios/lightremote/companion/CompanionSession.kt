package com.gios.lightremote.companion

import com.gios.lightremote.proto.Opack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Companion message kinds, the `_t` field. */
enum class MessageType(val value: Long) { Event(1), Request(2), Response(3) }

class ProtocolException(message: String) : Exception(message)

/**
 * Request/response plumbing over a [CompanionConnection].
 *
 * Regular OPACK messages carry a transaction id (`_x`) and may come back in any order, so
 * they are matched by id. Authentication frames have no id — the pairing handshake is
 * strictly sequential — so those are matched by frame type instead, with the wrinkle that
 * a `PS_Start`/`PV_Start` request is answered with the corresponding `_Next` frame.
 */
class CompanionSession(private val connection: CompanionConnection) {

    private val pendingByXid = ConcurrentHashMap<Long, CompletableDeferred<Map<String, Any?>>>()
    private val pendingByFrame = ConcurrentHashMap<FrameType, CompletableDeferred<Map<String, Any?>>>()
    private var nextXid: Long = Random.nextLong(0, 1L shl 16)
    private var readerJob: Job? = null

    /** Events the device pushes without being asked: `_iMC`, `SystemStatus`, `_tiStarted`. */
    var onEvent: ((String, Map<String, Any?>) -> Unit)? = null

    /** Called once when the socket goes away, so the UI can drop back to "disconnected". */
    var onDisconnect: ((Throwable?) -> Unit)? = null

    fun startReader(scope: CoroutineScope) {
        readerJob = scope.launch(Dispatchers.IO) {
            var failure: Throwable? = null
            try {
                while (true) {
                    val frame = connection.receive() ?: break
                    handle(frame)
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                // Never leave a caller suspended on a socket that has gone away.
                val error = failure ?: ProtocolException("connection closed by the Apple TV")
                pendingByXid.values.forEach { it.completeExceptionally(error) }
                pendingByFrame.values.forEach { it.completeExceptionally(error) }
                pendingByXid.clear()
                pendingByFrame.clear()
                // A close we asked for reports no cause. Closing the socket makes the reader
                // throw — of course it does — and reporting that as a failure meant every
                // deliberate teardown looked like the television hanging up: a "Lost the
                // connection" banner on the way out, an automatic reconnect of something just
                // closed, and, now that the app files its own reports, an offer to report a
                // fault that was the app pressing the hook switch. Every reconnect starts by
                // closing the old socket, so this fires on the happy path.
                //
                // The mirror-image mistake lived here for three versions: a clean end of
                // stream leaves `failure` null, so a television that *hung up on its own* —
                // which tvOS does, and starting playback is one of the moments it does it —
                // was reported exactly like the app closing its own socket. No banner, no
                // automatic reconnect, no offer to report: the remote just sat there
                // disconnected until somebody backed out and tapped the TV again. Only the
                // `closing` flag can say a teardown was ours; EOF cannot.
                onDisconnect?.invoke(if (closing) null else error)
            }
        }
    }

    @Volatile
    private var closing = false

    fun stop() {
        closing = true
        readerJob?.cancel()
        readerJob = null
    }

    private fun handle(frame: Frame) {
        if (frame.payload.isEmpty()) return
        val message = try {
            Opack.unpackMap(frame.payload)
        } catch (e: Exception) {
            Trace.problem("could not decode a ${frame.type} frame", e)
            return
        }

        when (frame.type) {
            FrameType.PairSetupStart, FrameType.PairSetupNext,
            FrameType.PairVerifyStart, FrameType.PairVerifyNext,
            -> {
                val waiting = pendingByFrame.remove(frame.type)
                if (waiting == null) {
                    // The handshake replies with the _Next variant of whatever was sent, so
                    // an unmatched auth frame means the device answered with a type we were
                    // not expecting — and that is precisely what shows up as a timeout a few
                    // seconds later.
                    Trace.unmatched("auth frame ${frame.type}, expected one of $${pendingByFrame.keys}")
                } else {
                    waiting.complete(message)
                }
            }

            FrameType.EncryptedOpack, FrameType.UnencryptedOpack, FrameType.PlainOpack -> {
                when ((message["_t"] as? Long)) {
                    MessageType.Event.value -> {
                        val name = message["_i"] as? String
                        if (name == null) {
                            Trace.unmatched("event with no identifier")
                            return
                        }
                        Trace.event(name)
                        @Suppress("UNCHECKED_CAST")
                        val content = message["_c"] as? Map<String, Any?> ?: emptyMap()
                        onEvent?.invoke(name, content)
                    }
                    else -> {
                        val xid = message["_x"] as? Long
                        Trace.response(message["_i"] as? String, xid)
                        if (xid == null) {
                            Trace.unmatched("response with no transaction id: ${message.keys}")
                            return
                        }
                        val waiting = pendingByXid.remove(xid)
                        if (waiting == null) {
                            Trace.unmatched("response x=$xid (waiting on ${pendingByXid.keys})")
                        } else {
                            waiting.complete(message)
                        }
                    }
                }
            }

            else -> Trace.problem("ignoring frame type ${frame.type}")
        }
    }

    /** Send a pairing frame and await its reply. */
    suspend fun exchangeAuth(
        type: FrameType,
        content: Map<String, Any?>,
        timeoutMs: Long = 20_000,
    ): Map<String, Any?> {
        val replyType = when (type) {
            FrameType.PairSetupStart -> FrameType.PairSetupNext
            FrameType.PairVerifyStart -> FrameType.PairVerifyNext
            else -> type
        }
        // Pairing frames carry a transaction id as well, even though nothing dispatches on
        // it — they are matched by frame type, because the handshake is strictly
        // sequential. pyatv stamps `_x` onto every outgoing frame including these, and a
        // real Apple TV does tolerate its absence, but sending a dictionary one entry short
        // of the reference client is not a difference worth keeping.
        val message = LinkedHashMap<String, Any?>(content)
        message["_x"] = synchronized(this) { nextXid++ }

        val deferred = CompletableDeferred<Map<String, Any?>>()
        pendingByFrame[replyType] = deferred
        withContext(Dispatchers.IO) { connection.send(type, Opack.pack(message)) }
        return awaitReply(deferred, timeoutMs, type.name) { pendingByFrame.remove(replyType) }
    }

    /** Send a request and await its response, matched on the transaction id. */
    suspend fun request(
        identifier: String,
        content: Map<String, Any?>,
        timeoutMs: Long = 8_000,
    ): Map<String, Any?> {
        val xid = synchronized(this) { nextXid++ }
        val deferred = CompletableDeferred<Map<String, Any?>>()
        pendingByXid[xid] = deferred
        val message = linkedMapOf<String, Any?>(
            "_i" to identifier,
            "_t" to MessageType.Request.value,
            "_c" to content,
            "_x" to xid,
        )
        Trace.request(identifier, xid)
        withContext(Dispatchers.IO) { connection.send(FrameType.EncryptedOpack, Opack.pack(message)) }
        val response = awaitReply(deferred, timeoutMs, identifier) { pendingByXid.remove(xid) }
        // `_em` is how the device reports "no request handler" for commands a given tvOS
        // version does not implement, which is a normal thing to hit.
        (response["_em"] as? String)?.let { throw ProtocolException("$identifier failed: $it") }
        return response
    }

    /** Fire-and-forget: events and HID touch samples get no reply. */
    suspend fun sendEvent(identifier: String, content: Map<String, Any?>) {
        val xid = synchronized(this) { nextXid++ }
        val message = linkedMapOf<String, Any?>(
            "_i" to identifier,
            "_t" to MessageType.Event.value,
            "_c" to content,
            "_x" to xid,
        )
        withContext(Dispatchers.IO) { connection.send(FrameType.EncryptedOpack, Opack.pack(message)) }
    }

    private suspend fun awaitReply(
        deferred: CompletableDeferred<Map<String, Any?>>,
        timeoutMs: Long,
        what: String,
        cleanup: () -> Unit,
    ): Map<String, Any?> {
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) {
            cleanup()
            // Naming the frame is the difference between a bug report that can be acted on
            // and one that cannot.
            Trace.problem("timed out waiting for a reply to $what after ${timeoutMs}ms")
            throw ProtocolException("no answer to $what")
        }
        return result
    }
}
