package com.gios.lightremote.companion

import com.gios.lightremote.crypto.ChaChaCipherPair
import com.gios.lightremote.crypto.Curve25519
import com.gios.lightremote.crypto.Digest
import com.gios.lightremote.crypto.Srp
import com.gios.lightremote.proto.Opack
import com.gios.lightremote.proto.Tlv8
import java.util.UUID

class AuthenticationException(message: String) : Exception(message)

/**
 * HAP pair-setup and pair-verify over the Companion link.
 *
 * Pair-setup runs once, when the four-digit code is on the TV, and yields long-term
 * [Credentials]. Pair-verify runs on every connection afterwards and yields the two
 * session keys.
 *
 * The salts and info strings below are not interchangeable and not guessable — each
 * HKDF label is part of the protocol, and a typo shows up as a device that accepts the
 * handshake and then ignores everything sent afterwards.
 */
class CompanionAuth(private val session: CompanionSession) {

    companion object {
        private const val PAIRING_DATA = "_pd"

        private const val SETUP_SIGN_SALT = "Pair-Setup-Controller-Sign-Salt"
        private const val SETUP_SIGN_INFO = "Pair-Setup-Controller-Sign-Info"
        private const val SETUP_ENCRYPT_SALT = "Pair-Setup-Encrypt-Salt"
        private const val SETUP_ENCRYPT_INFO = "Pair-Setup-Encrypt-Info"
        private const val VERIFY_ENCRYPT_SALT = "Pair-Verify-Encrypt-Salt"
        private const val VERIFY_ENCRYPT_INFO = "Pair-Verify-Encrypt-Info"

        /** Session keys use an empty salt; only the info string separates the directions. */
        private const val SESSION_SALT = ""
        private const val SESSION_OUTPUT_INFO = "ClientEncrypt-main"
        private const val SESSION_INPUT_INFO = "ServerEncrypt-main"
    }

    private fun pairingData(message: Map<String, Any?>): Map<Int, ByteArray> {
        val raw = message[PAIRING_DATA] as? ByteArray
            ?: throw AuthenticationException("the Apple TV sent no pairing data")
        val tlv = Tlv8.read(raw)
        Tlv8.errorMessage(tlv)?.let { throw AuthenticationException(it) }
        return tlv
    }

    // ------------------------------------------------------------------ pair-setup

    /** State carried between "show me a PIN" and "here is the PIN". */
    class PairSetup internal constructor(
        internal val clientPrivateKey: ByteArray,
        internal val clientId: ByteArray,
        internal val salt: ByteArray,
        internal val serverPublicKey: ByteArray,
    )

    /** Ask the TV to display a PIN. */
    suspend fun beginPairSetup(): PairSetup {
        // One 32-byte secret does double duty: the SRP exponent 'a' now, and the long-term
        // Ed25519 seed afterwards. pyatv does the same, so credentials stay interchangeable.
        val clientPrivateKey = Curve25519.randomBytes(32)
        val clientId = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)

        val response = session.exchangeAuth(
            FrameType.PairSetupStart,
            linkedMapOf(
                PAIRING_DATA to Tlv8.write(
                    linkedMapOf(
                        Tlv8.METHOD to byteArrayOf(0x00),
                        Tlv8.SEQ_NO to byteArrayOf(0x01),
                    ),
                ),
                "_pwTy" to 1L,
            ),
        )

