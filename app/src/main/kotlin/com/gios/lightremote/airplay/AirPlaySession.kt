package com.gios.lightremote.airplay

import com.gios.lightremote.companion.Credentials
import com.gios.lightremote.companion.Trace
import com.gios.lightremote.proto.BPlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** How the AirPlay channel authenticates. */
sealed class AirPlayAuth {
    /** Transient pairing (HomePod, and the no-credentials probe). */
    data object Transient : AirPlayAuth()

    /** Pair-verify with credentials from an earlier AirPlay pair-setup. */
    data class WithCredentials(val credentials: Credentials) : AirPlayAuth()
}

/**
 * The AirPlay 2 "remote control" session: connect, authenticate, and stand up the event and
 * data channels so MRP protobufs can flow.
 *
 * The sequence is fixed and, like the Companion connect, unforgiving of reordering. Pair-verify
 * (or transient) brings up encryption on the control channel; `/setup` for the event channel
 * has to precede `RECORD`, which has to precede `/setup` for the data channel; only then does a
 * data channel exist to send MRP on. This mirrors pyatv's `AP2Session.setup_remote_control`.
 *
 * The event channel carries nothing this remote wants — but the receiver refuses to proceed
 * without it, so it is set up and answered (200 to every ping) and otherwise ignored.
 */
