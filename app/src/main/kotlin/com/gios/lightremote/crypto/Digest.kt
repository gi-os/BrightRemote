package com.gios.lightremote.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SHA-512, HMAC and HKDF.
 *
 * These come from the platform rather than a bundled provider: SHA-512 and HmacSHA512
 * ship in every Android and JVM release, so the same code runs on the phone and under
 * plain `kotlinc` in the tests.
 */
object Digest {

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        // SecretKeySpec rejects a zero-length key, but RFC 5869 explicitly allows an
        // empty HKDF salt and defines it as HashLen zero bytes. The Companion session
        // keys are derived with exactly that empty salt, so this branch is load-bearing.
        val k = if (key.isEmpty()) ByteArray(64) else key
        mac.init(SecretKeySpec(k, "HmacSHA512"))
        return mac.doFinal(data)
    }

    /**
     * HKDF-SHA512 (RFC 5869). HAP only ever asks for 32 bytes, which fits inside the
     * first expansion block, but the loop is general so it cannot silently truncate.
     */
    fun hkdfSha512(salt: ByteArray, info: ByteArray, ikm: ByteArray, length: Int = 32): ByteArray {
        require(length > 0 && length <= 255 * 64) { "invalid HKDF length $length" }
        val prk = hmacSha512(salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val block = hmacSha512(prk, previous + info + byteArrayOf(counter.toByte()))
            val take = minOf(block.size, length - offset)
            block.copyInto(out, offset, 0, take)
            offset += take
            previous = block
            counter++
        }
        return out
    }

    fun hkdfSha512(salt: String, info: String, ikm: ByteArray, length: Int = 32): ByteArray =
        hkdfSha512(salt.toByteArray(Charsets.UTF_8), info.toByteArray(Charsets.UTF_8), ikm, length)

    /** Constant-time comparison, for proofs and auth tags. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
