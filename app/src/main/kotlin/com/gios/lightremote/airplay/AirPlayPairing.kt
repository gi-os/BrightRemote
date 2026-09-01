package com.gios.lightremote.airplay

import com.gios.lightremote.companion.AuthenticationException
import com.gios.lightremote.companion.Credentials
import com.gios.lightremote.crypto.ChaChaCipherPair
import com.gios.lightremote.crypto.Curve25519
import com.gios.lightremote.crypto.Digest
import com.gios.lightremote.crypto.Srp
import com.gios.lightremote.proto.Tlv8
import java.util.UUID

/**
 * HAP pairing and verification on the AirPlay channel.
 *
 * The AirPlay control channel authenticates with the same HAP/SRP machinery as Companion —
 * SRP-6a, Ed25519, X25519, ChaCha20-Poly1305, HKDF-SHA512 — but over HTTP `POST`s to
 * `/pair-setup` and `/pair-verify` rather than Companion's binary frames, and it derives a
 * different set of session keys (Control / Events / DataStream instead of ClientEncrypt-main).
 * Everything here reuses the existing hand-written primitives; only the transport and the label
 * strings differ.
 *
 * Two ways in:
 *
 *  - **Transient** (`X-Apple-HKP: 4`): SRP with the well-known PIN 3939 and no long-term keys,
 *    just the four setup states, after which the SRP session key is the shared secret. This is
 *    what HomePod remote-control tunneling uses and it needs nothing stored.
 *  - **Verify** with saved [Credentials], for a device previously paired with [pairSetup]
 *    (`X-Apple-HKP: 3`): the X25519 exchange whose shared secret keys the session.
 *
 * A successful handshake returns [AirPlayKeys], which knows how to derive the per-channel
 * encryption keys from whichever shared secret it holds.
 */
class AirPlayPairing(private val channel: RtspChannel) {

    companion object {
        private const val TRANSIENT_PIN = "3939"
        private val AIRPLAY_HEADERS = linkedMapOf(
            "User-Agent" to "AirPlay/320.20",
            "Connection" to "keep-alive",
            "Content-Type" to "application/octet-stream",
        )
    }

    /**
     * The shared secret from a completed handshake, plus the derivation the channels need.
     *
     * For transient this is the SRP session key; for verify it is the X25519 shared secret.
     * Either way the per-channel keys come out of it by HKDF with the channel's own salt/info,
     * which is exactly what pyatv's `encryption_keys` does.
     */
    class AirPlayKeys(val sharedSecret: ByteArray) {
        fun derive(salt: String, outputInfo: String, inputInfo: String): Pair<ByteArray, ByteArray> =
            Digest.hkdfSha512(salt, outputInfo, sharedSecret) to
                Digest.hkdfSha512(salt, inputInfo, sharedSecret)
    }

    private fun post(path: String, hkp: Int, body: ByteArray?): HttpCodec.Response {
        val headers = LinkedHashMap(AIRPLAY_HEADERS)
        headers["X-Apple-HKP"] = hkp.toString()
        val request = HttpCodec.formatRequest(
            method = "POST",
            uri = path,
            headers = headers,
            body = body,
            protocol = "HTTP/1.1",
            userAgent = "AirPlay/320.20",
        )
        return channel.exchange(request)
    }

    private fun tlv(body: ByteArray): Map<Int, ByteArray> {
        val map = Tlv8.read(body)
        Tlv8.errorMessage(map)?.let { throw AuthenticationException(it) }
        return map
    }

    // ------------------------------------------------------------------ transient