class AirPlaySession(
    private val host: String,
    private val controlPort: Int,
    private val deviceId: String,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val DATASTREAM_SALT = "DataStream-Salt"
        private const val EVENTS_SALT = "Events-Salt"
    }

    private val control = RtspChannel(host, controlPort)
    private var event: RtspChannel? = null
    private var data: RtspChannel? = null
    private var eventReader: Job? = null
    private var dataReader: Job? = null
    private var cseq = 0
    private val sessionId = Random.nextLong(0, 1L shl 32)
    private val dacpId = "%016X".format(Random.nextLong())

    /** Called with each MRP protobuf frame the data channel receives. */
    var onProtobuf: ((ByteArray) -> Unit)? = null

    val isConnected: Boolean get() = data?.isConnected == true

    // ------------------------------------------------------------------ connect

    suspend fun connect(auth: AirPlayAuth) {
        withContext(Dispatchers.IO) {
            Trace.step("airplay: control connect $host:$controlPort")
            control.connect()

            val pairing = AirPlayPairing(control)
            val keys = when (auth) {
                AirPlayAuth.Transient -> pairing.transient()
                is AirPlayAuth.WithCredentials -> pairing.verify(auth.credentials)
            }
            val (controlOut, controlIn) = keys.derive(
                "Control-Salt", "Control-Write-Encryption-Key", "Control-Read-Encryption-Key",
            )
            control.enableEncryption(controlOut, controlIn)
            Trace.step("airplay: control encryption up")

            setupEventChannel(keys)
            rtsp("RECORD", null)
            setupDataChannel(keys)
            Trace.step("airplay: data channel up")
        }
    }

    private fun setupEventChannel(keys: AirPlayPairing.AirPlayKeys) {
        val body = BPlist.write(
            linkedMapOf(
                "isRemoteControlOnly" to true,
                "osName" to "iPhone OS",
                "sourceVersion" to "550.10",
                "timingProtocol" to "None",
                "model" to "iPhone14,3",
                "deviceID" to deviceId,
                "osVersion" to "17.0",
                "osBuildVersion" to "21A329",
                "macAddress" to deviceId,
                "sessionUUID" to java.util.UUID.randomUUID().toString().uppercase(),
                "name" to "Light Phone",
            ),
        )
        val response = rtsp("SETUP", body)
        val plist = BPlist.read(response.body) as? Map<*, *>
            ?: throw IllegalStateException("airplay: /setup event returned no plist")
        val eventPort = (plist["eventPort"] as? Number)?.toInt()
            ?: throw IllegalStateException("airplay: /setup event returned no eventPort")

        // Read/write reversed: this connection originates from the receiver.
        val (eventOut, eventIn) = keys.derive(
            EVENTS_SALT, "Events-Read-Encryption-Key", "Events-Write-Encryption-Key",
        )
        val ch = RtspChannel(host, eventPort)
        ch.connect()
        ch.enableEncryption(eventOut, eventIn)
        event = ch
        eventReader = scope.launch(Dispatchers.IO) { runEventLoop(ch) }
    }

    private fun setupDataChannel(keys: AirPlayPairing.AirPlayKeys) {
        val seed = Random.nextLong(0, Long.MAX_VALUE)
        val body = BPlist.write(
            linkedMapOf(
                "streams" to listOf(
                    linkedMapOf(
                        "controlType" to 2L,
                        "channelID" to java.util.UUID.randomUUID().toString().uppercase(),
                        "seed" to seed,
                        "clientUUID" to java.util.UUID.randomUUID().toString().uppercase(),
                        "type" to 130L,
                        "wantsDedicatedSocket" to true,
                        "clientTypeUUID" to "1910A70F-DBC0-4242-AF95-115DB30604E1",
                    ),
                ),
            ),
        )
        val response = rtsp("SETUP", body)
        val plist = BPlist.read(response.body) as? Map<*, *>
            ?: throw IllegalStateException("airplay: /setup data returned no plist")
        val streams = plist["streams"] as? List<*>
            ?: throw IllegalStateException("airplay: /setup data returned no streams")
        val stream = streams.firstOrNull() as? Map<*, *>
            ?: throw IllegalStateException("airplay: /setup data returned an empty stream list")
        val dataPort = (stream["dataPort"] as? Number)?.toInt()
            ?: throw IllegalStateException("airplay: /setup data returned no dataPort")

        // The seed is appended to the salt as its decimal string — the same value has to key
        // both ends, which is why it is a plain Long that serialises identically here and in
        // the plist.
        val (dataOut, dataIn) = keys.derive(
            DATASTREAM_SALT + seed.toString(),
            "DataStream-Output-Encryption-Key",
            "DataStream-Input-Encryption-Key",
        )
        val ch = RtspChannel(host, dataPort)
        ch.connect()
        ch.enableEncryption(dataOut, dataIn)
        data = ch
        dataReader = scope.launch(Dispatchers.IO) { runDataLoop(ch) }
    }

    // ------------------------------------------------------------------ control RTSP

    private fun rtsp(method: String, body: ByteArray?): HttpCodec.Response {
        val headers = linkedMapOf(
            "CSeq" to (cseq++).toString(),
            "DACP-ID" to dacpId,
            "Active-Remote" to Random.nextInt(0, Int.MAX_VALUE).toString(),
            "Client-Instance" to dacpId,
        )
        if (body != null) headers["Content-Type"] = "application/x-apple-binary-plist"
        val request = HttpCodec.formatRequest(
            method = method,
            uri = "rtsp://$host/$sessionId",
            headers = headers,
            body = body,
            protocol = "RTSP/1.0",
        )
        return control.exchange(request)
    }

    // ------------------------------------------------------------------ data channel

    private var sendSeqno = Random.nextLong(0x1_0000_0000L, 0x1_FFFF_FFFFL)

    /** Send one MRP protobuf over the data channel, wrapped in the plist envelope tvOS wants. */
    fun sendProtobuf(message: ByteArray) {
        val ch = data ?: return
        val payload = BPlist.write(
            linkedMapOf(
                "params" to linkedMapOf(
                    "data" to DataStreamCodec.encodeProtobuf(message),
                ),
            ),
        )
        val frame = DataStreamCodec.syncCommand(sendSeqno++, payload)
        runCatching { ch.sendRaw(frame) }.onFailure {
            Trace.problem("airplay: failed to send an MRP message", it)
        }
    }

    private fun runDataLoop(ch: RtspChannel) {
        runCatching {
            while (true) {
                val (frame, consumed) = DataStreamCodec.decode(ch.bufferedPlain())
                if (frame == null) {
                    ch.pumpOnce() ?: break
                    continue
                }
                ch.dropConsumed(consumed)
                if (frame.payload.isNotEmpty()) dispatchPayload(frame.payload)
                if (frame.isRequest) runCatching { ch.sendRaw(DataStreamCodec.reply(frame.seqno)) }
            }
        }
    }

    private fun dispatchPayload(payload: ByteArray) {
        val plist = runCatching { BPlist.read(payload) }.getOrNull() as? Map<*, *> ?: return
        val params = plist["params"] as? Map<*, *> ?: return
        val data = params["data"] as? ByteArray ?: return
        for (message in DataStreamCodec.decodeProtobufs(data)) onProtobuf?.invoke(message)
    }

    private fun runEventLoop(ch: RtspChannel) {
        runCatching {
            while (true) {
                val (request, consumed) = HttpCodec.tryParseRequest(ch.bufferedPlain())
                if (request == null) {
                    ch.pumpOnce() ?: break
                    continue
                }
                ch.dropConsumed(consumed)
                val echo = LinkedHashMap<String, String>()
                request.headers["cseq"]?.let { echo["CSeq"] = it }
                request.headers["server"]?.let { echo["Server"] = it }
                echo["Audio-Latency"] = "0"
                runCatching { ch.sendRaw(HttpCodec.formatResponse(echo)) }
            }
        }
    }

    fun close() {
        eventReader?.cancel()
        dataReader?.cancel()
        runCatching { data?.close() }
        runCatching { event?.close() }
        runCatching { control.close() }
        data = null
        event = null
    }
}
