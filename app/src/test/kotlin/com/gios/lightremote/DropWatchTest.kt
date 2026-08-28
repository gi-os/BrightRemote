package com.gios.lightremote

import com.gios.lightremote.report.DropWatch
import com.gios.lightremote.report.FaultKind
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The policy behind auto-sent failure reports.
 *
 * Worth testing rather than eyeballing because the previous version of this idea, in another app
 * on this phone, filed thirty issues for one dead socket — and it read as obviously correct. The
 * two things that stop that are both time-dependent, which is exactly what a synthetic clock is
 * for: the app cannot wait an hour to find out whether it would have flooded.
 */
class DropWatchTest {

    /** A watch over a clock the test moves, and a ledger the test can inspect. */
    private class Rig(var now: Long = 1_000L) {
        var ledger: String = ""
        val watch = DropWatch(
            nowMs = { now },
            load = { ledger },
            store = { ledger = it },
        )

        /** A second watch over the same ledger — what the next launch of the app sees. */
        fun relaunch(): DropWatch = DropWatch(nowMs = { now }, load = { ledger }, store = { ledger = it })
    }

    @Test
    fun `a burst of failures is one report`() {
        val rig = Rig()
        val settle = rig.watch.record(FaultKind.Dropped, "ProtocolException: closed by peer")
        assertEquals(DropWatch.SETTLE_MS, settle)
        rig.now += 1_200
        assertNull(
            rig.watch.record(FaultKind.ConnectFailed, "SocketTimeoutException: failed to connect"),
            "a failure inside an open episode must not schedule a second report",
        )
        rig.now += 2_400
        assertNull(rig.watch.record(FaultKind.ConnectFailed, "SocketTimeoutException: failed to connect"))

        rig.now += 5_400
        val report = assertNotNull(rig.watch.due("trace lines"))
        assertEquals(FaultKind.Dropped, report.kind, "the episode is named by what started it")
        assertTrue(report.detail.startsWith("3 failures over"), report.detail)
        assertTrue(report.detail.contains("still disconnected"), report.detail)
        assertTrue(report.detail.contains("trace lines"), "the wire trace has to ride along")
        assertNull(rig.watch.due("trace lines"), "the episode is spent")
    }

    @Test
    fun `a drop that self-heals still reports, and says so`() {
        val rig = Rig()
        rig.watch.record(FaultKind.Dropped, "ProtocolException: closed by peer")
        rig.now += 4_100
        rig.watch.recovered()
        rig.now += 4_900
        val report = assertNotNull(rig.watch.due("t"))
        assertTrue(report.detail.contains("picked itself back up after 4.1s"), report.detail)
    }

    @Test
    fun `no two reports inside the floor, whatever they say`() {
        val rig = Rig()
        rig.watch.record(FaultKind.Dropped, "ProtocolException: closed by peer")
        rig.now += DropWatch.SETTLE_MS
        assertNotNull(rig.watch.due("t"))

        // A different signature entirely: the floor is not about what the failure says.
        rig.now += 10_000
        rig.watch.record(FaultKind.Unanswered, "three presses in a row went unacknowledged")
        rig.now += DropWatch.SETTLE_MS
        assertNull(rig.watch.due("t"))
        assertNotNull(rig.watch.heldCause(), "a refused episode must stay sendable by hand")

        val forced = assertNotNull(rig.watch.forceHeld("t"), "a person asking beats the throttle")
        assertEquals(FaultKind.Unanswered, forced.kind)
        assertNull(rig.watch.forceHeld("t"), "and only once")
    }

    @Test
    fun `the same fault backs off, and the next report carries what was skipped`() {
        val rig = Rig()
        fun drop(cause: String = "ProtocolException: closed by peer") {
            rig.watch.record(FaultKind.Dropped, cause)
            rig.now += DropWatch.SETTLE_MS
        }
        drop()
        assertNotNull(rig.watch.due("t"))

        // Past the floor but inside the two-minute backoff for a fault already reported.
        rig.now += 80_000
        drop()
        assertNull(rig.watch.due("t"))

        // Same fault wearing different numbers — a port, a length, a transaction id. This is the
        // mistake that turned one broken socket into thirty issues: every number made a failure
        // look brand new, and every brand new failure got to be a first offence.
        rig.now += 20_000
        drop("ProtocolException: closed by peer after 41 frames")
        assertNull(rig.watch.due("t"))

        rig.now += 3 * 60_000
        drop()
        val report = assertNotNull(rig.watch.due("t"))
        assertTrue(report.detail.contains("2 more like this went unreported"), report.detail)
    }

    @Test
    fun `the throttle survives the process`() {
        val rig = Rig()
        rig.watch.record(FaultKind.Dropped, "ProtocolException: closed by peer")
        rig.now += DropWatch.SETTLE_MS
        assertNotNull(rig.watch.due("t"))

        // A crash on launch, or a phone rebooting into the same broken Wi-Fi. In memory only,
        // this is where the same drop gets filed once per launch.
        val next = rig.relaunch()
        rig.now += 5_000
        next.record(FaultKind.Dropped, "ProtocolException: closed by peer")
        rig.now += DropWatch.SETTLE_MS
        assertNull(next.due("t"))
    }

    @Test
    fun `a fault that stays away for hours reports promptly again`() {
        val rig = Rig()
        repeat(3) {
            rig.watch.record(FaultKind.Dropped, "ProtocolException: closed by peer")
            rig.now += DropWatch.SETTLE_MS
            rig.watch.due("t")
            rig.now += 60 * 60_000
        }
        rig.now += DropWatch.STREAK_RESET_MS
        rig.watch.record(FaultKind.Dropped, "ProtocolException: closed by peer")
        rig.now += DropWatch.SETTLE_MS
        assertNotNull(rig.watch.due("t"), "six quiet hours starts the backoff over")
    }

    @Test
    fun `a ledger of nonsense is not fatal`() {
        val rig = Rig()
        rig.ledger = "A|not-a-number\nS|only|three\ngarbage"
        rig.watch.record(FaultKind.Dropped, "ProtocolException: closed by peer")
        rig.now += DropWatch.SETTLE_MS
        assertNotNull(rig.watch.due("t"))
    }
}
