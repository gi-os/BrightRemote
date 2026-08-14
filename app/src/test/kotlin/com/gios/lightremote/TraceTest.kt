package com.gios.lightremote

import com.gios.lightremote.companion.FrameType
import com.gios.lightremote.companion.Trace
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The trace ring is what a disconnect report carries, so it has to hold the recent lines and
 * stay bounded — an unbounded log inside a report body is its own bug.
 */
class TraceTest {

    @Test
    fun `the tail carries recent lines`() {
        Trace.step("test marker step")
        Trace.sent(FrameType.EncryptedOpack, 42, encrypted = true)
        Trace.problem("test marker problem", IllegalStateException("boom"))
        val tail = Trace.tail()
        assertTrue("test marker step" in tail, tail.takeLast(300))
        assertTrue("EncryptedOpack 42B enc" in tail, tail.takeLast(300))
        assertTrue("test marker problem (IllegalStateException: boom)" in tail, tail.takeLast(300))
    }

    @Test
    fun `the ring is bounded`() {
        repeat(500) { Trace.step("filler $it") }
        val lines = Trace.tail().lines()
        assertTrue(lines.size <= 150, "ring held ${lines.size} lines")
        // Newest last: the most recent filler must have survived.
        assertTrue("filler 499" in lines.last(), lines.last())
    }
}
