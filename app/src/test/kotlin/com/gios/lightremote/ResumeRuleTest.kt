package com.gios.lightremote

import com.gios.lightremote.resume.ResumeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distinction the whole feature rests on, exercised as callback sequences.
 *
 * Real orders, not invented ones:
 * - screen off:  onPause, onStop(finishing = false)
 * - home:        onUserLeave, onPause, onStop(finishing = false)
 * - back out:    onPause, onStop(finishing = true)
 */
class ResumeRuleTest {

    private var stayOpen = true
    private fun rule() = ResumeRule { stayOpen }

    private fun ResumeRule.screenOff() {
        onPaused()
        onStopped(finishing = false)
    }

    private fun ResumeRule.pressHome() {
        onUserLeave()
        onPaused()
        onStopped(finishing = false)
    }

    private fun ResumeRule.pressBack() {
        onPaused()
        onStopped(finishing = true)
    }

    @Test
    fun `screen off while open relaunches on wake`() {
        val rule = rule()
        rule.onResumed()
        rule.screenOff()
        assertTrue(rule.shouldRelaunchOnWake())
    }

    @Test
    fun `home leaves the app for good`() {
        val rule = rule()
        rule.onResumed()
        rule.pressHome()
        assertFalse(rule.shouldRelaunchOnWake())
    }

    @Test
    fun `back out leaves the app for good`() {
        val rule = rule()
        rule.onResumed()
        rule.pressBack()
        assertFalse(rule.shouldRelaunchOnWake())
    }

    /** Home, then the screen sleeps later. Still quit — the sleep does not re-arm anything. */
    @Test
    fun `screen off after home does not relaunch`() {
        val rule = rule()
        rule.onResumed()
        rule.pressHome()
        rule.screenOff()
        assertFalse(rule.shouldRelaunchOnWake())
    }

    /** Reopening from the launcher arms it again. */
    @Test
    fun `reopening after home re-arms`() {
        val rule = rule()
        rule.onResumed()
        rule.pressHome()
        rule.onResumed()
        rule.screenOff()
        assertTrue(rule.shouldRelaunchOnWake())
    }

    @Test
    fun `nothing relaunches while the setting is off`() {
        stayOpen = false
        val rule = rule()
        rule.onResumed()
        rule.screenOff()
        assertFalse(rule.shouldRelaunchOnWake())
    }

    /** The setting is read at wake, not cached at stop, so turning it off takes effect now. */
    @Test
    fun `turning the setting off between stop and wake takes effect`() {
        stayOpen = true
        val rule = rule()
        rule.onResumed()
        rule.screenOff()
        stayOpen = false
        assertFalse(rule.shouldRelaunchOnWake())
    }

    /** Nothing to restore if the app is already on screen — a wake with the app on top. */
    @Test
    fun `no relaunch while already in the foreground`() {
        val rule = rule()
        rule.onResumed()
        assertFalse(rule.shouldRelaunchOnWake())
    }

    /** A cold process that never showed the activity must not launch itself on a wake. */
    @Test
    fun `never shown means never relaunched`() {
        val rule = rule()
        assertFalse(rule.shouldRelaunchOnWake())
    }

    @Test
    fun `disarm stops a pending relaunch`() {
        val rule = rule()
        rule.onResumed()
        rule.screenOff()
        rule.disarm()
        assertFalse(rule.shouldRelaunchOnWake())
    }
}