        val tlv = pairingData(response)
        val salt = tlv[Tlv8.SALT] ?: throw AuthenticationException("no salt in the pairing response")
        val serverKey = tlv[Tlv8.PUBLIC_KEY]
            ?: throw AuthenticationException("no public key in the pairing response")
        return PairSetup(clientPrivateKey, clientId, salt, serverKey)
    }

    /** Finish pairing with the code shown on screen. */
    suspend fun completePairSetup(
        setup: PairSetup,
        pin: String,
        displayName: String?,
    ): Credentials {
        val srp = Srp.Session(setup.clientPrivateKey)
        srp.process(setup.salt, setup.serverPublicKey, pin)

        val proofResponse = session.exchangeAuth(
            FrameType.PairSetupNext,
            linkedMapOf(
                PAIRING_DATA to Tlv8.write(
                    linkedMapOf(
                        Tlv8.SEQ_NO to byteArrayOf(0x03),
                        Tlv8.PUBLIC_KEY to srp.publicKeyBytes(),
                        Tlv8.PROOF to srp.proof,
                    ),
                ),
                "_pwTy" to 1L,
            ),
        )

        val proofTlv = pairingData(proofResponse)
        val deviceProof = proofTlv[Tlv8.PROOF]
            ?: throw AuthenticationException("the Apple TV sent no proof")
        if (!Digest.constantTimeEquals(deviceProof, srp.expectedServerProof)) {
            // A mismatch here means the device derived a different session key, so the
            // rest of the exchange cannot succeed. In practice this is a wrong PIN that
            // the device chose not to flag explicitly.
            throw AuthenticationException("the Apple TV's proof did not match — check the code")
        }

        val signingSeed = setup.clientPrivateKey
        val clientPublicKey = Curve25519.ed25519PublicKey(signingSeed)
        val deviceX = Digest.hkdfSha512(SETUP_SIGN_SALT, SETUP_SIGN_INFO, srp.sessionKey)
        val encryptionKey = Digest.hkdfSha512(SETUP_ENCRYPT_SALT, SETUP_ENCRYPT_INFO, srp.sessionKey)

        val signature = Curve25519.ed25519Sign(
            signingSeed,
            deviceX + setup.clientId + clientPublicKey,
        )
        val payload = linkedMapOf(
            Tlv8.IDENTIFIER to setup.clientId,
            Tlv8.PUBLIC_KEY to clientPublicKey,
            Tlv8.SIGNATURE to signature,
        )
        // The name is what shows up under Settings > Remotes and Devices on the TV.
        if (displayName != null) {
            payload[Tlv8.NAME] = Opack.pack(mapOf("name" to displayName))
        }

        val cipher = ChaChaCipherPair(encryptionKey, encryptionKey, nonceLength = 8)
        val encrypted = cipher.encrypt(
            Tlv8.write(payload),
            nonce = "PS-Msg05".toByteArray(Charsets.UTF_8),
        )

        val finalResponse = session.exchangeAuth(
            FrameType.PairSetupNext,
            linkedMapOf(
                PAIRING_DATA to Tlv8.write(
                    linkedMapOf(
                        Tlv8.SEQ_NO to byteArrayOf(0x05),
                        Tlv8.ENCRYPTED_DATA to encrypted,
                    ),
                ),
                "_pwTy" to 1L,
            ),
        )

        val sealed = pairingData(finalResponse)[Tlv8.ENCRYPTED_DATA]
            ?: throw AuthenticationException("the Apple TV sent no credentials")
        val decrypted = try {
            cipher.decrypt(sealed, nonce = "PS-Msg06".toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw AuthenticationException("could not decrypt the pairing response — wrong code?")
        }

        val result = Tlv8.read(decrypted)
        val deviceId = result[Tlv8.IDENTIFIER]
            ?: throw AuthenticationException("no device identifier in the pairing response")
        val devicePublicKey = result[Tlv8.PUBLIC_KEY]
            ?: throw AuthenticationException("no device public key in the pairing response")
        val deviceSignature = result[Tlv8.SIGNATURE]

        // pyatv skips this check. Doing it means a botched pairing is caught now rather
        // than as a mysterious failure on the next connection, so it is worth the code —
        // but only as a warning path, since a false negative here would block a pairing
        // that would otherwise have worked.
        if (deviceSignature != null) {
            val deviceInfo = Digest.hkdfSha512(
                "Pair-Setup-Accessory-Sign-Salt",
                "Pair-Setup-Accessory-Sign-Info",
                srp.sessionKey,
            ) + deviceId + devicePublicKey
            if (!Curve25519.ed25519Verify(devicePublicKey, deviceInfo, deviceSignature)) {
                lastWarning = "the Apple TV's identity signature did not verify"
            }
        }

        return Credentials(
            devicePublicKey = devicePublicKey,
            clientPrivateKey = signingSeed,
            deviceId = deviceId,
            clientId = setup.clientId,
        )
    }

    /** Non-fatal oddity from the last handshake, surfaced for logging. */
    var lastWarning: String? = null
        private set

    // ------------------------------------------------------------------ pair-verify

    /** The two directional session keys produced by a successful verify. */
    class SessionKeys(val outputKey: ByteArray, val inputKey: ByteArray)

    suspend fun verify(credentials: Credentials): SessionKeys {
        val ephemeralPrivate = Curve25519.randomBytes(32)
        val ephemeralPublic = Curve25519.x25519PublicKey(ephemeralPrivate)

        val response = session.exchangeAuth(
            FrameType.PairVerifyStart,
            linkedMapOf(
                PAIRING_DATA to Tlv8.write(
                    linkedMapOf(
                        Tlv8.SEQ_NO to byteArrayOf(0x01),
                        Tlv8.PUBLIC_KEY to ephemeralPublic,
                    ),
                ),
                "_auTy" to 4L,
            ),
        )

        val tlv = pairingData(response)
        val devicePublic = tlv[Tlv8.PUBLIC_KEY]
            ?: throw AuthenticationException("no session key from the Apple TV")
        val sealed = tlv[Tlv8.ENCRYPTED_DATA]
            ?: throw AuthenticationException("no signature from the Apple TV")

        val shared = Curve25519.x25519(ephemeralPrivate, devicePublic)
        val verifyKey = Digest.hkdfSha512(VERIFY_ENCRYPT_SALT, VERIFY_ENCRYPT_INFO, shared)
        val cipher = ChaChaCipherPair(verifyKey, verifyKey, nonceLength = 8)

        val decrypted = try {
            cipher.decrypt(sealed, nonce = "PV-Msg02".toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw AuthenticationException("this Apple TV no longer recognises us — pair again")
        }
        val inner = Tlv8.read(decrypted)
        val identifier = inner[Tlv8.IDENTIFIER]
            ?: throw AuthenticationException("no identifier in the verify response")
        val signature = inner[Tlv8.SIGNATURE]
            ?: throw AuthenticationException("no signature in the verify response")

        if (!identifier.contentEquals(credentials.deviceId)) {
            throw AuthenticationException("a different Apple TV answered at this address")
        }
        if (!Curve25519.ed25519Verify(
                credentials.devicePublicKey,
                devicePublic + identifier + ephemeralPublic,
                signature,
            )
        ) {
            throw AuthenticationException("the Apple TV failed to prove its identity")
        }

        val ourSignature = Curve25519.ed25519Sign(
            credentials.clientPrivateKey,
            ephemeralPublic + credentials.clientId + devicePublic,
        )
        val reply = cipher.encrypt(
            Tlv8.write(
                linkedMapOf(
                    Tlv8.IDENTIFIER to credentials.clientId,
                    Tlv8.SIGNATURE to ourSignature,
                ),
            ),
            nonce = "PV-Msg03".toByteArray(Charsets.UTF_8),
        )

        val confirmation = session.exchangeAuth(
            FrameType.PairVerifyNext,
            linkedMapOf(
                PAIRING_DATA to Tlv8.write(
                    linkedMapOf(
                        Tlv8.SEQ_NO to byteArrayOf(0x03),
                        Tlv8.ENCRYPTED_DATA to reply,
                    ),
                ),
            ),
        )
        pairingData(confirmation) // throws if the device reported an error

        return SessionKeys(
            outputKey = Digest.hkdfSha512(SESSION_SALT, SESSION_OUTPUT_INFO, shared),
            inputKey = Digest.hkdfSha512(SESSION_SALT, SESSION_INPUT_INFO, shared),
        )
    }
}
