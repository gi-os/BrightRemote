package com.gios.lightremote.crypto

import java.math.BigInteger

/**
 * SRP-6a as HAP pair-setup uses it: RFC 5054 group 15 (3072-bit), generator 5, SHA-512,
 * username "Pair-Setup" and the on-screen PIN as the password.
 *
 * The byte-level details matter more than the algebra here. Apple's implementation is
 * whatever `srptools` (which pyatv drives) happens to do, and `srptools` serialises
 * integers with Python's `'%x'` — that is *minimal* big-endian, with leading zero bytes
 * dropped — everywhere except the two places RFC 5054 calls for explicit padding. Pad
 * where it does not, or fail to pad where it does, and the proof mismatches with no
 * useful error. [minimalBytes] exists to reproduce that exactly.
 */
object Srp {

    /** RFC 5054 group 15 modulus. */
    val N: BigInteger = BigInteger(
        "ffffffffffffffffc90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74" +
            "020bbea63b139b22514a08798e3404ddef9519b3cd3a431b302b0a6df25f1437" +
            "4fe1356d6d51c245e485b576625e7ec6f44c42e9a637ed6b0bff5cb6f406b7ed" +
            "ee386bfb5a899fa5ae9f24117c4b1fe649286651ece45b3dc2007cb8a163bf05" +
            "98da48361c55d39a69163fa8fd24cf5f83655d23dca3ad961c62f356208552bb" +
            "9ed529077096966d670c354e4abc9804f1746c08ca18217c32905e462e36ce3b" +
            "e39e772c180e86039b2783a2ec07a28fb5c55df06f4c52c9de2bcbf695581718" +
            "3995497cea956ae515d2261898fa051015728e5a8aaac42dad33170d04507a33" +
            "a85521abdf1cba64ecfb850458dbef0a8aea71575d060c7db3970f85a6e1e4c7" +
            "abf5ae8cdb0933d71e8c94e04a25619dcee3d2261ad2ee6bf12ffa06d98a0864" +
            "d87602733ec86a64521f2b18177b200cbbe117577a615d6c770988c0bad946e2" +
            "08e24fa074e5ab3143db5bfce0fd108e4b82d120a93ad2caffffffffffffffff",
        16,
    )

    val G: BigInteger = BigInteger.valueOf(5)

    /** Width of an explicitly padded value: the modulus size, 3072 bits. */
    const val PAD_SIZE = 384

    private const val USERNAME = "Pair-Setup"

    /**
     * Big-endian with no leading zero bytes — Python's `unhexlify('%x' % value)`.
     * Zero encodes as a single 0x00 byte, matching `'%x' % 0 == '0'` padded to `'00'`.
     */
    fun minimalBytes(value: BigInteger): ByteArray {
        require(value.signum() >= 0) { "cannot serialise a negative SRP value" }
        if (value.signum() == 0) return byteArrayOf(0)
        val raw = value.toByteArray() // two's complement, may carry one leading 0x00
        var start = 0
        while (start < raw.size - 1 && raw[start] == 0.toByte()) start++
        return raw.copyOfRange(start, raw.size)
    }

    /** Left-pad with zeros to the modulus width, for the two RFC 5054 PAD() call sites. */
    private fun pad(value: BigInteger): ByteArray {
        val bytes = minimalBytes(value)
        require(bytes.size <= PAD_SIZE) { "SRP value wider than the modulus" }
        return ByteArray(PAD_SIZE - bytes.size) + bytes
    }

    private fun hashToInt(vararg parts: ByteArray): BigInteger =
        BigInteger(1, Digest.sha512(*parts))

    /** k = H(N | PAD(g)) */
    private val K_MULTIPLIER: BigInteger by lazy { hashToInt(minimalBytes(N), pad(G)) }

    /**
     * The client half of one pair-setup exchange.
     *
     * @param privateKey the SRP secret 'a'. pyatv reuses the 32-byte Ed25519 seed here,
     *   so this is 256 bits rather than the 3072 RFC 5054 suggests. Kept identical for
     *   compatibility — the device does not care how wide 'a' is.
     */
    class Session(private val privateKey: ByteArray) {

        private val a: BigInteger = BigInteger(1, privateKey)

        /** A = g^a mod N */
        val publicKey: BigInteger = G.modPow(a, N)

        lateinit var sessionKey: ByteArray
            private set

        lateinit var proof: ByteArray
            private set

        /** The proof the device is expected to send back, M2 = H(A | M1 | K). */
        lateinit var expectedServerProof: ByteArray
            private set

        /**
         * Process the device's salt and public key, producing the client proof.
         *
         * @param salt raw salt bytes from the device (TLV 0x02)
         * @param serverPublicKey raw B bytes from the device (TLV 0x03)
         * @param pin the four-digit code shown on the TV
         */
        fun process(salt: ByteArray, serverPublicKey: ByteArray, pin: String) {
            val b = BigInteger(1, serverPublicKey)
            require(b.mod(N).signum() != 0) { "device sent an invalid SRP public key" }

            // x = H(s | H(I | ":" | P))
            val inner = Digest.sha512("$USERNAME:$pin".toByteArray(Charsets.UTF_8))
            val x = hashToInt(salt, inner)

            // u = H(PAD(A) | PAD(B))
            val u = hashToInt(pad(publicKey), pad(b))

            // S = (B - k*v)^(a + u*x) mod N, with v = g^x mod N
            val v = G.modPow(x, N)
            val base = b.subtract(K_MULTIPLIER.multiply(v)).mod(N)
            val s = base.modPow(a.add(u.multiply(x)), N)

            sessionKey = Digest.sha512(minimalBytes(s))

            // M1 = H(H(N) xor H(g) | H(I) | s | A | B | K)
            val hn = hashToInt(minimalBytes(N))
            val hg = hashToInt(minimalBytes(G))
            val hi = hashToInt(USERNAME.toByteArray(Charsets.UTF_8))
            proof = Digest.sha512(
                minimalBytes(hn.xor(hg)),
                minimalBytes(hi),
                salt,
                minimalBytes(publicKey),
                minimalBytes(b),
                sessionKey,
            )

            expectedServerProof =
                Digest.sha512(minimalBytes(publicKey), proof, sessionKey)
        }

        /** A, serialised the way the device expects it in TLV 0x03. */
        fun publicKeyBytes(): ByteArray = minimalBytes(publicKey)
    }
}