    /**
     * Transient pairing (M1–M3, PIN 3939), yielding session keys with nothing stored.
     *
     * Deliberately does NOT touch `/pair-pin-start`. That endpoint has exactly one effect on
     * tvOS: it puts a four-digit pairing code on the television. Transient pairing never needs
     * one — the PIN is the well-known 3939 — but this function used to open with that POST
     * anyway, copying the shape of the interactive flow. Against the fake receiver (which
     * answers 200 and moves on) it was invisible; against a real Apple TV it drew a PIN prompt
     * over whatever was playing, then the SRP exchange failed because tvOS does not support
     * transient pairing at all, the tunnel quietly gave up — and the code stayed on the screen
     * with nowhere in the app to type it. A silent probe must stay silent: the only route to
     * `/pair-pin-start` is [beginPairSetup], which only ever runs because the user asked.
     */
    fun transient(): AirPlayKeys {
        val m1 = Tlv8.write(
            linkedMapOf(
                Tlv8.METHOD to byteArrayOf(0x00),
                Tlv8.SEQ_NO to byteArrayOf(0x01),
                Tlv8.FLAGS to byteArrayOf(0x10), // TransientPairing
            ),
        )
        val m2 = tlv(post("/pair-setup", hkp = 4, body = m1).body)
        val salt = m2[Tlv8.SALT] ?: throw AuthenticationException("transient pairing: no salt")
        val serverKey = m2[Tlv8.PUBLIC_KEY]
            ?: throw AuthenticationException("transient pairing: no public key")

        val srp = Srp.Session(Curve25519.randomBytes(32))
        srp.process(salt, serverKey, TRANSIENT_PIN)

        val m3 = Tlv8.write(
            linkedMapOf(
                Tlv8.SEQ_NO to byteArrayOf(0x03),
                Tlv8.PUBLIC_KEY to srp.publicKeyBytes(),
                Tlv8.PROOF to srp.proof,
            ),
        )
        // The device's M4 proof is not checked here — pyatv does not either, and a wrong shared
        // secret simply fails to decrypt the first data frame, which is caught a step later.
        tlv(post("/pair-setup", hkp = 4, body = m3).body)

        return AirPlayKeys(srp.sessionKey)
    }

    // ------------------------------------------------------------------ verify (stored creds)

