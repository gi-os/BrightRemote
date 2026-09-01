package com.gios.lightremote

import com.gios.lightremote.proto.BPlist
import com.gios.lightremote.proto.NowPlayingInfo
import com.gios.lightremote.proto.NowPlayingPayloads
import com.gios.lightremote.proto.Uid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NowPlayingTest {

    private fun classDescriptor(name: String): Map<String, Any?> = linkedMapOf(
        "\$classname" to name,
        "\$classes" to listOf(name, "NSObject"),
    )

    /**
     * Build the NSKeyedArchiver graph a real `NowPlayingInfo` event carries, laid out
     * exactly as the protocol dumps show it:
     *
     * ```
     * 0  "$null"
     * 1  TVRCNowPlayingInfo  -> playbackRate(2), metadata(4), $class(3)
     * 2  playbackRate value
     * 3  TVRCNowPlayingInfo class
     * 4  TVRCNowPlayingMetadata -> timeOffset(5), duration(6), $class(7)
     * 5  timeOffset value
     * 6  duration value
     * 7  TVRCNowPlayingMetadata class
     * ```
     */
    private fun archive(
        position: Double = 1234.5,
        duration: Double = 4567.8,
        rate: Double = 1.0,
    ): ByteArray = BPlist.write(
        linkedMapOf<String, Any?>(
            "\$version" to 100000L,
            "\$archiver" to "NSKeyedArchiver",
            "\$top" to linkedMapOf<String, Any?>("root" to Uid(1)),
            "\$objects" to listOf(
                "\$null",
                linkedMapOf<String, Any?>(
                    "\$class" to Uid(3),
                    "playbackRate" to Uid(2),
                    "metadata" to Uid(4),
                ),
                rate,
                classDescriptor("TVRCNowPlayingInfo"),
                linkedMapOf<String, Any?>(
                    "\$class" to Uid(7),
                    "timeOffset" to Uid(5),
                    "duration" to Uid(6),
                ),
                position,
                duration,
                classDescriptor("TVRCNowPlayingMetadata"),
            ),
        ),
    )

    @Test
    fun `reads position duration and rate from a now playing archive`() {
        val info = NowPlayingPayloads.readNowPlaying(archive())
        assertNotNull(info)
        assertEquals(1234.5, info.position, 1e-9)
        assertEquals(4567.8, info.duration, 1e-9)
        assertEquals(1.0, info.rate, 1e-9)
    }

    @Test
    fun `paused media reports a zero rate`() {
        val info = NowPlayingPayloads.readNowPlaying(archive(rate = 0.0))
        assertNotNull(info)
        assertEquals(0.0, info.rate, 1e-9)
    }

    @Test
    fun `stripped playbackRate-only packet has nothing to draw`() {
        // Some title changes push just the rate, with no metadata to measure progress by.
        val stripped = BPlist.write(
            linkedMapOf<String, Any?>(
                "\$version" to 100000L,
                "\$archiver" to "NSKeyedArchiver",
                "\$top" to linkedMapOf<String, Any?>("root" to Uid(1)),
                "\$objects" to listOf(
                    "\$null",
                    linkedMapOf<String, Any?>("\$class" to Uid(3), "playbackRate" to Uid(2)),
                    1.0,
                    classDescriptor("TVRCNowPlayingInfo"),
                ),
            ),
        )
        assertNull(NowPlayingPayloads.readNowPlaying(stripped))
    }

    @Test
    fun `not an archiver graph at all returns null`() {
        assertNull(NowPlayingPayloads.readNowPlaying(BPlist.write(linkedMapOf("a" to 1L))))
    }

    @Test
    fun `fraction clamps to the item length`() {
        assertEquals(1.0, NowPlayingInfo(9999.0, 100.0).fraction, 1e-9)
        assertEquals(0.25, NowPlayingInfo(25.0, 100.0).fraction, 1e-9)
        assertEquals(0.0, NowPlayingInfo(50.0, 0.0).fraction, 1e-9)
    }

    @Test
    fun `extrapolate walks forward while playing`() {
        val info = NowPlayingInfo(position = 10.0, duration = 100.0, rate = 1.0)
        assertEquals(12.0, info.extrapolate(2_000_000_000L), 1e-9)
    }

    @Test
    fun `extrapolate does not move while paused`() {
        val info = NowPlayingInfo(position = 10.0, duration = 100.0, rate = 0.0)
        assertEquals(10.0, info.extrapolate(2_000_000_000L), 1e-9)
    }

    @Test
    fun `extrapolate never walks past the end or before the start`() {
        val atEnd = NowPlayingInfo(position = 99.0, duration = 100.0, rate = 1.0)
        assertEquals(100.0, atEnd.extrapolate(30_000_000_000L), 1e-9)
        assertTrue(NowPlayingInfo(position = 5.0, duration = 100.0, rate = -1.0)
            .extrapolate(30_000_000_000L) >= 0.0)
    }
}
