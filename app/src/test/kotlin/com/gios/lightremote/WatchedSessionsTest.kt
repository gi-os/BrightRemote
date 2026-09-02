package com.gios.lightremote

import com.gios.lightremote.watched.WatchedAssembler
import com.gios.lightremote.watched.WatchedCodec
import com.gios.lightremote.watched.WatchedSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The journal contract's session rules over a synthetic clock.
 *
 * Every boundary here — the five-minute pause, the two-minute floor, the close-at-the-pause
 * endpoint — is a promise the Notebook journal is being built against in parallel, so each
 * one gets its own test rather than a happy path that brushes past them.
 */
class WatchedSessionsTest {

    private val t0 = 1_756_700_000_000L // an arbitrary fixed epoch

    private fun min(minutes: Double): Long = (minutes * 60_000).toLong()

    @Test
    fun `one uninterrupted viewing becomes one session with the right duration`() {
        val a = WatchedAssembler()
        assertNull(a.playing("Severance", "Apple TV+", t0))
        assertNull(a.notPlaying(t0 + min(47.0)))
        val s = a.flush(t0 + min(47.1))
        assertEquals("Severance", s!!.title)
        assertEquals("Apple TV+", s.subtitle)
        assertEquals(t0, s.startAt)
        assertEquals(t0 + min(47.0), s.endAt, "the end is when playback stopped, not the flush")
        assertEquals(47, s.durationMin)
    }

    @Test
    fun `a pause under five minutes stays in the session but not in its duration`() {
        val a = WatchedAssembler()
        a.playing("Film", "", t0)
        a.notPlaying(t0 + min(20.0))                       // kettle at minute 20
        assertNull(a.playing("Film", "", t0 + min(24.0)))  // back 4 minutes later: same session
        val s = a.flush(t0 + min(54.0))
        assertEquals(t0, s!!.startAt)
        assertEquals(t0 + min(54.0), s.endAt)
        assertEquals(50, s.durationMin, "20 + 30 minutes played; the pause is not viewing")
    }

    @Test
    fun `a gap over five minutes is two sittings, split at the pause`() {
        val a = WatchedAssembler()
        a.playing("Film", "", t0)
        a.notPlaying(t0 + min(20.0))
        val first = a.playing("Film", "", t0 + min(30.0)) // ten minutes later: a new sitting
        assertEquals(t0 + min(20.0), first!!.endAt, "the first sitting ended at its pause")
        assertEquals(20, first.durationMin)
        val second = a.flush(t0 + min(40.0))
        assertEquals(t0 + min(30.0), second!!.startAt)
        assertEquals(10, second.durationMin)
    }

    @Test
    fun `a long pause closes the session on its own, without waiting for the next play`() {
        val a = WatchedAssembler()
        a.playing("Film", "", t0)
        a.notPlaying(t0 + min(20.0))
        assertNull(a.notPlaying(t0 + min(24.0)), "four minutes in, still one sitting")
        val s = a.notPlaying(t0 + min(26.0))
        assertEquals(t0 + min(20.0), s!!.endAt, "closed at the pause, not at the timeout check")
        assertNull(a.flush(t0 + min(60.0)), "nothing left open behind it")
    }

    @Test
    fun `a title change closes the old session at the moment of the change`() {
        val a = WatchedAssembler()
        a.playing("Episode 1", "Show", t0)
        val first = a.playing("Episode 2", "Show", t0 + min(42.0))
        assertEquals("Episode 1", first!!.title)
        assertEquals(t0 + min(42.0), first.endAt)
        assertEquals(42, first.durationMin)
        val second = a.flush(t0 + min(84.0))
        assertEquals("Episode 2", second!!.title)
        assertEquals(t0 + min(42.0), second.startAt)
    }

    @Test
    fun `under two minutes of playing is surfing and is dropped`() {
        val a = WatchedAssembler()
        a.playing("Trailer", "", t0)
        assertNull(a.playing("Film", "", t0 + min(1.5)), "90 seconds of trailer is not a session")
        val s = a.flush(t0 + min(4.0))
        assertEquals("Film", s!!.title, "the real viewing behind it still counts")
    }

    @Test
    fun `flush closes an open session so a disconnect cannot leave a phantom hour`() {
        val a = WatchedAssembler()
        a.playing("Film", "", t0)
        val s = a.flush(t0 + min(10.0))
        assertEquals(10, s!!.durationMin)
        assertNull(a.flush(t0 + min(70.0)), "flushing twice yields nothing new")
    }

    @Test
    fun `duration counts playing time across pauses, never the pauses`() {
        val a = WatchedAssembler()
        a.playing("Film", "", t0)
        a.notPlaying(t0 + min(10.0))
        a.playing("Film", "", t0 + min(12.0))
        a.notPlaying(t0 + min(22.0))
        a.playing("Film", "", t0 + min(25.0))
        val s = a.flush(t0 + min(30.0))
        assertEquals(25, s!!.durationMin, "10 + 10 + 5 minutes of actual playback")
    }

    @Test
    fun `a late subtitle fills in and an empty one never erases`() {
        val a = WatchedAssembler()
        a.playing("Song", "", t0)
        a.notPlaying(t0 + min(3.0))
        a.playing("Song", "The Artist", t0 + min(4.0))
        a.notPlaying(t0 + min(8.0))
        a.playing("Song", "", t0 + min(9.0))
        val s = a.flush(t0 + min(12.0))
        assertEquals("The Artist", s!!.subtitle)
    }

    // ------------------------------------------------------------------ codec

    @Test
    fun `sessions round-trip through the store, tabs and newlines included`() {
        val awkward = WatchedSession(t0, t0 + min(5.0), "A\ttitle\nwith\\escapes", "Sub\ttitle", 5)
        val plain = WatchedSession(t0 + min(10.0), t0 + min(20.0), "Plain", "", 10)
        val decoded = WatchedCodec.decode(WatchedCodec.encode(listOf(awkward, plain)))
        assertEquals(listOf(awkward, plain), decoded)
    }

    @Test
    fun `append prunes anything older than sixty days`() {
        val ancient = WatchedSession(t0 - WatchedCodec.KEEP_MS - min(1.0), t0 - WatchedCodec.KEEP_MS, "Old", "", 30)
        val recent = WatchedSession(t0 - min(60.0), t0 - min(30.0), "Recent", "", 30)
        val stored = WatchedCodec.encode(listOf(ancient, recent))
        val fresh = WatchedSession(t0, t0 + min(30.0), "Fresh", "", 30)
        val kept = WatchedCodec.decode(WatchedCodec.append(stored, fresh, now = t0 + min(30.0)))
        assertEquals(listOf("Recent", "Fresh"), kept.map { it.title })
    }

    @Test
    fun `garbage in the store decodes to nothing instead of crashing the provider`() {
        assertTrue(WatchedCodec.decode("not\ta\tsession\nat all\n\n12\t34").isEmpty())
    }
}