    /** Pair-verify against a device this app paired with earlier via [pairSetup]. */
    fun verify(credentials: Credentials): AirPlayKeys {
        val ephemeralPrivate = Curve25519.randomBytes(32)
        val ephemeralPublic = Curve25519.x25519PublicKey(ephemeralPrivate)

        val m1 = Tlv8.write(
            linkedMapOf(
                Tlv8.SEQ_NO to byteArrayOf(0x01),
                Tlv8.PUBLIC_KEY to ephemeralPublic,
            ),
        )
        val m2 = tlv(post("/pair-verify", hkp = 3, body = m1).body)
        val devicePublic = m2[Tlv8.PUBLIC_KEY]
            ?: throw AuthenticationException("verify: no device public key")
        val sealed = m2[Tlv8.ENCRYPTED_DATA]
            ?: throw AuthenticationException("verify: no encrypted data")

        val shared = Curve25519.x25519(ephemeralPrivate, devicePublic)
        val verifyKey = Digest.hkdfSha512("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
        val cipher = ChaChaCipherPair(verifyKey, verifyKey, nonceLength = 8)

        val inner = Tlv8.read(
            try {
                cipher.decrypt(sealed, nonce = "PV-Msg02".toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                throw AuthenticationException("this Apple TV no longer recognises us on AirPlay")
            },
        )
        val identifier = inner[Tlv8.IDENTIFIER]
            ?: throw AuthenticationException("verify: no device identifier")
        val signature = inner[Tlv8.SIGNATURE]
            ?: throw AuthenticationException("verify: no device signature")
        if (!Curve25519.ed25519Verify(
                credentials.devicePublicKey,
                devicePublic + identifier + ephemeralPublic,
                signature,
            )
        ) {
            throw AuthenticationException("the Apple TV failed to prove its AirPlay identity")
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
        val m3 = Tlv8.write(linkedMapOf(Tlv8.SEQ_NO to byteArrayOf(0x03), Tlv8.ENCRYPTED_DATA to reply))
        tlv(post("/pair-verify", hkp = 3, body = m3).body)

        return AirPlayKeys(shared)
    }

    // ------------------------------------------------------------------ pair-setup (PIN)

    /** State between "show me a PIN" and "here is the PIN" for an AirPlay HAP pairing. */
    class Setup internal constructor(
        internal val clientPrivateKey: ByteArray,
        internal val clientId: ByteArray,
        internal val salt: ByteArray,
        internal val serverPublicKey: ByteArray,
    )

    /** Ask the device to display an AirPlay pairing PIN. */
    fun beginPairSetup(): Setup {
        post("/pair-pin-start", hkp = 3, body = null)
        val m1 = Tlv8.write(
            linkedMapOf(
                Tlv8.METHOD to byteArrayOf(0x00),
                Tlv8.SEQ_NO to byteArrayOf(0x01),
            ),
        )
        val m2 = tlv(post("/pair-setup", hkp = 3, body = m1).body)
        val salt = m2[Tlv8.SALT] ?: throw AuthenticationException("no salt in the pairing response")
        val serverKey = m2[Tlv8.PUBLIC_KEY]
            ?: throw AuthenticationException("no public key in the pairing response")
        return Setup(Curve25519.randomBytes(32), UUID.randomUUID().toString().toByteArray(), salt, serverKey)
    }

    /** Finish an AirPlay pairing with the code shown on screen, yielding long-term credentials. */
    fun completePairSetup(setup: Setup, pin: String, displayName: String?): Credentials {
        val srp = Srp.Session(setup.clientPrivateKey)
        srp.process(setup.salt, setup.serverPublicKey, pin)

        val m3 = Tlv8.write(
            linkedMapOf(
                Tlv8.SEQ_NO to byteArrayOf(0x03),
                Tlv8.PUBLIC_KEY to srp.publicKeyBytes(),
                Tlv8.PROOF to srp.proof,
            ),
        )
        val m4 = tlv(post("/pair-setup", hkp = 3, body = m3).body)
        val deviceProof = m4[Tlv8.PROOF] ?: throw AuthenticationException("the Apple TV sent no proof")
        if (!Digest.constantTimeEquals(deviceProof, srp.expectedServerProof)) {
            throw AuthenticationException("the Apple TV's proof did not match — check the code")
        }

        val signingSeed = setup.clientPrivateKey
        val clientPublicKey = Curve25519.ed25519PublicKey(signingSeed)
        val deviceX = Digest.hkdfSha512("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", srp.sessionKey)
        val encryptionKey = Digest.hkdfSha512("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", srp.sessionKey)
        val signature = Curve25519.ed25519Sign(signingSeed, deviceX + setup.clientId + clientPublicKey)

        val payload = linkedMapOf(
            Tlv8.IDENTIFIER to setup.clientId,
            Tlv8.PUBLIC_KEY to clientPublicKey,
            Tlv8.SIGNATURE to signature,
        )
        if (displayName != null) {
            payload[Tlv8.NAME] = displayName.toByteArray(Charsets.UTF_8)
        }
        val cipher = ChaChaCipherPair(encryptionKey, encryptionKey, nonceLength = 8)
        val encrypted = cipher.encrypt(Tlv8.write(payload), nonce = "PS-Msg05".toByteArray(Charsets.UTF_8))

        val m6 = tlv(
            post(
                "/pair-setup",
                hkp = 3,
                body = Tlv8.write(
                    linkedMapOf(
                        Tlv8.SEQ_NO to byteArrayOf(0x05),
                        Tlv8.ENCRYPTED_DATA to encrypted,
                    ),
                ),
            ).body,
        )
        val sealed = m6[Tlv8.ENCRYPTED_DATA] ?: throw AuthenticationException("no credentials returned")
        val decrypted = try {
            cipher.decrypt(sealed, nonce = "PS-Msg06".toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw AuthenticationException("could not decrypt the pairing response — wrong code?")
        }
        val result = Tlv8.read(decrypted)
        val deviceId = result[Tlv8.IDENTIFIER] ?: throw AuthenticationException("no device id in response")
        val devicePublicKey = result[Tlv8.PUBLIC_KEY] ?: throw AuthenticationException("no device key in response")
        return Credentials(
            devicePublicKey = devicePublicKey,
            clientPrivateKey = signingSeed,
            deviceId = deviceId,
            clientId = setup.clientId,
        )
    }
}
