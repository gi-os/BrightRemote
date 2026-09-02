package com.gios.lightremote

import com.gios.lightremote.airplay.DataStreamCodec
import com.gios.lightremote.airplay.HapBlockSession
import com.gios.lightremote.airplay.HttpCodec
import com.gios.lightremote.crypto.ChaChaCipherPair
import com.gios.lightremote.crypto.Curve25519
import com.gios.lightremote.crypto.Digest
import com.gios.lightremote.crypto.Srp
import com.gios.lightremote.proto.BPlist
import com.gios.lightremote.proto.Mrp
import com.gios.lightremote.proto.Opack
import com.gios.lightremote.proto.ProtoBuf
import com.gios.lightremote.proto.Tlv8
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * A stand-in AirPlay 2 receiver: the accessory half of transient pairing, PIN pair-setup,
 * pair-verify, the `/setup` exchange, and the data channel — enough to pair the real client,
 * hand it long-term credentials, verify it on a later connection, and push one now-playing
 * state at it.
 *
 * The golden protobuf vectors prove the codec matches Google's protobuf byte for byte, and the
 * crypto vectors prove each primitive matches pyatv. What neither can prove is the *sequence*:
 * that a pairing derives the same session key both ways, that the Control / Events /
 * DataStream keys are derived from the right salts and handed to the right direction, that the
 * data-stream framing and its plist envelope survive a round trip. Those are exactly the
 * mistakes that produce a channel which pairs and then decrypts nothing, and they only show up
 * when the real client runs against something that pushes back.
 *
 * Two knobs matter to the tests:
 *
 *  - [supportsTransient] — false is a real Apple TV, which refuses transient pairing outright
 *    (it is a HomePod feature). The refusal is an error TLV to M1, exactly the shape tvOS
 *    sends, and the client is expected to give up silently.
 *  - [requests] — every request line the client sent, with its `X-Apple-HKP` mode. This is
 *    what the "nothing may put a code on the television" regression test reads:
 *    `/pair-pin-start` is the request that draws the PIN on tvOS, so its absence from this
 *    list is the whole point of the assertion.
 *
 * Connections are served one after another off one listening socket, because that is how the
 * client behaves: a pairing connection is closed before the tunnel connects with the result.
 *
 * As with [FakeAppleTv], the accessory side is written independently of the client's helpers,
 * so a shared misunderstanding has a chance of surfacing as a mismatch instead of cancelling
 * out.
 */
