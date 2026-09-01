package com.gios.lightremote.proto

import java.io.ByteArrayOutputStream

/**
 * A hand-written, tag-based protobuf reader and writer — varints and length-delimited
 * fields, plus the two fixed widths tvOS actually uses for now-playing (fixed64 for
 * doubles, fixed32 for floats).
 *
 * The point of this file is what is *not* in it. MRP's schema is 77 `.proto` files; porting
 * them, or pulling in protobuf-lite, buys a generated class hierarchy for a message set this
 * app writes three of and reads two of. Instead the wire format is decoded generically into
 * [ProtoMessage] — field number to a list of [ProtoField] — and the handful of messages that
 * matter are walked by field number, straight from pyatv's `.proto` definitions. Everything
 * else on the wire is ignored, which is exactly what a minimal remote wants: the schema can
 * grow a dozen fields between tvOS releases and this keeps reading the six it needs.
 *
 * proto2 semantics, but this never has to know that — an absent field is simply a number the
 * decoder never saw, and there are no defaults to synthesise because every reader here treats
 * "missing" as null.
 *
 * Pure Kotlin, no Android imports, so the whole codec is checked on the JVM against vectors
 * that `scripts/genvec.py` generates with Google's own protobuf library (see MrpProtoTest).
 */
object ProtoBuf {

    class FormatException(message: String) : Exception(message)

    /** Protobuf wire types. Only these four are ever emitted or understood. */
    object WireType {
        const val VARINT = 0
        const val FIXED64 = 1
        const val LENGTH_DELIMITED = 2
        const val FIXED32 = 5
    }

    /** One decoded field value, tagged by the wire type it arrived as. */
    sealed class ProtoField {
        data class Varint(val value: Long) : ProtoField()
        data class Fixed64(val bits: Long) : ProtoField()
        data class Fixed32(val bits: Int) : ProtoField()
        class LengthDelimited(val bytes: ByteArray) : ProtoField() {
            override fun equals(other: Any?) =
                other is LengthDelimited && bytes.contentEquals(other.bytes)
            override fun hashCode() = bytes.contentHashCode()
        }
    }

    /**
     * A decoded message: field number to every value seen for it, in wire order.
     *
     * A list because a field can legally repeat — `contentItems` does, and proto2 also allows
     * a scalar to appear more than once (last one wins, which the accessors implement). A field
     * read with the wrong accessor returns null rather than throwing, because a foreign tvOS
     * build putting an unexpected type on a field number must never crash the parse.
     */
    class ProtoMessage(val fields: Map<Int, List<ProtoField>>) {

        fun has(field: Int): Boolean = fields.containsKey(field)

        private fun last(field: Int): ProtoField? = fields[field]?.lastOrNull()

        fun varint(field: Int): Long? = (last(field) as? ProtoField.Varint)?.value

        fun int(field: Int): Int? = varint(field)?.toInt()

        fun bool(field: Int): Boolean? = varint(field)?.let { it != 0L }

        /** A double is a fixed64 carrying IEEE-754 bits. */
        fun double(field: Int): Double? =
            (last(field) as? ProtoField.Fixed64)?.let { Double.fromBits(it.bits) }

        /** A float is a fixed32; widened to Double so callers deal in one number type. */
        fun float(field: Int): Double? =
            (last(field) as? ProtoField.Fixed32)?.let { Float.fromBits(it.bits).toDouble() }

        /** Either width, whichever the device used — tvOS is not always consistent. */
        fun realNumber(field: Int): Double? = double(field) ?: float(field)

        fun bytes(field: Int): ByteArray? = (last(field) as? ProtoField.LengthDelimited)?.bytes

        fun string(field: Int): String? = bytes(field)?.decodeToString()

        fun message(field: Int): ProtoMessage? = bytes(field)?.let { decode(it) }

        /** Every value for a repeated length-delimited field, as sub-messages. */
        fun messages(field: Int): List<ProtoMessage> =
            fields[field].orEmpty().mapNotNull { f ->
                (f as? ProtoField.LengthDelimited)?.let { decode(it.bytes) }
            }
    }

