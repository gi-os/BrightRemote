package com.gios.lightremote

import com.gios.lightremote.crypto.ChaCha20Poly1305
import com.gios.lightremote.crypto.ChaChaCipherPair
import com.gios.lightremote.crypto.Curve25519
import com.gios.lightremote.crypto.Digest
import com.gios.lightremote.crypto.Srp
import com.gios.lightremote.proto.Opack
import com.gios.lightremote.proto.Tlv8
import java.io.Closeable
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket

/**
 * A stand-in Apple TV that speaks the accessory half of the Companion handshake.
 *
 * The golden vectors prove each primitive matches pyatv byte for byte, but they say nothing
 * about the *sequence*: which frame type answers which, whose key encrypts which direction,
 * when the counters start. Those are exactly the mistakes that produce a handshake which
 * appears to succeed and then goes quiet, and they can only be caught by running the real
 * client against something that pushes back.
 *
 * The accessory side here is written from the HAP flow independently rather than by reusing
 * the client's private helpers, so a misunderstanding baked into one side has a chance of
 * showing up as a mismatch instead of cancelling out.
 */
class FakeAppleTv(
    private val pin: String = "1234",
    /** Set to make the device reject pairing with a HAP authentication error. */
    private val rejectProof: Boolean = false,
) : Closeable {

    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    /** The device's long-term Ed25519 identity. */
    private val deviceSeed = ByteArray(32) { (it * 7 + 1).toByte() }
    val devicePublicKey: ByteArray = Curve25519.ed25519PublicKey(deviceSeed)
    val deviceId: ByteArray = "AA:BB:CC:DD:EE:FF".toByteArray()

    private val salt = ByteArray(16) { (it * 5 + 3).toByte() }
    private val serverPrivate = BigInteger(1, ByteArray(32) { (it * 11 + 5).toByte() })

    /** Requests seen after encryption came up, in order, for assertions. */
    val requests = mutableListOf<String>()

    /** `_hBtS` from every `_hidC` frame, in arrival order: 1 is DOWN, 2 is UP. */
    val hidStates = mutableListOf<Long>()

    /** Every pairing frame received, decoded, so tests can check the envelope. */
    val authFrames = mutableListOf<Map<String, Any?>>()

    /** Content of the `_systemInfo` request, once it arrives. */
    var systemInfo: Map<String, Any?>? = null
        private set

    @Volatile
    var failure: Throwable? = null
        private set

    @Volatile
    private var encryptionEnabled = false

    private var socket: Socket? = null
    private var cipher: ChaChaCipherPair? = null
    private var thread: Thread? = null

    // The material carried across the handshake steps.
    private var srpSessionKey: ByteArray? = null
    private var clientPublicKey: ByteArray? = null
    private var clientId: ByteArray? = null

    fun start() {
        thread = Thread {
            runCatching { serve() }.onFailure { failure = it }
        }.apply { isDaemon = true; start() }
    }

    override fun close() {
        runCatching { socket?.close() }
        runCatching { server.close() }
        thread?.interrupt()
    }

    // ------------------------------------------------------------------ framing

    /**
     * Accept connections one after another. Pairing and the first real connection are two
     * separate sockets — the device rebuilds its session state in between — but the paired
     * controller's key has to survive across them, so that state lives on the instance.
     */
    private fun serve() {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: return
            socket = client
            cipher = null
            pendingCipher = null
            encryptionEnabled = false
            runCatching { handleConnection(client) }
        }
    }

    private fun handleConnection(client: Socket) {
        client.tcpNoDelay = true
        val input = client.getInputStream()
        val output = client.getOutputStream()

        while (!client.isClosed) {
            val header = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = input.read(header, read, 4 - read)
                if (n < 0) return
                read += n
            }
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            var payload = ByteArray(length)
            read = 0
            while (read < length) {
                val n = input.read(payload, read, length - read)
                if (n < 0) return
                read += n
            }
            val active = cipher
            if (active != null && payload.isNotEmpty()) {
                payload = active.decrypt(payload, aad = header)
            }

            val type = header[0].toInt() and 0xFF
            val message = Opack.unpackMap(payload)
            val reply = handle(type, message) ?: continue

            val (replyType, replyBody) = reply
            var body = Opack.pack(replyBody)
            var outLength = body.size
            val outActive = cipher
            if (outActive != null && body.isNotEmpty()) outLength += ChaCha20Poly1305.TAG_LENGTH
            val outHeader = byteArrayOf(
                replyType.toByte(),
                ((outLength shr 16) and 0xFF).toByte(),
                ((outLength shr 8) and 0xFF).toByte(),
                (outLength and 0xFF).toByte(),
            )
            if (outActive != null && body.isNotEmpty()) {
                body = outActive.encrypt(body, aad = outHeader)
            }
            output.write(outHeader)
            output.write(body)
            output.flush()

            // Encryption starts on the frame *after* the verify confirmation, never on the
            // confirmation itself.
            if (pendingCipher != null) {
                cipher = pendingCipher
                pendingCipher = null
                encryptionEnabled = true
            }
        }
    }

    private var pendingCipher: ChaChaCipherPair? = null

    private fun handle(type: Int, message: Map<String, Any?>): Pair<Int, Map<String, Any?>>? {
        if (type in 3..6) synchronized(authFrames) { authFrames.add(message) }
        return when (type) {
            3 -> 4 to pairSetupStart()
            4 -> 4 to pairSetupNext(tlvOf(message))
            5 -> 6 to pairVerifyStart(tlvOf(message))
            6 -> 6 to pairVerifyNext(tlvOf(message))
            8 -> encryptedRequest(message)
            else -> null
        }
    }

    private fun tlvOf(message: Map<String, Any?>): Map<Int, ByteArray> =
        Tlv8.read(message["_pd"] as ByteArray)

    private fun pairingData(entries: Map<Int, ByteArray>): Map<String, Any?> =
        mapOf("_pd" to Tlv8.write(entries))

    // ------------------------------------------------------------------ SRP, accessory side

    private fun pad(value: BigInteger): ByteArray {
        val bytes = Srp.minimalBytes(value)
        return ByteArray(Srp.PAD_SIZE - bytes.size) + bytes
    }

    private fun hashToInt(vararg parts: ByteArray) = BigInteger(1, Digest.sha512(*parts))

    private val multiplier: BigInteger by lazy { hashToInt(Srp.minimalBytes(Srp.N), pad(Srp.G)) }

    private val passwordX: BigInteger by lazy {
        hashToInt(salt, Digest.sha512("Pair-Setup:$pin".toByteArray()))
    }

    private val verifier: BigInteger by lazy { Srp.G.modPow(passwordX, Srp.N) }

    /** B = (k*v + g^b) mod N */
    private val serverPublic: BigInteger by lazy {
        multiplier.multiply(verifier).add(Srp.G.modPow(serverPrivate, Srp.N)).mod(Srp.N)
    }

    private fun pairSetupStart(): Map<String, Any?> = pairingData(
        linkedMapOf(
            Tlv8.SEQ_NO to byteArrayOf(0x02),
            Tlv8.SALT to salt,
            Tlv8.PUBLIC_KEY to Srp.minimalBytes(serverPublic),
        ),
    )

    private fun pairSetupNext(tlv: Map<Int, ByteArray>): Map<String, Any?> {
        val seq = tlv[Tlv8.SEQ_NO]?.firstOrNull()?.toInt()
        return when (seq) {
            0x03 -> {
                if (rejectProof) {
                    return pairingData(
                        linkedMapOf(
                            Tlv8.SEQ_NO to byteArrayOf(0x04),
                            Tlv8.ERROR to byteArrayOf(0x02),
                        ),
                    )
                }
                val a = BigInteger(1, tlv[Tlv8.PUBLIC_KEY]!!)
                val u = hashToInt(pad(a), pad(serverPublic))
                // S = (A * v^u)^b mod N
                val s = a.multiply(verifier.modPow(u, Srp.N)).modPow(serverPrivate, Srp.N)
                val key = Digest.sha512(Srp.minimalBytes(s))
                srpSessionKey = key

                val hn = hashToInt(Srp.minimalBytes(Srp.N))
                val hg = hashToInt(Srp.minimalBytes(Srp.G))
                val hi = hashToInt("Pair-Setup".toByteArray())
                val expectedProof = Digest.sha512(
                    Srp.minimalBytes(hn.xor(hg)),
                    Srp.minimalBytes(hi),
                    salt,
                    Srp.minimalBytes(a),
                    Srp.minimalBytes(serverPublic),
                    key,
                )
                check(Digest.constantTimeEquals(tlv[Tlv8.PROOF]!!, expectedProof)) {
                    "client proof M1 did not verify"
                }
                pairingData(
                    linkedMapOf(
                        Tlv8.SEQ_NO to byteArrayOf(0x04),
                        Tlv8.PROOF to Digest.sha512(Srp.minimalBytes(a), expectedProof, key),
                    ),
                )
            }
            0x05 -> {
                val key = srpSessionKey!!
                val encryptKey = Digest.hkdfSha512(
                    "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", key,
                )
                val cipher = ChaChaCipherPair(encryptKey, encryptKey, nonceLength = 8)
                val inner = Tlv8.read(
                    cipher.decrypt(tlv[Tlv8.ENCRYPTED_DATA]!!, "PS-Msg05".toByteArray()),
                )
                clientId = inner[Tlv8.IDENTIFIER]!!
                clientPublicKey = inner[Tlv8.PUBLIC_KEY]!!

                val controllerX = Digest.hkdfSha512(
                    "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", key,
                )
                check(
                    Curve25519.ed25519Verify(
                        clientPublicKey!!,
                        controllerX + clientId!! + clientPublicKey!!,
                        inner[Tlv8.SIGNATURE]!!,
                    ),
                ) { "controller signature did not verify" }

                val accessoryX = Digest.hkdfSha512(
                    "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", key,
                )
                val signature = Curve25519.ed25519Sign(
                    deviceSeed,
                    accessoryX + deviceId + devicePublicKey,
                )
                val payload = Tlv8.write(
                    linkedMapOf(
                        Tlv8.IDENTIFIER to deviceId,
                        Tlv8.PUBLIC_KEY to devicePublicKey,
                        Tlv8.SIGNATURE to signature,
                    ),
                )
                pairingData(
                    linkedMapOf(
                        Tlv8.SEQ_NO to byteArrayOf(0x06),
                        Tlv8.ENCRYPTED_DATA to cipher.encrypt(payload, "PS-Msg06".toByteArray()),
                    ),
                )
            }
            else -> error("unexpected pair-setup sequence $seq")
        }
    }

    // ------------------------------------------------------------------ pair-verify

    private var deviceEphemeralPrivate: ByteArray? = null
    private var deviceEphemeralPublic: ByteArray? = null
    private var clientEphemeralPublic: ByteArray? = null
    private var sharedSecret: ByteArray? = null

    private fun pairVerifyStart(tlv: Map<Int, ByteArray>): Map<String, Any?> {
        val clientEph = tlv[Tlv8.PUBLIC_KEY]!!
        clientEphemeralPublic = clientEph
        val priv = ByteArray(32) { (it * 13 + 9).toByte() }
        deviceEphemeralPrivate = priv
        val pub = Curve25519.x25519PublicKey(priv)
        deviceEphemeralPublic = pub
        val shared = Curve25519.x25519(priv, clientEph)
        sharedSecret = shared

        val verifyKey = Digest.hkdfSha512(
            "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared,
        )
        val cipher = ChaChaCipherPair(verifyKey, verifyKey, nonceLength = 8)
        val signature = Curve25519.ed25519Sign(deviceSeed, pub + deviceId + clientEph)
        val payload = Tlv8.write(
            linkedMapOf(Tlv8.IDENTIFIER to deviceId, Tlv8.SIGNATURE to signature),
        )
        return pairingData(
            linkedMapOf(
                Tlv8.SEQ_NO to byteArrayOf(0x02),
                Tlv8.PUBLIC_KEY to pub,
                Tlv8.ENCRYPTED_DATA to cipher.encrypt(payload, "PV-Msg02".toByteArray()),
            ),
        )
    }

    private fun pairVerifyNext(tlv: Map<Int, ByteArray>): Map<String, Any?> {
        val shared = sharedSecret!!
        val verifyKey = Digest.hkdfSha512(
            "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared,
        )
        // A fresh pair so the decrypt counter is not shared with the encrypt above; both
        // messages carry explicit nonces anyway.
        val cipher = ChaChaCipherPair(verifyKey, verifyKey, nonceLength = 8)
        val inner = Tlv8.read(
            cipher.decrypt(tlv[Tlv8.ENCRYPTED_DATA]!!, "PV-Msg03".toByteArray()),
        )
        val identifier = inner[Tlv8.IDENTIFIER]!!
        check(
            Curve25519.ed25519Verify(
                clientPublicKey ?: error("no controller key on file"),
                clientEphemeralPublic!! + identifier + deviceEphemeralPublic!!,
                inner[Tlv8.SIGNATURE]!!,
            ),
        ) { "controller verify signature did not check out" }

        // Mirrored keys: what the controller writes with is what the accessory reads with.
        pendingCipher = ChaChaCipherPair(
            outKey = Digest.hkdfSha512("", "ServerEncrypt-main", shared),
            inKey = Digest.hkdfSha512("", "ClientEncrypt-main", shared),
            nonceLength = 12,
        )
        return pairingData(linkedMapOf(Tlv8.SEQ_NO to byteArrayOf(0x04)))
    }

    /** Pre-load the controller key so pair-verify can run without a prior pair-setup. */
    fun trustController(credentials: com.gios.lightremote.companion.Credentials) {
        clientPublicKey = Curve25519.ed25519PublicKey(credentials.clientPrivateKey)
        clientId = credentials.clientId
    }

    // ------------------------------------------------------------------ session traffic

    private fun encryptedRequest(message: Map<String, Any?>): Pair<Int, Map<String, Any?>>? {
        val identifier = message["_i"] as? String ?: return null
        val type = message["_t"] as? Long
        synchronized(requests) { requests.add(identifier) }
        if (identifier == "_hidC") {
            @Suppress("UNCHECKED_CAST")
            val content = message["_c"] as? Map<String, Any?>
            val state = content?.get("_hBtS") as? Long
            // 1 is DOWN, 2 is UP. Recorded so a test can check that a press arrives as a pair
            // rather than tangled with another press's halves.
            if (state != null) synchronized(hidStates) { hidStates.add(state) }
        }
        if (identifier == "_systemInfo") {
            @Suppress("UNCHECKED_CAST")
            systemInfo = message["_c"] as? Map<String, Any?>
        }
        // Events get no reply.
        if (type == 1L) return null

        val content: Map<String, Any?> = when (identifier) {
            "_sessionStart" -> mapOf("_sid" to 0x11223344L)
            "FetchAttentionState" -> mapOf("state" to 3L)
            "FetchLaunchableApplicationsEvent" -> mapOf(
                "com.netflix.Netflix" to "Netflix",
                "com.apple.TVWatchList" to "TV",
            )
            "_mcc" -> mapOf("_vol" to 0.25)
            // Newer tvOS has no handler for this; mimic that so the tolerant path is used.
            "TVRCSessionStart" -> return 8 to mapOf(
                "_em" to "No request handler",
                "_t" to 3L,
                "_x" to message["_x"],
            )
            else -> emptyMap()
        }
        return 8 to linkedMapOf(
            "_i" to identifier,
            "_t" to 3L,
            "_c" to content,
            "_x" to message["_x"],
        )
    }
}