class FakeAirPlayReceiver(
    private val pin: String = "3939",
    private val supportsTransient: Boolean = true,
    /**
     * Reply to a perfectly good M5 the way tvOS replied to v1.25's malformed one: a bare
     * `{state: 6}` with neither credentials nor an error code. Exists so the client's
     * step-labelled error surface has something honest to fail against.
     */
    private val withholdCredentials: Boolean = false,
) : Closeable {

    private val control = ServerSocket(0)
    val port: Int get() = control.localPort

    /** The now-playing state this receiver pushes; the test asserts the client extracts it. */
    val pushedTitle = "Midnight City"
    val pushedArtist = "M83"
    val pushedAlbum = "Hurry Up, We're Dreaming"
    val pushedDuration = 240.0
    val pushedElapsed = 63.5

    /** Every request line received, in order: "METHOD /path hkp=N". */
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The client's long-term public key, once a PIN pair-setup has completed M5. */
    @Volatile
    var pairedClientLtpk: ByteArray? = null
        private set

    @Volatile
    var pairedClientId: ByteArray? = null
        private set

    /**
     * The display name each PIN pair-setup carried in M5, decoded the way tvOS decodes it.
     *
     * tvOS parses TLV 0x11 as an OPACK dictionary with a "name" entry. The v1.25 client sent
     * the name as a bare UTF-8 string, this fake never looked inside, and the divergence
     * shipped: against a real Apple TV the pairing died at M6 with no credentials. So the
     * fake now does what the television does — a name that is not a well-formed OPACK
     * {"name": ...} dictionary fails the exchange (see [handlePairSetup]) — and what it
     * managed to decode lands here for the tests to assert on.
     */
    val pairSetupNames: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var failure: Throwable? = null
        private set

    private val threads = mutableListOf<Thread>()
    private val sockets = mutableListOf<Closeable>()

    // Fixed inputs so every exchange is deterministic.
    private val salt = ByteArray(16) { (it * 5 + 3).toByte() }
    private val serverPrivate = BigInteger(1, ByteArray(32) { (it * 11 + 5).toByte() })

    /** The accessory's long-term Ed25519 identity — what pair-setup hands out as the LTPK. */
    private val accessorySeed = ByteArray(32) { (it * 7 + 1).toByte() }
    private val accessoryId = "FAKE-APPLE-TV".toByteArray(Charsets.UTF_8)

    fun start() {
        spawn {
            while (!control.isClosed) {
                val socket = runCatching { control.accept() }.getOrNull() ?: return@spawn
                synchronized(sockets) { sockets.add(socket) }
                // A client hanging up mid-exchange is not a receiver failure.
                runCatching { serve(socket) }.onFailure {
                    if (it !is java.net.SocketException) failure = it
                }
            }
        }
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

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        var session: HapBlockSession? = null
        val plain = ByteArrayOutputStream()

        // Per-connection pairing state.
        var srpKey: ByteArray? = null
        var verifyCipher: ChaChaCipherPair? = null
        var verifyShared: ByteArray? = null
        var clientVerifyPub: ByteArray? = null
        var accessoryVerifyPub: ByteArray? = null
        var channelKey: ByteArray? = null

        fun send(bytes: ByteArray) {
            val wire = session?.encrypt(bytes) ?: bytes
            output.write(wire); output.flush()
        }

        fun controlSession(key: ByteArray) = HapBlockSession(
            outputKey = Digest.hkdfSha512("Control-Salt", "Control-Read-Encryption-Key", key),
            inputKey = Digest.hkdfSha512("Control-Salt", "Control-Write-Encryption-Key", key),
        )

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

            val hkp = request.headers["x-apple-hkp"]
            requests.add("${request.method} ${request.path} hkp=${hkp ?: "-"}")

            when {
                request.path == "/pair-pin-start" -> send(HttpCodec.formatResponse(echo(request)))

                request.path == "/pair-setup" -> {
                    val transient = hkp == "4"
                    val outcome = handlePairSetup(request.body, transient)
                    if (outcome.sessionKey != null) srpKey = outcome.sessionKey
                    send(response(request, outcome.body))
                    // Transient pairing is the whole handshake: encryption begins on the
                    // request after M3's answer goes out. A PIN pair-setup never encrypts
                    // this connection — the client closes it and keeps the credentials.
                    if (transient && srpKey != null && session == null && outcome.switchToEncrypted) {
                        channelKey = srpKey
                        session = controlSession(srpKey!!)
                    }
                }

                request.path == "/pair-verify" -> {
                    val tlv = Tlv8.read(request.body)
                    when (tlv[Tlv8.SEQ_NO]?.firstOrNull()?.toInt()) {
                        0x01 -> {
                            val clientPub = tlv[Tlv8.PUBLIC_KEY] ?: error("verify M1 without a key")
                            val accessoryPrivate = ByteArray(32) { (it * 13 + 9).toByte() }
                            val accessoryPub = Curve25519.x25519PublicKey(accessoryPrivate)
                            val shared = Curve25519.x25519(accessoryPrivate, clientPub)
                            val key = Digest.hkdfSha512(
                                "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared,
                            )
                            val cipher = ChaChaCipherPair(key, key, nonceLength = 8)
                            val inner = Tlv8.write(
                                linkedMapOf(
                                    Tlv8.IDENTIFIER to accessoryId,
                                    Tlv8.SIGNATURE to Curve25519.ed25519Sign(
                                        accessorySeed,
                                        accessoryPub + accessoryId + clientPub,
                                    ),
                                ),
                            )
                            verifyCipher = cipher
                            verifyShared = shared
                            clientVerifyPub = clientPub
                            accessoryVerifyPub = accessoryPub
                            send(
                                response(
                                    request,
                                    Tlv8.write(
                                        linkedMapOf(
                                            Tlv8.SEQ_NO to byteArrayOf(0x02),
                                            Tlv8.PUBLIC_KEY to accessoryPub,
                                            Tlv8.ENCRYPTED_DATA to cipher.encrypt(
                                                inner, nonce = "PV-Msg02".toByteArray(Charsets.UTF_8),
                                            ),
                                        ),
                                    ),
                                ),
                            )
                        }
                        0x03 -> {
                            val cipher = verifyCipher ?: error("verify M3 before M1")
                            val sealed = tlv[Tlv8.ENCRYPTED_DATA] ?: error("verify M3 without data")
                            val inner = Tlv8.read(
                                cipher.decrypt(sealed, nonce = "PV-Msg03".toByteArray(Charsets.UTF_8)),
                            )
                            val identifier = inner[Tlv8.IDENTIFIER] ?: error("verify M3 without id")
                            val signature = inner[Tlv8.SIGNATURE] ?: error("verify M3 without signature")
                            val ltpk = pairedClientLtpk
                            val ok = ltpk == null || Curve25519.ed25519Verify(
                                ltpk,
                                clientVerifyPub!! + identifier + accessoryVerifyPub!!,
                                signature,
                            )
                            if (!ok) {
                                send(response(request, errorTlv(0x04, 0x02)))
                            } else {
                                send(response(request, Tlv8.write(linkedMapOf(Tlv8.SEQ_NO to byteArrayOf(0x04)))))
                                channelKey = verifyShared
                                session = controlSession(verifyShared!!)
                            }
                        }
                        else -> error("unexpected pair-verify sequence")
                    }
                }

                request.method == "SETUP" -> send(response(request, handleSetup(request.body, channelKey)))
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

    private fun errorTlv(seq: Int, code: Int): ByteArray = Tlv8.write(
        linkedMapOf(
            Tlv8.SEQ_NO to byteArrayOf(seq.toByte()),
            Tlv8.ERROR to byteArrayOf(code.toByte()),
        ),
    )

    // ------------------------------------------------------------------ SRP pair-setup

    private fun pad(value: BigInteger): ByteArray {
        val bytes = Srp.minimalBytes(value)
        return ByteArray(Srp.PAD_SIZE - bytes.size) + bytes
    }

    private fun hashToInt(vararg parts: ByteArray) = BigInteger(1, Digest.sha512(*parts))
    private val multiplier by lazy { hashToInt(Srp.minimalBytes(Srp.N), pad(Srp.G)) }

    /** Transient runs against the well-known 3939; a PIN setup runs against [pin]. */
    private fun verifierFor(code: String): BigInteger {
        val x = hashToInt(salt, Digest.sha512("Pair-Setup:$code".toByteArray()))
        return Srp.G.modPow(x, Srp.N)
    }

    private fun serverPublicFor(verifier: BigInteger): BigInteger =
        multiplier.multiply(verifier).add(Srp.G.modPow(serverPrivate, Srp.N)).mod(Srp.N)

    private class SetupOutcome(
        val body: ByteArray,
        val sessionKey: ByteArray? = null,
        val switchToEncrypted: Boolean = false,
    )

    private fun handlePairSetup(body: ByteArray, transient: Boolean): SetupOutcome {
        val tlv = Tlv8.read(body)
        val code = if (transient) "3939" else pin
        val verifier = verifierFor(code)
        val serverPublic = serverPublicFor(verifier)
        return when (val seq = tlv[Tlv8.SEQ_NO]?.firstOrNull()?.toInt()) {
            0x01 -> {
                // A real Apple TV does not do transient pairing at all: M1 with the
                // transient flag is answered with an error and nothing appears on screen.
                if (transient && !supportsTransient) {
                    return SetupOutcome(errorTlv(0x02, 0x06))
                }
                SetupOutcome(
                    Tlv8.write(
                        linkedMapOf(
                            Tlv8.SEQ_NO to byteArrayOf(0x02),
                            Tlv8.SALT to salt,
                            Tlv8.PUBLIC_KEY to Srp.minimalBytes(serverPublic),
                        ),
                    ),
                )
            }
            0x03 -> {
                val a = BigInteger(1, tlv[Tlv8.PUBLIC_KEY]!!)
                val u = hashToInt(pad(a), pad(serverPublic))
                val s = a.multiply(verifier.modPow(u, Srp.N)).modPow(serverPrivate, Srp.N)
                val key = Digest.sha512(Srp.minimalBytes(s))

                val hn = hashToInt(Srp.minimalBytes(Srp.N))
                val hg = hashToInt(Srp.minimalBytes(Srp.G))
                val hi = hashToInt("Pair-Setup".toByteArray())
                val expectedProof = Digest.sha512(
                    Srp.minimalBytes(hn.xor(hg)), Srp.minimalBytes(hi), salt,
                    Srp.minimalBytes(a), Srp.minimalBytes(serverPublic), key,
                )
                if (!Digest.constantTimeEquals(tlv[Tlv8.PROOF]!!, expectedProof)) {
                    // A wrong code is a wrong SRP proof. Real HAP tears the exchange down
                    // here — the retry is a fresh pair-setup and a fresh code.
                    lastSetupKey = null
                    return SetupOutcome(errorTlv(0x04, 0x02))
                }
                lastSetupKey = key
                SetupOutcome(
                    Tlv8.write(
                        linkedMapOf(
                            Tlv8.SEQ_NO to byteArrayOf(0x04),
                            Tlv8.PROOF to Digest.sha512(Srp.minimalBytes(a), expectedProof, key),
                        ),
                    ),
                    sessionKey = key,
                    switchToEncrypted = transient,
                )
            }
            0x05 -> {
                // Exchange: the client sends its long-term Ed25519 key under the SRP-derived
                // encryption key, and gets the accessory's back the same way.
                val key = tlv[Tlv8.ENCRYPTED_DATA] ?: error("M5 without encrypted data")
                val srpKey = requireNotNull(lastSetupKey) { "M5 before M3" }
                val encryptionKey = Digest.hkdfSha512(
                    "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", srpKey,
                )
                val cipher = ChaChaCipherPair(encryptionKey, encryptionKey, nonceLength = 8)
                val inner = Tlv8.read(cipher.decrypt(key, nonce = "PS-Msg05".toByteArray(Charsets.UTF_8)))
                val clientId = inner[Tlv8.IDENTIFIER] ?: error("M5 without identifier")
                val clientLtpk = inner[Tlv8.PUBLIC_KEY] ?: error("M5 without a public key")

                // tvOS verifies the controller's signature over the HKDF-derived iOSDeviceX
                // material before it stores anything; a client that signs the wrong bytes gets
                // an authentication error, not credentials. The fake used to accept anything.
                val clientSignature = inner[Tlv8.SIGNATURE] ?: error("M5 without a signature")
                val iosDeviceX = Digest.hkdfSha512(
                    "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", srpKey,
                )
                if (!Curve25519.ed25519Verify(clientLtpk, iosDeviceX + clientId + clientLtpk, clientSignature)) {
                    return SetupOutcome(errorTlv(0x06, 0x02))
                }

                // TLV 0x11, when present, must be an OPACK dictionary carrying "name" — the
                // encoding pyatv has always used and tvOS expects. On the malformed raw-UTF-8
                // name the v1.25 client sent, a real Apple TV answered M6 as a bare state with
                // neither credentials nor an error code (the observed field failure), so that
                // is exactly what the fake does. Anything looser here and the client bug that
                // shipped would still be invisible.
                val rawName = inner[Tlv8.NAME]
                if (rawName != null) {
                    val name = runCatching { Opack.unpackMap(rawName)["name"] as? String }.getOrNull()
                        ?: return SetupOutcome(Tlv8.write(linkedMapOf(Tlv8.SEQ_NO to byteArrayOf(0x06))))
                    pairSetupNames.add(name)
                }
                if (withholdCredentials) {
                    return SetupOutcome(Tlv8.write(linkedMapOf(Tlv8.SEQ_NO to byteArrayOf(0x06))))
                }
                pairedClientId = clientId
                pairedClientLtpk = clientLtpk

                val accessoryLtpk = Curve25519.ed25519PublicKey(accessorySeed)
                val deviceX = Digest.hkdfSha512(
                    "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", srpKey,
                )
                val payload = Tlv8.write(
                    linkedMapOf(
                        Tlv8.IDENTIFIER to accessoryId,
                        Tlv8.PUBLIC_KEY to accessoryLtpk,
                        Tlv8.SIGNATURE to Curve25519.ed25519Sign(
                            accessorySeed, deviceX + accessoryId + accessoryLtpk,
                        ),
                    ),
                )
                SetupOutcome(
                    Tlv8.write(
                        linkedMapOf(
                            Tlv8.SEQ_NO to byteArrayOf(0x06),
                            Tlv8.ENCRYPTED_DATA to cipher.encrypt(
                                payload, nonce = "PS-Msg06".toByteArray(Charsets.UTF_8),
                            ),
                        ),
                    ),
                )
            }
            else -> error("unexpected pair-setup sequence $seq")
        }
    }

    /** The SRP key from the latest M3, for M5 — which arrives as a separate request. */
    @Volatile
    private var lastSetupKey: ByteArray? = null

    // ------------------------------------------------------------------ /setup channels

    private fun handleSetup(body: ByteArray, sessionKey: ByteArray?): ByteArray {
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
