package com.gios.lightremote.proto

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * OPACK, Apple's binary serialisation format, as spoken by the Companion protocol.
 *
 * Type tags, in the order the decoder tests them:
 *
 * ```
 * 01/02        true / false
 * 04           null
 * 05 + 16      UUID
 * 06 + 8       absolute time (decoded as a raw little-endian integer)
 * 08..2F       small integer, value = tag - 8  (0..39)
 * 30..33       integer in 1/2/4/8 little-endian bytes
 * 35/36        float32 / float64
 * 40..60       string, length = tag - 0x40 (up to 32 UTF-8 bytes)
 * 61..64       string with a 1/2/3/4-byte little-endian length
 * 70..90       byte string, length = tag - 0x70
 * 91..94       byte string with a 1/2/4/8-byte little-endian length
 * A0..C0       back-reference to object 0..32
 * C1..C4       back-reference with a 1/2/4/8-byte index
 * D0..DF       array, count = tag - 0xD0; 0xDF means "read until 0x03"
 * E0..EF       dictionary, count = tag - 0xE0; 0xEF means "read until 0x03"
 * ```
 *
 * The back-references are the subtle part. Every encoded object longer than one byte is
 * appended to a running table, and a later occurrence of the *same encoded bytes* is
 * replaced by its index. This is not optional politeness — real Companion messages use it
 * (`_mcc` appears as both a key and a value in one frame and the second one arrives as a
 * single 0xA1 byte), so a decoder without the table simply cannot read the device.
 */
object Opack {

    class FormatException(message: String) : Exception(message)

    // ------------------------------------------------------------------ encoding

    fun pack(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        pack(value, out, mutableListOf())
        return out.toByteArray()
    }

    private fun pack(value: Any?, sink: ByteArrayOutputStream, table: MutableList<ByteArray>) {
        val encoded = encode(value, table)

        // Reuse an identical earlier object if we have already emitted one.
        val existing = table.indexOfFirst { it.contentEquals(encoded) }
        if (existing >= 0) {
            sink.write(backReference(existing))
            return
        }
        if (encoded.size > 1) table.add(encoded)
        sink.write(encoded)
    }

    private fun backReference(index: Int): ByteArray = when {
        index < 0x21 -> byteArrayOf((0xA0 + index).toByte())
        index <= 0xFF -> byteArrayOf(0xC1.toByte()) + leBytes(index.toLong(), 1)
        index <= 0xFFFF -> byteArrayOf(0xC2.toByte()) + leBytes(index.toLong(), 2)
        else -> byteArrayOf(0xC3.toByte()) + leBytes(index.toLong(), 4)
    }

