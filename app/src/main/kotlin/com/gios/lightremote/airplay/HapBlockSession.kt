package com.gios.lightremote.airplay

import com.gios.lightremote.crypto.ChaChaCipherPair
import java.io.ByteArrayOutputStream

/**
 * HAP's block-framed stream encryption, as used by every AirPlay channel (control, event and
 * data). It is the same ChaCha20-Poly1305 the Companion session uses — with the same counter
 * nonce — but framed differently: HAP encrypts in blocks of at most 1024 bytes, each block
 * prefixed by its own two-byte little-endian length, and that length doubles as the block's
 * associated data.
 *
 * The nonce layout is the subtle part, and it is why this reuses [ChaChaCipherPair] with an
 * eight-byte nonce rather than the twelve the Companion stream uses: HAP's counter goes in the
 * low eight bytes with four zero bytes in front, which is exactly what the eight-byte
 * constructor produces after padding. The counter advances per block, per direction — get the
 * width wrong and the first block decrypts and every one after it fails.
 *
 * Mirrors pyatv's `HAPSession` (auth/hap_session.py) block for block, so a stream this produces
 * is one a real AirPlay receiver reads and vice versa.
 */
class HapBlockSession(outputKey: ByteArray, inputKey: ByteArray) {

    companion object {
        private const val FRAME_LENGTH = 1024
        private const val AUTH_TAG_LENGTH = 16
        private const val LENGTH_PREFIX = 2
    }

    private val cipher = ChaChaCipherPair(outputKey, inputKey, nonceLength = 8)

    /** Ciphertext read from the socket that has not yet formed a whole block. */
    private val inbound = ByteArrayOutputStream()

    /** Split [data] into <=1024-byte blocks, each length-prefixed and sealed. */
    fun encrypt(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + FRAME_LENGTH, data.size)
            val frame = data.copyOfRange(offset, end)
            val length = shortLe(frame.size)
            val sealed = cipher.encrypt(frame, aad = length)
            out.write(length)
            out.write(sealed)
            offset = end
        }
        return out.toByteArray()
    }

    /**
     * Feed newly-read ciphertext and get back whatever plaintext is now complete.
     *
     * A block may be split across socket reads, so anything short of a whole block is buffered
     * and returned on a later call — the caller keeps feeding until it has parsed a full message.
     */
    fun decrypt(chunk: ByteArray): ByteArray {
        inbound.write(chunk)
        var buffer = inbound.toByteArray()
        val out = ByteArrayOutputStream()
        var consumed = 0
        while (buffer.size - consumed >= LENGTH_PREFIX) {
            val length = (buffer[consumed].toInt() and 0xFF) or ((buffer[consumed + 1].toInt() and 0xFF) shl 8)
            val blockLength = length + AUTH_TAG_LENGTH
            if (buffer.size - consumed - LENGTH_PREFIX < blockLength) break
            val start = consumed + LENGTH_PREFIX
            val block = buffer.copyOfRange(start, start + blockLength)
            val aad = byteArrayOf(buffer[consumed], buffer[consumed + 1])
            out.write(cipher.decrypt(block, aad = aad))
            consumed = start + blockLength
        }
        // Keep the unconsumed tail for next time.
        inbound.reset()
        if (consumed < buffer.size) inbound.write(buffer, consumed, buffer.size - consumed)
        return out.toByteArray()
    }

    private fun shortLe(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte())
}
