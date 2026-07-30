package com.gios.lightremote

import com.gios.lightremote.proto.BPlist
import com.gios.lightremote.proto.RtiPayloads
import com.gios.lightremote.proto.Uid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BPlistTest {

    private val sessionUuid = ByteArray(16) { it.toByteArray0() }

    private fun Int.toByteArray0(): Byte = this.toByte()

    /**
     * Byte equality against CPython `plistlib`. This is the only verification available
     * for text input without an Apple TV in the loop, so it has to be exact rather than
     * merely structurally similar.
     */
    @Test
    fun `clear text payload matches plistlib`() {
        assertEquals(
            Vectors["rti.clear"],
            Vectors.encodeHex(RtiPayloads.clearText(Vectors.hex("rti.session_uuid"))),
        )
    }

    @Test
    fun `insert text payload matches plistlib`() {
        assertEquals(
            Vectors["rti.insert_hello"],
            Vectors.encodeHex(RtiPayloads.insertText(Vectors.hex("rti.session_uuid"), "hello")),
        )
    }

    /** Non-ASCII text switches the string to UTF-16BE, and the length is in code units. */
    @Test
    fun `insert text payload handles non-ascii`() {
        assertEquals(
            Vectors["rti.insert_unicode"],
            Vectors.encodeHex(RtiPayloads.insertText(Vectors.hex("rti.session_uuid"), "café — naïve")),
        )
    }

    @Test
    fun `reads session uuid and existing text from a device archive`() {
        val state = RtiPayloads.readSessionState(Vectors.hex("rti.device_tid"))
        assertNotNull(state)
        assertEquals(Vectors["rti.session_uuid"], Vectors.encodeHex(state.first))
        assertEquals(Vectors["rti.device_tid_expect_text"], state.second)
    }

    @Test
    fun `rejects a session uuid of the wrong length`() {
        assertFailsWith<IllegalArgumentException> { RtiPayloads.insertText(ByteArray(8), "x") }
        assertFailsWith<IllegalArgumentException> { RtiPayloads.clearText(ByteArray(20)) }
    }

    @Test
    fun `round-trips the primitive types`() {
        val original = linkedMapOf<String, Any?>(
            "int_small" to 5L,
            "int_two" to 300L,
            "int_four" to 100000L,
            "int_eight" to 5_000_000_000L,
            "double" to 1.5,
            "true" to true,
            "false" to false,
            "null" to null,
            "ascii" to "hello",
            "unicode" to "naïve",
            "long_string" to "x".repeat(400),
            "data" to ByteArray(20) { it.toByte() },
            "uid" to Uid(7),
            "list" to listOf(1L, "two", false),
            "nested" to linkedMapOf<String, Any?>("a" to 1L),
        )
        @Suppress("UNCHECKED_CAST")
        val decoded = BPlist.read(BPlist.write(original)) as Map<String, Any?>
        assertEquals(original.keys, decoded.keys)
        assertEquals(5L, decoded["int_small"])
        assertEquals(300L, decoded["int_two"])
        assertEquals(100000L, decoded["int_four"])
        assertEquals(5_000_000_000L, decoded["int_eight"])
        assertEquals(1.5, decoded["double"])
        assertEquals(true, decoded["true"])
        assertEquals(false, decoded["false"])
        assertEquals(null, decoded["null"])
        assertEquals("hello", decoded["ascii"])
        assertEquals("naïve", decoded["unicode"])
        assertEquals("x".repeat(400), decoded["long_string"])
        assertEquals(Uid(7), decoded["uid"])
        assertEquals(listOf(1L, "two", false), decoded["list"])
        assertTrue((decoded["data"] as ByteArray).contentEquals(ByteArray(20) { it.toByte() }))
    }

    /** A dictionary of 15 or more entries switches to the extended-length encoding. */
    @Test
    fun `round-trips containers past the fifteen entry boundary`() {
        for (size in intArrayOf(14, 15, 16, 300)) {
            val list = (0 until size).map { it.toLong() }
            @Suppress("UNCHECKED_CAST")
            assertEquals(list, BPlist.read(BPlist.write(list)) as List<Long>, "list of $size")
        }
    }

    @Test
    fun `rejects malformed input`() {
        assertFailsWith<BPlist.FormatException> { BPlist.read(ByteArray(10)) }
        assertFailsWith<BPlist.FormatException> { BPlist.read("bplist00".toByteArray() + ByteArray(32)) }
    }
}