    // ------------------------------------------------------------------ reading

    fun decode(data: ByteArray): ProtoMessage {
        val fields = LinkedHashMap<Int, MutableList<ProtoField>>()
        var pos = 0
        while (pos < data.size) {
            val (tag, afterTag) = readVarint(data, pos)
            pos = afterTag
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            if (field == 0) throw FormatException("protobuf field number 0 is not valid")
            val (value, next) = when (wire) {
                WireType.VARINT -> {
                    val (v, p) = readVarint(data, pos)
                    ProtoField.Varint(v) to p
                }
                WireType.FIXED64 -> ProtoField.Fixed64(readFixed(data, pos, 8)) to pos + 8
                WireType.FIXED32 -> ProtoField.Fixed32(readFixed(data, pos, 4).toInt()) to pos + 4
                WireType.LENGTH_DELIMITED -> {
                    val (len, p) = readVarint(data, pos)
                    val length = len.toInt()
                    if (length < 0 || p + length > data.size) {
                        throw FormatException("length-delimited field runs past the buffer")
                    }
                    ProtoField.LengthDelimited(data.copyOfRange(p, p + length)) to p + length
                }
                else -> throw FormatException("unsupported protobuf wire type $wire on field $field")
            }
            fields.getOrPut(field) { mutableListOf() }.add(value)
            pos = next
        }
        return ProtoMessage(fields)
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = start
        while (true) {
            if (pos >= data.size) throw FormatException("truncated varint")
            val b = data[pos].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) break
            shift += 7
            if (shift >= 64) throw FormatException("varint wider than 64 bits")
        }
        return result to pos
    }

    /** Little-endian fixed-width integer, as the wire format specifies. */
    private fun readFixed(data: ByteArray, start: Int, size: Int): Long {
        if (start + size > data.size) throw FormatException("truncated fixed$size field")
        var value = 0L
        for (i in 0 until size) value = value or ((data[start + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    // ------------------------------------------------------------------ writing

    /**
     * Builds a message field by field. Fields are emitted in the order called; every builder
     * here calls them in ascending number order to match pyatv byte for byte — the data
     * channel leans on the first field being `type` (tag 0x08) to spot an unprefixed message.
     */
    class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(field: Int, value: Long): Writer = apply {
            writeTag(field, WireType.VARINT)
            emitVarint(out, value)
        }

        fun int(field: Int, value: Int): Writer = varint(field, value.toLong())

        fun bool(field: Int, value: Boolean): Writer = varint(field, if (value) 1L else 0L)

        fun enum(field: Int, value: Int): Writer = varint(field, value.toLong())

        fun double(field: Int, value: Double): Writer = apply {
            writeTag(field, WireType.FIXED64)
            writeFixed(value.toRawBits(), 8)
        }

        fun float(field: Int, value: Float): Writer = apply {
            writeTag(field, WireType.FIXED32)
            writeFixed(value.toRawBits().toLong() and 0xFFFFFFFFL, 4)
        }

        fun bytes(field: Int, value: ByteArray): Writer = apply {
            writeTag(field, WireType.LENGTH_DELIMITED)
            emitVarint(out, value.size.toLong())
            out.write(value)
        }

        fun string(field: Int, value: String): Writer = bytes(field, value.toByteArray(Charsets.UTF_8))

        fun message(field: Int, nested: Writer): Writer = bytes(field, nested.toByteArray())

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun writeTag(field: Int, wire: Int) = emitVarint(out, ((field.toLong()) shl 3) or wire.toLong())

        private fun writeFixed(value: Long, size: Int) {
            for (i in 0 until size) out.write(((value ushr (8 * i)) and 0xFF).toInt())
        }
    }

    private fun emitVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) { out.write(b); break }
            out.write(b or 0x80)
        }
    }

    /** Encode an unsigned length as a base-128 varint — the data channel's length prefix. */
    fun writeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        emitVarint(out, value)
        return out.toByteArray()
    }

    /** Read a base-128 varint from [start], returning the value and the next offset. */
    fun readVarintAt(data: ByteArray, start: Int): Pair<Long, Int> = readVarint(data, start)
}