    private fun leBytes(value: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) out[i] = ((value ushr (8 * i)) and 0xFF).toByte()
        return out
    }

    private fun encode(value: Any?, table: MutableList<ByteArray>): ByteArray = when (value) {
        null -> byteArrayOf(0x04)
        is Boolean -> byteArrayOf(if (value) 0x01 else 0x02)
        is UUID -> byteArrayOf(0x05) + uuidBytes(value)
        is Byte, is Short, is Int, is Long -> encodeInt((value as Number).toLong())
        is Float -> byteArrayOf(0x35) + leBytes(
            java.lang.Float.floatToIntBits(value).toLong() and 0xFFFFFFFFL, 4,
        )
        is Double -> byteArrayOf(0x36) + leBytes(java.lang.Double.doubleToLongBits(value), 8)
        is String -> encodeString(value)
        is ByteArray -> encodeBytes(value)
        is List<*> -> encodeCollection(0xD0, value.size) { sink ->
            value.forEach { pack(it, sink, table) }
        }
        is Map<*, *> -> encodeCollection(0xE0, value.size) { sink ->
            value.forEach { (k, v) -> pack(k, sink, table); pack(v, sink, table) }
        }
        else -> throw FormatException("cannot OPACK-encode ${value.javaClass.name}")
    }

    private fun encodeInt(value: Long): ByteArray = when {
        // The wire format has no negative integers at all. Callers that need one (the
        // skip-by-seconds argument, say) must send a Double instead.
        value < 0 -> throw FormatException(
            "OPACK has no negative integers; pass $value as a Double",
        )
        value < 0x28 -> byteArrayOf((value + 8).toByte())
        value <= 0xFF -> byteArrayOf(0x30) + leBytes(value, 1)
        value <= 0xFFFF -> byteArrayOf(0x31) + leBytes(value, 2)
        value <= 0xFFFFFFFFL -> byteArrayOf(0x32) + leBytes(value, 4)
        else -> byteArrayOf(0x33) + leBytes(value, 8)
    }

    private fun encodeString(value: String): ByteArray {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        return when {
            utf8.size <= 0x20 -> byteArrayOf((0x40 + utf8.size).toByte()) + utf8
            utf8.size <= 0xFF -> byteArrayOf(0x61) + leBytes(utf8.size.toLong(), 1) + utf8
            utf8.size <= 0xFFFF -> byteArrayOf(0x62) + leBytes(utf8.size.toLong(), 2) + utf8
            utf8.size <= 0xFFFFFF -> byteArrayOf(0x63) + leBytes(utf8.size.toLong(), 3) + utf8
            else -> byteArrayOf(0x64) + leBytes(utf8.size.toLong(), 4) + utf8
        }
    }

    private fun encodeBytes(value: ByteArray): ByteArray = when {
        value.size <= 0x20 -> byteArrayOf((0x70 + value.size).toByte()) + value
        value.size <= 0xFF -> byteArrayOf(0x91.toByte()) + leBytes(value.size.toLong(), 1) + value
        value.size <= 0xFFFF -> byteArrayOf(0x92.toByte()) + leBytes(value.size.toLong(), 2) + value
        else -> byteArrayOf(0x93.toByte()) + leBytes(value.size.toLong(), 4) + value
    }

    private inline fun encodeCollection(
        baseTag: Int,
        count: Int,
        writeItems: (ByteArrayOutputStream) -> Unit,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(baseTag + minOf(count, 0xF))
        writeItems(body)
        // A count of 15 or more switches to the terminated form, so 15 exactly gets both
        // the 0xF nibble and the terminator.
        if (count >= 0xF) body.write(0x03)
        return body.toByteArray()
    }

    private fun uuidBytes(uuid: UUID): ByteArray {
        val out = ByteArray(16)
        var hi = uuid.mostSignificantBits
        var lo = uuid.leastSignificantBits
        for (i in 7 downTo 0) { out[i] = (hi and 0xFF).toByte(); hi = hi ushr 8 }
        for (i in 15 downTo 8) { out[i] = (lo and 0xFF).toByte(); lo = lo ushr 8 }
        return out
    }

    // ------------------------------------------------------------------ decoding

    fun unpack(data: ByteArray): Any? = Reader(data).read(mutableListOf())

    /** Convenience for frames, which are always a dictionary at the top level. */
    @Suppress("UNCHECKED_CAST")
    fun unpackMap(data: ByteArray): Map<String, Any?> {
        val value = unpack(data)
        if (value !is Map<*, *>) {
            throw FormatException("expected an OPACK dictionary, got ${value?.javaClass?.name}")
        }
        return value as Map<String, Any?>
    }

    private class Reader(private val data: ByteArray) {
        var pos = 0

        fun byte(): Int {
            if (pos >= data.size) throw FormatException("truncated OPACK data")
            return data[pos++].toInt() and 0xFF
        }

        fun peek(): Int {
            if (pos >= data.size) throw FormatException("truncated OPACK data")
            return data[pos].toInt() and 0xFF
        }

        fun slice(length: Int): ByteArray {
            if (length < 0 || pos + length > data.size) throw FormatException("truncated OPACK data")
            return data.copyOfRange(pos, pos + length).also { pos += length }
        }

        fun leLong(size: Int): Long {
            val raw = slice(size)
            var v = 0L
            for (i in raw.indices.reversed()) v = (v shl 8) or (raw[i].toLong() and 0xFF)
            return v
        }

        fun read(table: MutableList<Any?>): Any? {
            val tag = byte()
            // Back-references resolve against the table and are never re-added to it.
            if (tag in 0xA0..0xC0) return tableGet(table, tag - 0xA0)
            if (tag in 0xC1..0xC4) {
                val width = tag - 0xC0
                return tableGet(table, leLong(if (width == 3) 4 else width).toInt())
            }

            var addToTable = true
            val value: Any? = when {
                tag == 0x01 -> { addToTable = false; true }
                tag == 0x02 -> { addToTable = false; false }
                tag == 0x04 -> { addToTable = false; null }
                tag == 0x05 -> readUuid()
                tag == 0x06 -> leLong(8)
                tag in 0x08..0x2F -> { addToTable = false; (tag - 8).toLong() }
                tag == 0x35 -> java.lang.Float.intBitsToFloat(leLong(4).toInt()).toDouble()
                tag == 0x36 -> java.lang.Double.longBitsToDouble(leLong(8))
                (tag and 0xF0) == 0x30 -> leLong(1 shl (tag and 0xF))
                tag in 0x40..0x60 -> {
                    val length = tag - 0x40
                    // A one-byte encoding was never added to the encoder's table, so an
                    // empty string must not be added here either or the two sides drift.
                    if (length == 0) addToTable = false
                    String(slice(length), Charsets.UTF_8)
                }
                tag in 0x61..0x64 -> String(slice(leLong(tag and 0xF).toInt()), Charsets.UTF_8)
                tag in 0x70..0x90 -> {
                    val length = tag - 0x70
                    if (length == 0) addToTable = false
                    slice(length)
                }
                tag in 0x91..0x94 -> slice(leLong(1 shl ((tag and 0xF) - 1)).toInt())
                (tag and 0xF0) == 0xD0 -> { addToTable = false; readList(tag and 0xF, table) }
                (tag and 0xE0) == 0xE0 -> { addToTable = false; readMap(tag and 0xF, table) }
                else -> throw FormatException("unknown OPACK tag 0x${tag.toString(16)}")
            }

            if (addToTable) table.add(value)
            return value
        }

        private fun tableGet(table: List<Any?>, index: Int): Any? {
            if (index !in table.indices) {
                throw FormatException("OPACK back-reference $index outside table of ${table.size}")
            }
            return table[index]
        }

        private fun readUuid(): UUID {
            val raw = slice(16)
            var hi = 0L
            var lo = 0L
            for (i in 0 until 8) hi = (hi shl 8) or (raw[i].toLong() and 0xFF)
            for (i in 8 until 16) lo = (lo shl 8) or (raw[i].toLong() and 0xFF)
            return UUID(hi, lo)
        }

        private fun readList(count: Int, table: MutableList<Any?>): List<Any?> {
            val out = mutableListOf<Any?>()
            if (count == 0xF) {
                while (peek() != 0x03) out.add(read(table))
                pos++
            } else {
                repeat(count) { out.add(read(table)) }
            }
            return out
        }

        private fun readMap(count: Int, table: MutableList<Any?>): Map<Any?, Any?> {
            val out = LinkedHashMap<Any?, Any?>()
            if (count == 0xF) {
                while (peek() != 0x03) {
                    val key = read(table)
                    out[key] = read(table)
                }
                pos++
            } else {
                repeat(count) {
                    val key = read(table)
                    out[key] = read(table)
                }
            }
            return out
        }
    }
}
