package com.gios.lightremote.crypto

import java.math.BigInteger

/**
 * ChaCha20-Poly1305 AEAD (RFC 8439).
 *
 * `Cipher.getInstance("ChaCha20-Poly1305")` exists from API 28, but implementing it here
 * keeps the whole Companion stack testable off-device with nothing but `kotlinc`, and
 * sidesteps the provider differences between Conscrypt and OpenJDK.
 */
object ChaCha20Poly1305 {

    const val TAG_LENGTH = 16
    const val NONCE_LENGTH = 12

    class AuthenticationFailure : Exception("ChaCha20-Poly1305 authentication tag mismatch")

    // ------------------------------------------------------------------ ChaCha20

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }

    private fun leInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun initialState(key: ByteArray, nonce: ByteArray, counter: Int): IntArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == NONCE_LENGTH) { "ChaCha20 nonce must be 12 bytes" }
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = leInt(key, i * 4)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = leInt(nonce, i * 4)
        return s
    }

    private fun block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val state = initialState(key, nonce, counter)
        val working = state.copyOf()
        repeat(10) {
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val v = working[i] + state[i]
            out[i * 4] = (v and 0xFF).toByte()
            out[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun chacha20Xor(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var offset = 0
        var counter = 1 // Counter 0 is reserved for the Poly1305 one-time key.
        while (offset < data.size) {
            val stream = block(key, nonce, counter)
            val n = minOf(64, data.size - offset)
            for (i in 0 until n) out[offset + i] = (data[offset + i].toInt() xor stream[i].toInt()).toByte()
            offset += n
            counter++
        }
        return out
    }

    // ------------------------------------------------------------------ Poly1305

    private val P130_5: BigInteger = BigInteger.TWO.pow(130).subtract(BigInteger.valueOf(5))
    private val TWO_128: BigInteger = BigInteger.TWO.pow(128)
    private val R_CLAMP = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)

    private fun leToBigInteger(data: ByteArray, from: Int, to: Int, extraHighByte: Boolean): BigInteger {
        var v = BigInteger.ZERO
        for (i in (to - 1) downTo from) {
            v = v.shiftLeft(8).or(BigInteger.valueOf((data[i].toInt() and 0xFF).toLong()))
        }
        // The 0x01 byte appended past the end of each block, per RFC 8439 section 2.5.
        return if (extraHighByte) v.setBit((to - from) * 8) else v
    }

    private fun poly1305(oneTimeKey: ByteArray, message: ByteArray): ByteArray {
        val r = leToBigInteger(oneTimeKey, 0, 16, false).and(R_CLAMP)
        val s = leToBigInteger(oneTimeKey, 16, 32, false)
        var acc = BigInteger.ZERO
        var offset = 0
        while (offset < message.size) {
            val end = minOf(offset + 16, message.size)
            acc = acc.add(leToBigInteger(message, offset, end, true)).multiply(r).mod(P130_5)
            offset = end
        }
        return Curve25519.toLeBytes(acc.add(s).mod(TWO_128), 16)
    }

    private fun pad16(length: Int): ByteArray =
        if (length % 16 == 0) ByteArray(0) else ByteArray(16 - (length % 16))

    private fun le64(value: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = ((value ushr (8 * i)) and 0xFF).toByte()
        return out
    }

    private fun macData(aad: ByteArray, ciphertext: ByteArray): ByteArray =
        aad + pad16(aad.size) + ciphertext + pad16(ciphertext.size) +
            le64(aad.size.toLong()) + le64(ciphertext.size.toLong())

    // ------------------------------------------------------------------ AEAD

    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val ciphertext = chacha20Xor(key, nonce, plaintext)
        val tag = poly1305(block(key, nonce, 0).copyOfRange(0, 32), macData(aad, ciphertext))
        return ciphertext + tag
    }

    /** @throws AuthenticationFailure if the tag does not verify. */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        if (ciphertextWithTag.size < TAG_LENGTH) throw AuthenticationFailure()
        val split = ciphertextWithTag.size - TAG_LENGTH
        val ciphertext = ciphertextWithTag.copyOfRange(0, split)
        val tag = ciphertextWithTag.copyOfRange(split, ciphertextWithTag.size)
        val expected = poly1305(block(key, nonce, 0).copyOfRange(0, 32), macData(aad, ciphertext))
        if (!Digest.constantTimeEquals(tag, expected)) throw AuthenticationFailure()
        return chacha20Xor(key, nonce, ciphertext)
    }
}

/**
 * The two counter-based cipher pairs HAP uses.
 *
 * Each direction keeps its own counter, and the counter is *not* advanced when an
 * explicit nonce is supplied — the pairing messages ("PS-Msg05", "PV-Msg02" and friends)
 * use string nonces while the session stream uses the counters.
 *
 * @param nonceLength 8 for pairing (padded on the left to 12), 12 for the Companion
 *   session stream. That difference is not cosmetic: pairing pads at the front and the
 *   session counter fills all 12 bytes, so getting it wrong produces a valid-looking
 *   frame the device silently drops.
 */
class ChaChaCipherPair(
    private val outKey: ByteArray,
    private val inKey: ByteArray,
    private val nonceLength: Int = 8,
) {
    private var outCounter = 0L
    private var inCounter = 0L

    private fun padNonce(nonce: ByteArray): ByteArray =
        if (nonce.size >= ChaCha20Poly1305.NONCE_LENGTH) nonce
        else ByteArray(ChaCha20Poly1305.NONCE_LENGTH - nonce.size) + nonce

    private fun counterNonce(counter: Long): ByteArray {
        val raw = ByteArray(nonceLength)
        // Only the first eight bytes can carry a Long, and the loop must stop there. The JVM
        // masks Long shift distances to six bits, so `counter ushr 64` is `counter ushr 0`,
        // not zero — writing byte 8 from the loop stamped a copy of the counter's low byte
        // into the middle of the nonce. Counter 0 is all zeros either way, which is why the
        // first encrypted frame of a session worked and every one after it was silently
        // dropped by the device.
        val carried = minOf(nonceLength, 8)
        for (i in 0 until carried) raw[i] = ((counter ushr (8 * i)) and 0xFF).toByte()
        return padNonce(raw)
    }

    fun encrypt(data: ByteArray, nonce: ByteArray? = null, aad: ByteArray = ByteArray(0)): ByteArray {
        val n = if (nonce == null) counterNonce(outCounter++) else padNonce(nonce)
        return ChaCha20Poly1305.encrypt(outKey, n, data, aad)
    }

    fun decrypt(data: ByteArray, nonce: ByteArray? = null, aad: ByteArray = ByteArray(0)): ByteArray {
        val n = if (nonce == null) counterNonce(inCounter++) else padNonce(nonce)
        return ChaCha20Poly1305.decrypt(inKey, n, data, aad)
    }
}
