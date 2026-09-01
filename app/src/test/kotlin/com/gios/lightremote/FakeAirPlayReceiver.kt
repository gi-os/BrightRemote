package com.gios.lightremote

import com.gios.lightremote.airplay.DataStreamCodec
import com.gios.lightremote.airplay.HapBlockSession
import com.gios.lightremote.airplay.HttpCodec
import com.gios.lightremote.crypto.Curve25519
import com.gios.lightremote.crypto.Digest
import com.gios.lightremote.crypto.Srp
import com.gios.lightremote.proto.BPlist
import com.gios.lightremote.proto.Mrp
import com.gios.lightremote.proto.ProtoBuf
import com.gios.lightremote.proto.Tlv8
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket

/**
 * A stand-in AirPlay 2 receiver that speaks the accessory half of transient pairing, the
 * `/setup` exchange, and the data channel — enough to push one now-playing state at the real
 * client and prove it comes out the other end.
 *
 * The golden protobuf vectors prove the codec matches Google's protobuf byte for byte, and the
 * crypto vectors prove each primitive matches pyatv. What neither can prove is the *sequence*:
 * that transient pairing derives the same session key both ways, that the Control / Events /
 * DataStream keys are derived from the right salts and handed to the right direction, that the
 * data-stream framing and its plist envelope survive a round trip. Those are exactly the
 * mistakes that produce a channel which pairs and then decrypts nothing, and they only show up
 * when the real client runs against something that pushes back.
 *
 * As with [FakeAppleTv], the accessory side is written independently of the client's helpers, so
 * a shared misunderstanding has a chance of surfacing as a mismatch instead of cancelling out.
 */
class FakeAirPlayReceiver(private val pin: String = "3939") : Closeable {

    private val control = ServerSocket(0)
    val port: Int get() = control.localPort

    /** The now-playing state this receiver pushes; the test asserts the client extracts it. */
    val pushedTitle = "Midnight City"
    val pushedArtist = "M83"
    val pushedAlbum = "Hurry Up, We're Dreaming"
    val pushedDuration = 240.0
    val pushedElapsed = 63.5

    @Volatile
    var failure: Throwable? = null
        private set

    private val threads = mutableListOf<Thread>()
    private val sockets = mutableListOf<Closeable>()

    // Fixed SRP inputs so the exchange is deterministic.
    private val salt = ByteArray(16) { (it * 5 + 3).toByte() }
    private val serverPrivate = BigInteger(1, ByteArray(32) { (it * 11 + 5).toByte() })
    private var sessionKey: ByteArray? = null

    fun start() {
        spawn { runCatching { serveControl() }.onFailure { failure = it } }
    }

    override fun close() {
        sockets.forEach { runCatching { it.close() } }
        runCatching { control.close() }
        threads.forEach { it.interrupt() }
    }

    private fun spawn(block: () -> Unit) {
        val t = Thread(block).apply { isDaemon = true; start() }
        synchronized(threads) { threads.add(t) }
    }

    // ------------------------------------------------------------------ control channel

    private fun serveControl() {
        val socket = control.accept()
        synchronized(sockets) { sockets.add(socket) }
        socket.tcpNoDelay = true
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        var session: HapBlockSession? = null
        val plain = ByteArrayOutputStream()

        fun send(bytes: ByteArray) {
            val wire = session?.encrypt(bytes) ?: bytes
            output.write(wire); output.flush()
        }

        while (!socket.isClosed) {
            val (request, consumed) = HttpCodec.tryParseRequest(plain.toByteArray())
            if (request == null) {
                val chunk = readChunk(input) ?: return
                val decrypted = session?.decrypt(chunk) ?: chunk
                plain.write(decrypted)
                continue
            }
            val current = plain.toByteArray()
            plain.reset()
            if (consumed < current.size) plain.write(current, consumed, current.size - consumed)

            when {
                request.path == "/pair-pin-start" -> send(HttpCodec.formatResponse(echo(request)))
                request.path == "/pair-setup" -> {
                    val body = handlePairSetup(request.body)
                    send(response(request, body))
                    // Encryption begins on the request after M3's answer goes out.
                    if (sessionKey != null && session == null) {
                        val key = sessionKey!!
                        session = HapBlockSession(
                            outputKey = Digest.hkdfSha512("Control-Salt", "Control-Read-Encryption-Key", key),
                            inputKey = Digest.hkdfSha512("Control-Salt", "Control-Write-Encryption-Key", key),
                        )
                    }
                }
                request.method == "SETUP" -> send(response(request, handleSetup(request.body)))
                request.method == "RECORD" -> send(HttpCodec.formatResponse(echo(request)))
                else -> send(HttpCodec.formatResponse(echo(request)))
            }
        }
    }

