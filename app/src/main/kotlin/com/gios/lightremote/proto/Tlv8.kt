package com.gios.lightremote.proto

import java.io.ByteArrayOutputStream

/**
 * TLV8, the tag/length/value encoding HAP pairing rides on.
 *
 * One quirk worth knowing: a value longer than 255 bytes is split into several entries
 * sharing the same tag, and the reader is expected to concatenate them. The SRP public
 * key is 384 bytes, so this happens on every single pairing attempt.
 */
object Tlv8 {

    // Standard HAP tags.
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val SEQ_NO = 0x06
    const val ERROR = 0x07
    const val BACK_OFF = 0x08
    const val CERTIFICATE = 0x09
    const val SIGNATURE = 0x0A
    const val PERMISSIONS = 0x0B
    const val FRAGMENT_DATA = 0x0C
    const val FRAGMENT_LAST = 0x0D

    // Apple additions.
    const val NAME = 0x11
    const val FLAGS = 0x13

    fun write(entries: Map<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in entries) {
            if (value.isEmpty()) {
                out.write(tag)
                out.write(0)
                continue
            }
            var offset = 0
            while (offset < value.size) {
                val chunk = minOf(255, value.size - offset)
                out.write(tag)
                out.write(chunk)
                out.write(value, offset, chunk)
                offset += chunk
            }
        }
        return out.toByteArray()
    }

    fun read(data: ByteArray): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        var pos = 0
        while (pos + 1 < data.size) {
            val tag = data[pos].toInt() and 0xFF
            val length = data[pos + 1].toInt() and 0xFF
            if (pos + 2 + length > data.size) break
            val value = data.copyOfRange(pos + 2, pos + 2 + length)
            out[tag] = out[tag]?.plus(value) ?: value
            pos += 2 + length
        }
        return out
    }

    /** Human-readable error for the failure paths, so a bad PIN says so. */
    fun describeError(code: Int): String = when (code) {
        0x01 -> "unknown error"
        0x02 -> "authentication failed — wrong PIN"
        0x03 -> "device is backing off, wait before retrying"
        0x04 -> "device has reached its maximum number of paired controllers"
        0x05 -> "too many failed attempts, restart the Apple TV to reset"
        0x06 -> "pairing is unavailable"
        0x07 -> "device is busy"
        else -> "error code 0x${code.toString(16)}"
    }

    fun errorMessage(tlv: Map<Int, ByteArray>): String? {
        val raw = tlv[ERROR] ?: return null
        var code = 0
        for (i in raw.indices.reversed()) code = (code shl 8) or (raw[i].toInt() and 0xFF)
        return describeError(code)
    }
}
