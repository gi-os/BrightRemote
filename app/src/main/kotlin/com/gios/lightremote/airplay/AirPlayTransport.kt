package com.gios.lightremote.airplay

import com.gios.lightremote.companion.Trace
import com.gios.lightremote.proto.ProtoBuf
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The little bit of HTTP/RTSP the AirPlay channels speak.
 *
 * AirPlay's control and event traffic is HTTP-shaped — a request line, headers, an optional
 * body framed by `Content-Length` — carried over a raw TCP socket (and, once pair-verify is
 * done, inside HAP block encryption). This is just enough of the format to post `/setup` and
 * answer the receiver's event pings. Header names are compared lowercased; bodies are opaque
 * bytes (binary plists, handled a level up).
 */
object HttpCodec {

    class Response(val code: Int, val headers: Map<String, String>, val body: ByteArray)
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private val CRLFCRLF = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

    fun formatRequest(
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: ByteArray?,
        protocol: String = "RTSP/1.0",
        userAgent: String = "AirPlay/550.10",
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("$method $uri $protocol\r\n")
        if (headers.keys.none { it.equals("User-Agent", true) }) sb.append("User-Agent: $userAgent\r\n")
        for ((k, v) in headers) sb.append("$k: $v\r\n")
        if (body != null && body.isNotEmpty()) sb.append("Content-Length: ${body.size}\r\n")
        sb.append("\r\n")
        val head = sb.toString().toByteArray(Charsets.US_ASCII)
        return if (body != null && body.isNotEmpty()) head + body else head
    }