    private fun echo(request: HttpCodec.Request): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        request.headers["cseq"]?.let { headers["CSeq"] = it }
        return headers
    }

    private fun response(request: HttpCodec.Request, body: ByteArray): ByteArray {
        val headers = echo(request).toMutableMap()
        return formatResponseWithBody(headers, body)
    }

    private fun formatResponseWithBody(headers: Map<String, String>, body: ByteArray): ByteArray {
        val sb = StringBuilder("RTSP/1.0 200 OK\r\n")
        for ((k, v) in headers) sb.append("$k: $v\r\n")
        sb.append("Content-Type: application/x-apple-binary-plist\r\n")
        sb.append("Content-Length: ${body.size}\r\n\r\n")
        return sb.toString().toByteArray(Charsets.US_ASCII) + body
    }

    // ------------------------------------------------------------------ transient SRP

    private fun pad(value: BigInteger): ByteArray {
        val bytes = Srp.minimalBytes(value)
        return ByteArray(Srp.PAD_SIZE - bytes.size) + bytes
    }

    private fun hashToInt(vararg parts: ByteArray) = BigInteger(1, Digest.sha512(*parts))
    private val multiplier by lazy { hashToInt(Srp.minimalBytes(Srp.N), pad(Srp.G)) }
    private val passwordX by lazy { hashToInt(salt, Digest.sha512("Pair-Setup:$pin".toByteArray())) }
    private val verifier by lazy { Srp.G.modPow(passwordX, Srp.N) }
    private val serverPublic by lazy {
        multiplier.multiply(verifier).add(Srp.G.modPow(serverPrivate, Srp.N)).mod(Srp.N)
    }

    private fun handlePairSetup(body: ByteArray): ByteArray {
        val tlv = Tlv8.read(body)
        val seq = tlv[Tlv8.SEQ_NO]?.firstOrNull()?.toInt()
        return when (seq) {
            0x01 -> Tlv8.write(
                linkedMapOf(
                    Tlv8.SEQ_NO to byteArrayOf(0x02),
                    Tlv8.SALT to salt,
                    Tlv8.PUBLIC_KEY to Srp.minimalBytes(serverPublic),
                ),
            )
            0x03 -> {
                val a = BigInteger(1, tlv[Tlv8.PUBLIC_KEY]!!)
                val u = hashToInt(pad(a), pad(serverPublic))
                val s = a.multiply(verifier.modPow(u, Srp.N)).modPow(serverPrivate, Srp.N)
                val key = Digest.sha512(Srp.minimalBytes(s))
                sessionKey = key

                val hn = hashToInt(Srp.minimalBytes(Srp.N))
                val hg = hashToInt(Srp.minimalBytes(Srp.G))
                val hi = hashToInt("Pair-Setup".toByteArray())
                val expectedProof = Digest.sha512(
                    Srp.minimalBytes(hn.xor(hg)), Srp.minimalBytes(hi), salt,
                    Srp.minimalBytes(a), Srp.minimalBytes(serverPublic), key,
                )
                check(Digest.constantTimeEquals(tlv[Tlv8.PROOF]!!, expectedProof)) {
                    "transient client proof did not verify"
                }
                Tlv8.write(
                    linkedMapOf(
                        Tlv8.SEQ_NO to byteArrayOf(0x04),
                        Tlv8.PROOF to Digest.sha512(Srp.minimalBytes(a), expectedProof, key),
                    ),
                )
            }
            else -> error("unexpected pair-setup sequence $seq")
        }
    }

    // ------------------------------------------------------------------ /setup channels

    private fun handleSetup(body: ByteArray): ByteArray {
        val request = BPlist.read(body) as Map<*, *>
        val key = sessionKey ?: error("/setup before pairing")
        return if (request.containsKey("streams")) {
            val stream = (request["streams"] as List<*>).first() as Map<*, *>
            val seed = (stream["seed"] as Number).toLong()
            val dataServer = ServerSocket(0)
            synchronized(sockets) { sockets.add(dataServer) }
            spawn { runCatching { serveData(dataServer, key, seed) }.onFailure { failure = it } }
            BPlist.write(linkedMapOf("streams" to listOf(linkedMapOf("dataPort" to dataServer.localPort.toLong()))))
        } else {
            val eventServer = ServerSocket(0)
            synchronized(sockets) { sockets.add(eventServer) }
            spawn { runCatching { eventServer.accept() }.getOrNull() } // accept and idle
            BPlist.write(linkedMapOf("eventPort" to eventServer.localPort.toLong()))
        }
    }

    private fun serveData(server: ServerSocket, key: ByteArray, seed: Long) {
        val socket = server.accept()
        synchronized(sockets) { sockets.add(socket) }
        socket.tcpNoDelay = true
        val salt = "DataStream-Salt$seed"
        // Server perspective: out is the client's input key.
        val session = HapBlockSession(
            outputKey = Digest.hkdfSha512(salt, "DataStream-Input-Encryption-Key", key),
            inputKey = Digest.hkdfSha512(salt, "DataStream-Output-Encryption-Key", key),
        )
        // Push one SET_STATE with the known metadata as a sync/comm frame.
        val protobuf = buildSetState()
        val payload = BPlist.write(
            linkedMapOf("params" to linkedMapOf("data" to DataStreamCodec.encodeProtobuf(protobuf))),
        )
        val frame = DataStreamCodec.syncCommand(seqno = 0x1234, payload = payload)
        val output: OutputStream = socket.getOutputStream()
        output.write(session.encrypt(frame))
        output.flush()
    }

    /** A SET_STATE carrying the known now-playing item, built independently of the client. */
    private fun buildSetState(): ByteArray {
        val npi = ProtoBuf.Writer()
            .string(1, pushedAlbum)      // album
            .string(2, pushedArtist)     // artist
            .double(3, pushedDuration)   // duration
            .double(4, pushedElapsed)    // elapsedTime
            .float(5, 1.0f)              // playbackRate
            .string(9, pushedTitle)      // title
        val setState = ProtoBuf.Writer()
            .message(1, npi)                                 // nowPlayingInfo
            .enum(6, Mrp.PlaybackState.Playing.wire)         // playbackState
        return ProtoBuf.Writer()
            .int(1, Mrp.Type.SET_STATE)                      // type
            .string(85, "0000-0001")                         // uniqueIdentifier
            .message(9, setState)                            // setStateMessage
            .toByteArray()
    }

    private fun readChunk(input: InputStream): ByteArray? {
        val buffer = ByteArray(8192)
        val n = input.read(buffer)
        if (n < 0) return null
        return buffer.copyOf(n)
    }
}
