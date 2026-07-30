package com.gios.lightremote

import com.gios.lightremote.crypto.ChaCha20Poly1305
import com.gios.lightremote.crypto.ChaChaCipherPair
import com.gios.lightremote.crypto.Curve25519
import com.gios.lightremote.crypto.Digest
import com.gios.lightremote.crypto.Srp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoTest {

    @Test
    fun `hkdf sha512 matches reference`() {
        repeat(Vectors.count("hkdf.count")) { i ->
            val (salt, info, ikm, expected) = Vectors.fields("hkdf.$i")
            assertEquals(
                expected,
                Vectors.encodeHex(Digest.hkdfSha512(salt, info, Vectors.decodeHex(ikm))),
                "HKDF salt='$salt' info='$info'",
            )
        }
    }

    @Test
    fun `chacha20poly1305 matches reference`() {
        repeat(Vectors.count("chacha.count")) { i ->
            val f = Vectors.fields("chacha.$i")
            val key = Vectors.decodeHex(f[0])
            val nonce = Vectors.decodeHex(f[1])
            val aad = Vectors.decodeHex(f[2])
            val plaintext = Vectors.decodeHex(f[3])
            val expected = f[4]

            assertEquals(
                expected,
                Vectors.encodeHex(ChaCha20Poly1305.encrypt(key, nonce, plaintext, aad)),
                "chacha encrypt case $i",
            )
            assertEquals(
                f[3],
                Vectors.encodeHex(
                    ChaCha20Poly1305.decrypt(key, nonce, Vectors.decodeHex(expected), aad),
                ),
                "chacha decrypt case $i",
            )
        }
    }

    @Test
    fun `chacha20poly1305 rejects a tampered tag`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12)
        val sealed = ChaCha20Poly1305.encrypt(key, nonce, "hello".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertFailsWith<ChaCha20Poly1305.AuthenticationFailure> {
            ChaCha20Poly1305.decrypt(key, nonce, sealed)
        }
    }

    @Test
    fun `chacha20poly1305 rejects a tampered aad`() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val nonce = ByteArray(12) { 7 }
        val header = byteArrayOf(8, 0, 0, 16)
        val sealed = ChaCha20Poly1305.encrypt(key, nonce, ByteArray(16), header)
        assertFailsWith<ChaCha20Poly1305.AuthenticationFailure> {
            ChaCha20Poly1305.decrypt(key, nonce, sealed, byteArrayOf(8, 0, 0, 17))
        }
    }

    /**
     * The counter pair is where a subtle mistake would cost the most: the pairing frames
     * use 8-byte nonces padded on the left, the session stream uses a full 12 bytes, and
     * the two directions count independently.
     */
    @Test
    fun `cipher pair advances each direction independently`() {
        val outKey = ByteArray(32) { it.toByte() }
        val inKey = ByteArray(32) { (255 - it).toByte() }

        val client = ChaChaCipherPair(outKey, inKey, nonceLength = 12)
        val server = ChaChaCipherPair(inKey, outKey, nonceLength = 12)

        // Three frames one way, two back, interleaved.
        val a = client.encrypt("one".toByteArray())
        val b = client.encrypt("two".toByteArray())
        val c = server.encrypt("back".toByteArray())
        assertEquals("one", String(server.decrypt(a)))
        assertEquals("back", String(client.decrypt(c)))
        assertEquals("two", String(server.decrypt(b)))
        val d = client.encrypt("three".toByteArray())
        assertEquals("three", String(server.decrypt(d)))
    }

    @Test
    fun `explicit nonce does not advance the counter`() {
        val key = ByteArray(32) { 9 }
        val pair = ChaChaCipherPair(key, key, nonceLength = 8)
        val withName = pair.encrypt("x".toByteArray(), "PS-Msg05".toByteArray())
        val counterZero = pair.encrypt("x".toByteArray())
        // If the named nonce had bumped the counter, this would have used counter 1.
        val fresh = ChaChaCipherPair(key, key, nonceLength = 8).encrypt("x".toByteArray())
        assertEquals(Vectors.encodeHex(fresh), Vectors.encodeHex(counterZero))
        assertFalse(withName.contentEquals(counterZero))
    }

    @Test
    fun `ed25519 matches reference signatures`() {
        repeat(Vectors.count("ed25519.count")) { i ->
            val f = Vectors.fields("ed25519.$i")
            val seed = Vectors.decodeHex(f[0])
            val message = Vectors.decodeHex(f[2])

            assertEquals(f[1], Vectors.encodeHex(Curve25519.ed25519PublicKey(seed)), "public key $i")
            assertEquals(f[3], Vectors.encodeHex(Curve25519.ed25519Sign(seed, message)), "signature $i")
            assertTrue(
                Curve25519.ed25519Verify(Vectors.decodeHex(f[1]), message, Vectors.decodeHex(f[3])),
                "verify $i",
            )
        }
    }

    @Test
    fun `ed25519 rejects a corrupted signature`() {
        val f = Vectors.fields("ed25519.1")
        val publicKey = Vectors.decodeHex(f[1])
        val message = Vectors.decodeHex(f[2])
        val signature = Vectors.decodeHex(f[3])

        for (index in intArrayOf(0, 31, 32, 63)) {
            val broken = signature.copyOf()
            broken[index] = (broken[index] + 1).toByte()
            assertFalse(Curve25519.ed25519Verify(publicKey, message, broken), "flipped byte $index")
        }
        assertFalse(Curve25519.ed25519Verify(publicKey, message + 1, signature), "changed message")
    }

    @Test
    fun `x25519 matches reference agreement`() {
        repeat(Vectors.count("x25519.count")) { i ->
            val f = Vectors.fields("x25519.$i")
            assertEquals(f[1], Vectors.encodeHex(Curve25519.x25519PublicKey(Vectors.decodeHex(f[0]))), "a_pub $i")
            assertEquals(f[3], Vectors.encodeHex(Curve25519.x25519PublicKey(Vectors.decodeHex(f[2]))), "b_pub $i")
            // Both sides must land on the same secret.
            assertEquals(
                f[4],
                Vectors.encodeHex(Curve25519.x25519(Vectors.decodeHex(f[0]), Vectors.decodeHex(f[3]))),
                "shared from a $i",
            )
            assertEquals(
                f[4],
                Vectors.encodeHex(Curve25519.x25519(Vectors.decodeHex(f[2]), Vectors.decodeHex(f[1]))),
                "shared from b $i",
            )
        }
    }

    @Test
    fun `x25519 refuses a small order public key`() {
        assertFailsWith<IllegalArgumentException> {
            Curve25519.x25519(ByteArray(32) { 1 }, ByteArray(32))
        }
    }

    /**
     * The whole SRP exchange against `srptools`, which is the implementation pyatv drives
     * and therefore the one an Apple TV is known to accept.
     */
    @Test
    fun `srp reproduces the reference pair-setup exchange`() {
        val session = Srp.Session(Vectors.hex("srp.a"))
        assertEquals(Vectors["srp.A"], Vectors.encodeHex(session.publicKeyBytes()), "client public A")

        session.process(
            salt = Vectors.hex("srp.salt"),
            serverPublicKey = Vectors.hex("srp.B"),
            pin = Vectors["srp.pin"],
        )
        assertEquals(Vectors["srp.K"], Vectors.encodeHex(session.sessionKey), "session key K")
        assertEquals(Vectors["srp.M1"], Vectors.encodeHex(session.proof), "client proof M1")
        assertEquals(Vectors["srp.M2"], Vectors.encodeHex(session.expectedServerProof), "server proof M2")
    }

    @Test
    fun `srp minimal serialisation drops leading zeros`() {
        assertEquals("00", Vectors.encodeHex(Srp.minimalBytes(java.math.BigInteger.ZERO)))
        assertEquals("05", Vectors.encodeHex(Srp.minimalBytes(java.math.BigInteger.valueOf(5))))
        assertEquals("0100", Vectors.encodeHex(Srp.minimalBytes(java.math.BigInteger.valueOf(256))))
        // 2^256-1 is 32 bytes of 0xFF, and because the top bit is set BigInteger.toByteArray()
        // prepends a 0x00 sign byte. That byte must be stripped: leave it in and every SRP
        // proof changes, with nothing but a failed pairing to show why.
        val allOnes = java.math.BigInteger.TWO.pow(256).subtract(java.math.BigInteger.ONE)
        assertEquals(33, allOnes.toByteArray().size, "BigInteger prepends a sign byte here")
        assertEquals(32, Srp.minimalBytes(allOnes).size)
        assertEquals("ff".repeat(32), Vectors.encodeHex(Srp.minimalBytes(allOnes)))
    }

    @Test
    fun `a wrong pin produces a different proof`() {
        val a = Srp.Session(Vectors.hex("srp.a"))
        a.process(Vectors.hex("srp.salt"), Vectors.hex("srp.B"), "1234")
        val b = Srp.Session(Vectors.hex("srp.a"))
        b.process(Vectors.hex("srp.salt"), Vectors.hex("srp.B"), "4321")
        assertFalse(a.proof.contentEquals(b.proof))
    }

    private operator fun <T> List<T>.component4(): T = this[3]
    private operator fun <T> List<T>.component5(): T = this[4]
}