    /** A 200 answer with an empty body, echoing CSeq/Server — what an event ping wants. */
    fun formatResponse(headers: Map<String, String>, protocol: String = "RTSP/1.0"): ByteArray {
        val sb = StringBuilder()
        sb.append("$protocol 200 OK\r\n")
        for ((k, v) in headers) sb.append("$k: $v\r\n")
        sb.append("Content-Length: 0\r\n\r\n")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /** Parse a complete response from [buffer], or null with 0 consumed if not all here yet. */
    fun tryParseResponse(buffer: ByteArray): Pair<Response?, Int> {
        val headerEnd = indexOf(buffer, CRLFCRLF)
        if (headerEnd < 0) return null to 0
        val headText = String(buffer, 0, headerEnd, Charsets.US_ASCII)
        val lines = headText.split("\r\n")
        val statusParts = lines.first().split(" ", limit = 3)
        // <protocol>/<version> <code> <message>
        val code = statusParts.getOrNull(1)?.toIntOrNull() ?: return null to 0
        val headers = parseHeaders(lines.drop(1))
        val bodyStart = headerEnd + CRLFCRLF.size
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (buffer.size - bodyStart < contentLength) return null to 0
        val body = buffer.copyOfRange(bodyStart, bodyStart + contentLength)
        return Response(code, headers, body) to (bodyStart + contentLength)
    }

    /** Parse a complete request (event channel), or null with 0 consumed if incomplete. */
    fun tryParseRequest(buffer: ByteArray): Pair<Request?, Int> {
        val headerEnd = indexOf(buffer, CRLFCRLF)
        if (headerEnd < 0) return null to 0
        val headText = String(buffer, 0, headerEnd, Charsets.US_ASCII)
        val lines = headText.split("\r\n")
        val requestParts = lines.first().split(" ", limit = 3)
        if (requestParts.size < 2) return null to 0
        val headers = parseHeaders(lines.drop(1))
        val bodyStart = headerEnd + CRLFCRLF.size
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (buffer.size - bodyStart < contentLength) return null to 0
        val body = buffer.copyOfRange(bodyStart, bodyStart + contentLength)
        return Request(requestParts[0], requestParts[1], headers, body) to (bodyStart + contentLength)
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            map[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return map
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

/**
 * One AirPlay TCP channel: a socket that speaks HTTP/RTSP, optionally wrapped in HAP block
 * encryption once pair-verify has produced the keys.
 *
 * Blocking sockets, driven from the IO dispatcher, request/response like the Companion
 * connection — but where Companion frames are length-prefixed, these are HTTP messages, so the
 * reader accumulates decrypted bytes until a whole message can be parsed out.
 */
class RtspChannel(private val host: String, private val port: Int) {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    private var session: HapBlockSession? = null

    /** Decrypted bytes read from the socket that no message has yet claimed. */
    private val plain = ByteArrayOutputStream()

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
        session = null
        plain.reset()
    }

    fun enableEncryption(outputKey: ByteArray, inputKey: ByteArray) {
        session = HapBlockSession(outputKey, inputKey)
    }

    /** Write [bytes], sealing them first if encryption is up. */
    fun sendRaw(bytes: ByteArray) {
        val stream = output ?: throw IllegalStateException("AirPlay channel not connected")
        val wire = session?.encrypt(bytes) ?: bytes
        stream.write(wire)
        stream.flush()
    }

    /** Send an HTTP/RTSP request and block until its response is complete. */
    fun exchange(request: ByteArray): HttpCodec.Response {
        sendRaw(request)
        while (true) {
            val (response, consumed) = HttpCodec.tryParseResponse(plain.toByteArray())
            if (response != null) {
                dropConsumed(consumed)
                return response
            }
            pumpOnce() ?: throw IllegalStateException("AirPlay channel closed mid-response")
        }
    }

    /**
     * Read one chunk from the socket into the plaintext buffer. Returns null at end of stream.
     * Exposed so a channel that also receives unsolicited requests (the event channel) can drive
     * its own read loop.
     */
    fun pumpOnce(): Unit? {
        val stream = input ?: return null
        val raw = ByteArray(8192)
        // A read that throws is the socket being closed under us — on teardown, or because the
        // receiver hung up. Either way it is end-of-stream to the caller, not an error to raise
        // out of a reader coroutine, so it is folded into the same null the clean EOF returns.
        val n = try {
            stream.read(raw)
        } catch (e: Exception) {
            return null
        }
        if (n < 0) return null
        val chunk = raw.copyOf(n)
        val decrypted = session?.decrypt(chunk) ?: chunk
        plain.write(decrypted)
        return Unit
    }

    fun bufferedPlain(): ByteArray = plain.toByteArray()

    fun dropConsumed(count: Int) {
        val current = plain.toByteArray()
        plain.reset()
        if (count < current.size) plain.write(current, count, current.size - count)
    }
}

/**
 * The AirPlay 2 data-stream framing that carries MRP protobufs.
 *
 * Each frame is a 32-byte header — a big-endian size, a twelve-byte message type, a four-byte
 * command, a big-endian sequence number and four padding bytes — followed by a payload. Our
 * payload is a binary plist wrapping the length-prefixed protobufs. A frame whose type begins
 * with `sync` is a request and must be answered with a matching `rply`, or the receiver stops
 * sending.
 *
 * From pyatv's `channels.py` (`DataHeader` / `DataStreamChannel`).
 */
object DataStreamCodec {

    const val HEADER_LENGTH = 32
    private val SYNC = "sync".toByteArray(Charsets.US_ASCII)

    class Frame(
        val messageType: ByteArray,
        val command: ByteArray,
        val seqno: Long,
        val payload: ByteArray,
    ) {
        val isRequest: Boolean get() = messageType.size >= 4 &&
            messageType.copyOfRange(0, 4).contentEquals(SYNC)
    }

    fun encode(messageType: ByteArray, command: ByteArray, seqno: Long, payload: ByteArray): ByteArray {
        val size = HEADER_LENGTH + payload.size
        val out = ByteArrayOutputStream()
        out.write(beBytes(size.toLong(), 4))
        out.write(fixed(messageType, 12))
        out.write(fixed(command, 4))
        out.write(beBytes(seqno, 8))
        out.write(beBytes(0, 4)) // padding
        out.write(payload)
        return out.toByteArray()
    }

    /** A `sync`/`comm` frame carrying [payload]. */
    fun syncCommand(seqno: Long, payload: ByteArray): ByteArray =
        encode("sync".toByteArray(Charsets.US_ASCII), "comm".toByteArray(Charsets.US_ASCII), seqno, payload)

    /** The empty acknowledgement a received `sync` frame requires. */
    fun reply(seqno: Long): ByteArray =
        encode("rply".toByteArray(Charsets.US_ASCII), ByteArray(4), seqno, ByteArray(0))

    /** Decode one frame from [buffer], or null with 0 consumed if it is not all here yet. */
    fun decode(buffer: ByteArray): Pair<Frame?, Int> {
        if (buffer.size < HEADER_LENGTH) return null to 0
        val size = beRead(buffer, 0, 4).toInt()
        if (size < HEADER_LENGTH || buffer.size < size) return null to 0
        val messageType = buffer.copyOfRange(4, 16)
        val command = buffer.copyOfRange(16, 20)
        val seqno = beRead(buffer, 20, 8)
        val payload = buffer.copyOfRange(HEADER_LENGTH, size)
        return Frame(messageType, command, seqno, payload) to size
    }

    /**
     * Length-prefixed protobufs, as the data channel packs them: each message preceded by its
     * varint length, except a single unprefixed message (which pyatv detects by its leading
     * `0x08` — the tag of field 1, `type`, present on every ProtocolMessage).
     */
    fun decodeProtobufs(data: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var pos = 0
        while (pos < data.size) {
            if ((data[pos].toInt() and 0xFF) == 0x08) {
                out.add(data.copyOfRange(pos, data.size))
                break
            }
            val (length, next) = try {
                ProtoBuf.readVarintAt(data, pos)
            } catch (e: ProtoBuf.FormatException) {
                Trace.problem("data stream: bad protobuf length prefix", e)
                break
            }
            val end = next + length.toInt()
            if (length < 0 || end > data.size) {
                Trace.problem("data stream: protobuf length runs past frame", null)
                break
            }
            out.add(data.copyOfRange(next, end))
            pos = end
        }
        return out
    }

    fun encodeProtobuf(message: ByteArray): ByteArray =
        ProtoBuf.writeVarint(message.size.toLong()) + message

    private fun fixed(value: ByteArray, size: Int): ByteArray {
        val out = ByteArray(size)
        System.arraycopy(value, 0, out, 0, minOf(value.size, size))
        return out
    }

    private fun beBytes(value: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) out[size - 1 - i] = ((value ushr (8 * i)) and 0xFF).toByte()
        return out
    }

    private fun beRead(data: ByteArray, offset: Int, size: Int): Long {
        var value = 0L
        for (i in 0 until size) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value
    }
}
