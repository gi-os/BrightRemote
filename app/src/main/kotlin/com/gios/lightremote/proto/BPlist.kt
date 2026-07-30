package com.gios.lightremote.proto

import java.io.ByteArrayOutputStream

/** A `$objects` index inside an NSKeyedArchiver graph — CoreFoundation's UID type. */
data class Uid(val value: Int)

/**
 * Binary property list reader and writer, enough of the format for the Companion
 * protocol's text-input channel.
 *
 * Text input is the one Companion feature that does not use OPACK: the payload is an
 * NSKeyedArchiver graph serialised as a binary plist. Rather than approximate it, this
 * writer reproduces CPython `plistlib`'s output exactly — same object-table ordering,
 * same de-duplication, same integer widths — because that output is known to be accepted
 * by tvOS, and byte-equality against it is something the tests can actually check.
 */
object BPlist {

    class FormatException(message: String) : Exception(message)

    private val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)

    // ------------------------------------------------------------------ writing

    fun write(root: Any?): ByteArray {
        val objects = mutableListOf<Any?>()
        // Scalars de-duplicate by (type, value); containers and UIDs by identity, which is
        // what plistlib does and why two equal UIDs still take two table slots.
        val scalarIndex = HashMap<Pair<String, Any?>, Int>()
        val identityIndex = HashMap<IdentityKey, Int>()

        flatten(root, objects, scalarIndex, identityIndex)

        val refSize = countToSize(objects.size)
        val offsets = IntArray(objects.size)
        val body = ByteArrayOutputStream()
        body.write(MAGIC)

        for ((index, value) in objects.withIndex()) {
            offsets[index] = body.size()
            writeObject(value, body, refSize) { target ->
                refNumber(target, scalarIndex, identityIndex)
            }
        }

        val offsetTableOffset = body.size()
        val offsetSize = countToSize(offsetTableOffset)
        for (offset in offsets) body.write(beBytes(offset.toLong(), offsetSize))

        // Trailer: 5 pad bytes, sort version, offset size, ref size, then three 64-bit
        // fields — object count, top object index, offset table position.
        body.write(ByteArray(5))
        body.write(0)
        body.write(offsetSize)
        body.write(refSize)
        body.write(beBytes(objects.size.toLong(), 8))
        body.write(beBytes(0, 8))
        body.write(beBytes(offsetTableOffset.toLong(), 8))
        return body.toByteArray()
    }

    private class IdentityKey(val target: Any?) {
        override fun hashCode(): Int = System.identityHashCode(target)
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.target === target
    }

    private fun scalarKey(value: Any?): Pair<String, Any?>? = when (value) {
        is String -> "s" to value
        is Int -> "i" to value.toLong()
        is Long -> "i" to value
        is Double -> "d" to value
        is Boolean -> "b" to value
        is ByteArray -> "y" to value.toList()
        else -> null
    }

    private fun flatten(
        value: Any?,
        objects: MutableList<Any?>,
        scalarIndex: MutableMap<Pair<String, Any?>, Int>,
        identityIndex: MutableMap<IdentityKey, Int>,
    ) {
        val key = scalarKey(value)
        if (key != null) {
            if (scalarIndex.containsKey(key)) return
        } else if (identityIndex.containsKey(IdentityKey(value))) {
            return
        }

        val refnum = objects.size
        objects.add(value)
        if (key != null) scalarIndex[key] = refnum else identityIndex[IdentityKey(value)] = refnum

        when (value) {
            is Map<*, *> -> {
                // Insertion order, matching plistlib's sort_keys=False. The RTI payloads
                // depend on it: their dictionaries are written in a specific order and
                // sorting them would move every object reference.
                val keys = value.keys.toList()
                val values = value.values.toList()
                for (k in keys) {
                    if (k !is String) throw FormatException("plist dictionary keys must be strings")
                    flatten(k, objects, scalarIndex, identityIndex)
                }
                for (v in values) flatten(v, objects, scalarIndex, identityIndex)
            }
            is List<*> -> value.forEach { flatten(it, objects, scalarIndex, identityIndex) }
        }
    }

    private fun refNumber(
        value: Any?,
        scalarIndex: Map<Pair<String, Any?>, Int>,
        identityIndex: Map<IdentityKey, Int>,
    ): Int {
        val key = scalarKey(value)
        val ref = if (key != null) scalarIndex[key] else identityIndex[IdentityKey(value)]
        return ref ?: throw FormatException("object missing from plist table: $value")
    }

    private fun countToSize(count: Int): Int = when {
        count < 1 shl 8 -> 1
        count < 1 shl 16 -> 2
        else -> 4
    }

    private fun beBytes(value: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) out[size - 1 - i] = ((value ushr (8 * i)) and 0xFF).toByte()
        return out
    }

    private fun writeSize(token: Int, size: Int, sink: ByteArrayOutputStream) {
        if (size < 15) {
            sink.write(token or size)
            return
        }
        sink.write(token or 0xF)
        when {
            size < 1 shl 8 -> { sink.write(0x10); sink.write(beBytes(size.toLong(), 1)) }
            size < 1 shl 16 -> { sink.write(0x11); sink.write(beBytes(size.toLong(), 2)) }
            else -> { sink.write(0x12); sink.write(beBytes(size.toLong(), 4)) }
        }
    }

    private fun writeObject(
        value: Any?,
        sink: ByteArrayOutputStream,
        refSize: Int,
        refOf: (Any?) -> Int,
    ) {
        when (value) {
            null -> sink.write(0x00)
            is Boolean -> sink.write(if (value) 0x09 else 0x08)
            is Int, is Long -> writeInt((value as Number).toLong(), sink)
            is Double -> { sink.write(0x23); sink.write(beBytes(java.lang.Double.doubleToLongBits(value), 8)) }
            is Uid -> when {
                value.value < 1 shl 8 -> { sink.write(0x80); sink.write(beBytes(value.value.toLong(), 1)) }
                value.value < 1 shl 16 -> { sink.write(0x81); sink.write(beBytes(value.value.toLong(), 2)) }
                // 0x82 is skipped by CoreFoundation; four-byte UIDs use 0x83.
                else -> { sink.write(0x83); sink.write(beBytes(value.value.toLong(), 4)) }
            }
            is ByteArray -> { writeSize(0x40, value.size, sink); sink.write(value) }
            is String -> writeString(value, sink)
            is List<*> -> {
                writeSize(0xA0, value.size, sink)
                value.forEach { sink.write(beBytes(refOf(it).toLong(), refSize)) }
            }
            is Map<*, *> -> {
                writeSize(0xD0, value.size, sink)
                value.keys.forEach { sink.write(beBytes(refOf(it).toLong(), refSize)) }
                value.values.forEach { sink.write(beBytes(refOf(it).toLong(), refSize)) }
            }
            else -> throw FormatException("cannot write ${value.javaClass.name} to a plist")
        }
    }

    private fun writeInt(value: Long, sink: ByteArrayOutputStream) {
        when {
            value < 0 -> { sink.write(0x13); sink.write(beBytes(value, 8)) }
            value < 1L shl 8 -> { sink.write(0x10); sink.write(beBytes(value, 1)) }
            value < 1L shl 16 -> { sink.write(0x11); sink.write(beBytes(value, 2)) }
            value < 1L shl 32 -> { sink.write(0x12); sink.write(beBytes(value, 4)) }
            else -> { sink.write(0x13); sink.write(beBytes(value, 8)) }
        }
    }

    private fun writeString(value: String, sink: ByteArrayOutputStream) {
        val ascii = value.all { it.code < 0x80 }
        if (ascii) {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            writeSize(0x50, bytes.size, sink)
            sink.write(bytes)
        } else {
            val bytes = value.toByteArray(Charsets.UTF_16BE)
            // The size is in UTF-16 code units, not bytes.
            writeSize(0x60, bytes.size / 2, sink)
            sink.write(bytes)
        }
    }

    // ------------------------------------------------------------------ reading

    fun read(data: ByteArray): Any? {
        if (data.size < MAGIC.size + 32) throw FormatException("plist too short")
        if (!data.copyOfRange(0, 8).contentEquals(MAGIC)) throw FormatException("not a binary plist")

        val trailer = data.size - 32
        val offsetSize = data[trailer + 6].toInt() and 0xFF
        val refSize = data[trailer + 7].toInt() and 0xFF
        val count = readBe(data, trailer + 8, 8).toInt()
        val topIndex = readBe(data, trailer + 16, 8).toInt()
        val offsetTable = readBe(data, trailer + 24, 8).toInt()

        if (count < 0 || offsetTable + count * offsetSize > data.size) {
            throw FormatException("plist offset table out of range")
        }
        val offsets = IntArray(count) { readBe(data, offsetTable + it * offsetSize, offsetSize).toInt() }
        return Parser(data, offsets, refSize).obj(topIndex, 0)
    }

    private class Parser(
        private val data: ByteArray,
        private val offsets: IntArray,
        private val refSize: Int,
    ) {
        fun obj(index: Int, depth: Int): Any? {
            if (depth > 64) throw FormatException("plist nested too deeply")
            if (index !in offsets.indices) throw FormatException("plist object $index out of range")
            var pos = offsets[index]
            val marker = data[pos].toInt() and 0xFF
            pos++
            val high = marker and 0xF0
            val low = marker and 0x0F

            return when {
                marker == 0x00 -> null
                marker == 0x08 -> false
                marker == 0x09 -> true
                high == 0x10 -> readBe(data, pos, 1 shl low)
                high == 0x20 -> when (low) {
                    2 -> java.lang.Float.intBitsToFloat(readBe(data, pos, 4).toInt()).toDouble()
                    3 -> java.lang.Double.longBitsToDouble(readBe(data, pos, 8))
                    else -> throw FormatException("unsupported plist real width $low")
                }
                high == 0x40 -> {
                    val (length, next) = sizeAt(low, pos)
                    data.copyOfRange(next, next + length)
                }
                high == 0x50 -> {
                    val (length, next) = sizeAt(low, pos)
                    String(data, next, length, Charsets.US_ASCII)
                }
                high == 0x60 -> {
                    val (length, next) = sizeAt(low, pos)
                    String(data, next, length * 2, Charsets.UTF_16BE)
                }
                high == 0x80 -> Uid(readBe(data, pos, low + 1).toInt())
                high == 0xA0 -> {
                    val (length, next) = sizeAt(low, pos)
                    (0 until length).map { obj(readBe(data, next + it * refSize, refSize).toInt(), depth + 1) }
                }
                high == 0xD0 -> {
                    val (length, next) = sizeAt(low, pos)
                    val out = LinkedHashMap<Any?, Any?>()
                    for (i in 0 until length) {
                        val key = obj(readBe(data, next + i * refSize, refSize).toInt(), depth + 1)
                        val valueRef = readBe(data, next + (length + i) * refSize, refSize).toInt()
                        out[key] = obj(valueRef, depth + 1)
                    }
                    out
                }
                else -> throw FormatException("unsupported plist marker 0x${marker.toString(16)}")
            }
        }

        /** A low nibble of 0xF means the real length follows as an integer object. */
        private fun sizeAt(low: Int, pos: Int): Pair<Int, Int> {
            if (low != 0xF) return low to pos
            val marker = data[pos].toInt() and 0xFF
            if (marker and 0xF0 != 0x10) throw FormatException("bad plist extended length")
            val width = 1 shl (marker and 0x0F)
            return readBe(data, pos + 1, width).toInt() to (pos + 1 + width)
        }
    }

    private fun readBe(data: ByteArray, offset: Int, size: Int): Long {
        if (offset < 0 || offset + size > data.size) throw FormatException("plist read out of range")
        var value = 0L
        for (i in 0 until size) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value
    }
}
