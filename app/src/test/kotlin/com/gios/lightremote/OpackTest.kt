package com.gios.lightremote

import com.gios.lightremote.proto.Opack
import com.gios.lightremote.proto.Tlv8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpackTest {

    /**
     * Every encode vector came out of pyatv's own `opack.pack`, so matching the hex means
     * the encoder is wire-identical, including the back-reference table.
     */
    @Test
    fun `pack matches reference bytes`() {
        for (name in Vectors.names("opack.names")) {
            val expected = Vectors["opack.$name"]
            val value = sampleFor(name)
            assertEquals(expected, Vectors.encodeHex(Opack.pack(value)), "pack $name")
        }
    }

    @Test
    fun `unpack round-trips every reference encoding`() {
        for (name in Vectors.names("opack.names")) {
            val hex = Vectors["opack.$name"]
            val decoded = Opack.unpack(Vectors.decodeHex(hex))
            assertEquals(hex, Vectors.encodeHex(Opack.pack(decoded)), "round trip $name")
        }
    }

    @Test
    fun `unpack round-trips device response shapes`() {
        for (name in Vectors.names("opackdec.names")) {
            val hex = Vectors["opackdec.$name"]
            val decoded = Opack.unpack(Vectors.decodeHex(hex))
            assertEquals(hex, Vectors.encodeHex(Opack.pack(decoded)), "round trip $name")
        }
    }

    @Test
    fun `decodes a device app list`() {
        @Suppress("UNCHECKED_CAST")
        val frame = Opack.unpackMap(Vectors.hex("opackdec.resp_applist"))
        assertEquals(3L, frame["_t"])
        val content = frame["_c"] as Map<String, String>
        assertEquals("Netflix", content["com.netflix.Netflix"])
        assertEquals("YouTube", content["com.google.ios.youtube"])
        assertEquals(3, content.size)
    }

    @Test
    fun `decodes a media control flags event`() {
        val frame = Opack.unpackMap(Vectors.hex("opackdec.event_imc"))
        assertEquals("_iMC", frame["_i"])
        assertEquals(1L, frame["_t"])
        @Suppress("UNCHECKED_CAST")
        val content = frame["_c"] as Map<String, Any?>
        assertEquals(0x703L, content["_mcF"])
    }

    @Test
    fun `decodes an error frame`() {
        val frame = Opack.unpackMap(Vectors.hex("opackdec.resp_error"))
        assertEquals("No request handler", frame["_em"])
    }

    @Test
    fun `decodes a fractional volume`() {
        val frame = Opack.unpackMap(Vectors.hex("opackdec.resp_volume"))
        @Suppress("UNCHECKED_CAST")
        val content = frame["_c"] as Map<String, Any?>
        assertEquals(0.375, content["_vol"])
    }

    /**
     * The back-reference table is the one part a naive implementation gets wrong, and the
     * `_mcc` frame exercises it for real: the string appears as both a value and a key, so
     * the second occurrence is a single 0xA1 byte.
     */
    @Test
    fun `reuses repeated objects as back references`() {
        val packed = Opack.pack(mapOf("_i" to "_mcc", "_t" to 2L, "_c" to mapOf("_mcc" to 7L)))
        assertTrue(
            Vectors.encodeHex(packed).contains("a1"),
            "expected a back reference, got ${Vectors.encodeHex(packed)}",
        )
        @Suppress("UNCHECKED_CAST")
        val decoded = Opack.unpack(packed) as Map<String, Any?>
        assertEquals("_mcc", decoded["_i"])
        assertEquals(7L, (decoded["_c"] as Map<*, *>)["_mcc"])
    }

    @Test
    fun `rejects negative integers with an actionable message`() {
        val error = assertFailsWith<Opack.FormatException> { Opack.pack(mapOf("_skpS" to -10L)) }
        assertTrue(error.message!!.contains("Double"), error.message!!)
    }

    @Test
    fun `rejects truncated input rather than returning junk`() {
        assertFailsWith<Opack.FormatException> { Opack.unpack(byteArrayOf(0x45, 0x61)) }
        assertFailsWith<Opack.FormatException> { Opack.unpack(byteArrayOf(0xE2.toByte())) }
    }

    @Test
    fun `rejects an out-of-range back reference`() {
        assertFailsWith<Opack.FormatException> { Opack.unpack(byteArrayOf(0xA5.toByte())) }
    }

    @Test
    fun `tlv8 matches reference bytes and reassembles long values`() {
        for (name in Vectors.names("tlv8.names")) {
            val encoded = Vectors.hex("tlv8.$name")
            val decoded = Tlv8.read(encoded)
            for ((tag, value) in decoded) {
                assertEquals(
                    Vectors["tlv8dec.$name.$tag"],
                    Vectors.encodeHex(value),
                    "tlv8 $name tag $tag",
                )
            }
            // Re-encoding the parsed map must give back the original bytes, which also
            // proves the 255-byte fragmentation is split at the same boundaries.
            assertEquals(Vectors["tlv8.$name"], Vectors.encodeHex(Tlv8.write(decoded)), "tlv8 rewrite $name")
        }
    }

    @Test
    fun `tlv8 splits a 384 byte key into two fragments`() {
        val key = ByteArray(384) { it.toByte() }
        val encoded = Tlv8.write(mapOf(Tlv8.PUBLIC_KEY to key))
        // 2 headers + 255 + 129 payload bytes.
        assertEquals(2 + 255 + 2 + 129, encoded.size)
        assertTrue(Tlv8.read(encoded)[Tlv8.PUBLIC_KEY]!!.contentEquals(key))
    }

    @Test
    fun `tlv8 surfaces a wrong pin as an authentication error`() {
        val tlv = Tlv8.read(Tlv8.write(mapOf(Tlv8.ERROR to byteArrayOf(0x02))))
        assertTrue(Tlv8.errorMessage(tlv)!!.contains("PIN"))
    }

    // ------------------------------------------------------------------ fixtures

    /** The Kotlin equivalents of the Python values in `scripts/genvec.py`. */
    private fun sampleFor(name: String): Any? = when (name) {
        "null" -> null
        "true" -> true
        "false" -> false
        "int_0" -> 0L
        "int_1" -> 1L
        "int_39" -> 0x27L
        "int_40" -> 0x28L
        "int_255" -> 0xFFL
        "int_256" -> 0x100L
        "int_65535" -> 0xFFFFL
        "int_65536" -> 0x10000L
        "int_2p32m1" -> 0xFFFFFFFFL
        "int_2p32" -> 0x100000000L
        "float_1_5" -> 1.5
        "float_neg" -> -30.0
        "str_empty" -> ""
        "str_short" -> "test"
        "str_32" -> "a".repeat(32)
        "str_33" -> "a".repeat(33)
        "str_300" -> "b".repeat(300)
        "bytes_empty" -> ByteArray(0)
        "bytes_short" -> byteArrayOf(1, 2, 3)
        "bytes_32" -> ByteArray(32) { 0xAA.toByte() }
        "bytes_33" -> ByteArray(33) { 0xAA.toByte() }
        "bytes_300" -> ByteArray(300) { 0xBB.toByte() }
        "uuid" -> java.util.UUID.fromString("12345678-1234-5678-1234-567812345678")
        "list_empty" -> emptyList<Any?>()
        "list_small" -> listOf(1L, 2L, 3L)
        "list_15" -> (0L until 15L).toList()
        "list_20" -> (0L until 20L).toList()
        "dict_empty" -> emptyMap<String, Any?>()
        "dict_small" -> linkedMapOf("a" to 1L, "b" to 2L)
        "dict_nested" -> mapOf("a" to mapOf("b" to listOf(1L, 2L, mapOf("c" to true))))
        "dict_dedup" -> linkedMapOf("one" to "aaaaaaaa", "two" to "aaaaaaaa", "three" to "aaaaaaaa")
        "dict_16" -> LinkedHashMap<String, Any?>().apply {
            for (i in 0 until 16) put("k%02d".format(i), i.toLong())
        }
        "msg_hidc_down" -> linkedMapOf(
            "_i" to "_hidC", "_t" to 2L,
            "_c" to linkedMapOf("_hBtS" to 1L, "_hidC" to 6L), "_x" to 12345L,
        )
        "msg_hidt" -> linkedMapOf(
            "_i" to "_hidT", "_t" to 1L,
            "_c" to linkedMapOf(
                "_ns" to 123456789L, "_tFg" to 1L, "_cx" to 500L, "_tPh" to 1L, "_cy" to 500L,
            ),
            "_x" to 7L,
        )
        "msg_touchstart" -> linkedMapOf(
            "_i" to "_touchStart", "_t" to 2L,
            "_c" to linkedMapOf("_height" to 1000.0, "_tFl" to 0L, "_width" to 1000.0), "_x" to 1L,
        )
        "msg_interest" -> linkedMapOf(
            "_i" to "_interest", "_t" to 1L,
            "_c" to linkedMapOf("_regEvents" to listOf("_iMC")), "_x" to 2L,
        )
        "msg_sessionstart" -> linkedMapOf(
            "_i" to "_sessionStart", "_t" to 2L,
            "_c" to linkedMapOf("_srvT" to "com.apple.tvremoteservices", "_sid" to 3735928559L),
            "_x" to 3L,
        )
        "msg_launchapp" -> linkedMapOf(
            "_i" to "_launchApp", "_t" to 2L,
            "_c" to linkedMapOf("_bundleID" to "com.netflix.Netflix"), "_x" to 4L,
        )
        "msg_mcc" -> linkedMapOf(
            "_i" to "_mcc", "_t" to 2L,
            "_c" to linkedMapOf("_mcc" to 7L, "_skpS" to -10.0), "_x" to 5L,
        )
        "msg_systeminfo" -> linkedMapOf(
            "_i" to "_systemInfo", "_t" to 2L,
            "_c" to linkedMapOf(
                "_bf" to 0L, "_cf" to 512L, "_clFl" to 128L, "_i" to "6c62fca18a4e",
                "_idsID" to "0123456789abcdef".toByteArray(),
                "_pubID" to "AA:BB:CC:DD:EE:FF",
                "_sf" to 256L, "_sv" to "170.18", "model" to "iPhone14,3", "name" to "Light Phone",
            ),
            "_x" to 6L,
        )
        else -> error("no Kotlin fixture for opack vector '$name'")
    }
}
