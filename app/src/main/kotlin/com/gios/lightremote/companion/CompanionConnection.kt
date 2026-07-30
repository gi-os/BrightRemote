package com.gios.lightremote.companion

import com.gios.lightremote.crypto.ChaChaCipherPair
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Frame types on a Companion link. Only a handful are used, but the numbering matters:
 * the device replies to a `PS_Start` with a `PS_Next`, never another `PS_Start`.
 */
enum class FrameType(val value: Int) {
    Unknown(0),
    NoOp(1),
    PairSetupStart(3),
    PairSetupNext(4),
    PairVerifyStart(5),
    PairVerifyNext(6),
    UnencryptedOpack(7),
    EncryptedOpack(8),
    PlainOpack(9),
    SessionStartRequest(16),
    SessionStartResponse(17),
    SessionData(18),
    ;

    companion object {
        fun from(value: Int): FrameType = entries.firstOrNull { it.value == value } ?: Unknown
    }
}

class Frame(val type: FrameType, val payload: ByteArray)

/**
 * The Companion transport: a plain TCP socket carrying 4-byte-header frames, optionally
 * wrapped in ChaCha20-Poly1305 once pair-verify has completed.
 *
 * Blocking sockets rather than NIO — every call is made from a coroutine on the IO
 * dispatcher, and the protocol is strictly request/response with a single reader.
 */
class CompanionConnection(private val host: String, private val port: Int) {

    companion object {
        private const val HEADER_LENGTH = 4
        private const val AUTH_TAG_LENGTH = 16

        /** Refuse absurd frame lengths rather than trying to allocate them. */
        private const val MAX_FRAME_LENGTH = 8 * 1024 * 1024
    }

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    private var cipher: ChaChaCipherPair? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    fun connect(timeoutMs: Int = 5000) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        cipher = null
    }

    /**
     * Turn on session encryption. The keys are asymmetric by design: what we encrypt with
     * is what the device decrypts with, so swapping them produces a connection that
     * silently drops every frame.
     */
    fun enableEncryption(outputKey: ByteArray, inputKey: ByteArray) {
        cipher = ChaChaCipherPair(outputKey, inputKey, nonceLength = 12)
    }

    fun send(type: FrameType, payload: ByteArray) {
        val stream = output ?: throw IllegalStateException("not connected to the Apple TV")
        val active = cipher
        var body = payload
        var length = payload.size
        if (active != null && payload.isNotEmpty()) length += AUTH_TAG_LENGTH

        val header = byteArrayOf(
            type.value.toByte(),
            ((length shr 16) and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte(),
        )
        // The header is the AEAD associated data, which is what stops a frame being
        // replayed as a different type.
        if (active != null && payload.isNotEmpty()) body = active.encrypt(payload, aad = header)

        Trace.sent(type, payload.size, active != null && payload.isNotEmpty())
        synchronized(this) {
            stream.write(header)
            stream.write(body)
            stream.flush()
        }
    }

    /** Blocks until a whole frame arrives. Returns null at end of stream. */
    fun receive(): Frame? {
        val stream = input ?: return null
        val header = readFully(stream, HEADER_LENGTH) ?: return null
        val length = ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (length > MAX_FRAME_LENGTH) {
            throw IllegalStateException("Apple TV announced an implausible $length byte frame")
        }
        var payload = if (length == 0) ByteArray(0) else (readFully(stream, length) ?: return null)
        val active = cipher
        val type = FrameType.from(header[0].toInt() and 0xFF)
        val wasEncrypted = active != null && payload.isNotEmpty()
        if (wasEncrypted) {
            // A failure here means the stream counters have diverged, which is unrecoverable
            // — say so plainly rather than letting it look like a generic read error.
            payload = try {
                active!!.decrypt(payload, aad = header)
            } catch (e: Exception) {
                Trace.problem("could not decrypt a $type frame; session keys out of step", e)
                throw e
            }
        }
        Trace.received(type, payload.size, wasEncrypted)
        return Frame(type, payload)
    }

    private fun readFully(stream: InputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = stream.read(buffer, read, count - read)
            if (n < 0) return null
            read += n
        }
        return buffer
    }
}
