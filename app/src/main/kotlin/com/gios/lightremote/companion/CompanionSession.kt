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
                onDisconnect?.invoke(failure)
            }
        }
    }

    fun stop() {
        readerJob?.cancel()
        readerJob = null
    }

    private fun handle(frame: Frame) {
        if (frame.payload.isEmpty()) return
        val message = runCatching { Opack.unpackMap(frame.payload) }.getOrNull() ?: return

        when (frame.type) {
            FrameType.PairSetupStart, FrameType.PairSetupNext,
            FrameType.PairVerifyStart, FrameType.PairVerifyNext,
            -> pendingByFrame.remove(frame.type)?.complete(message)

            FrameType.EncryptedOpack, FrameType.UnencryptedOpack, FrameType.PlainOpack -> {
                when ((message["_t"] as? Long)) {
                    MessageType.Event.value -> {
                        val name = message["_i"] as? String ?: return
                        @Suppress("UNCHECKED_CAST")
                        val content = message["_c"] as? Map<String, Any?> ?: emptyMap()
                        onEvent?.invoke(name, content)
                    }
                    else -> {
                        val xid = message["_x"] as? Long ?: return
                        pendingByXid.remove(xid)?.complete(message)
                    }
                }
            }

            else -> Unit
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
        return awaitReply(deferred, timeoutMs) { pendingByFrame.remove(replyType) }
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
        withContext(Dispatchers.IO) { connection.send(FrameType.EncryptedOpack, Opack.pack(message)) }
        val response = awaitReply(deferred, timeoutMs) { pendingByXid.remove(xid) }
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
        cleanup: () -> Unit,
    ): Map<String, Any?> {
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) {
            cleanup()
            throw ProtocolException("the Apple TV did not answer in time")
        }
        return result
    }
}
