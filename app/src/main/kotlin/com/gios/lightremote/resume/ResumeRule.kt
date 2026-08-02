package com.gios.lightremote.resume

/**
 * Decides whether waking the phone should put the remote back on screen.
 *
 * The whole feature turns on one distinction Android already draws for us and that nothing
 * else does: **`onUserLeaveHint` fires when the user leaves, and not when the system takes
 * the screen away.** Pressing home, or swiping to the task switcher, calls it. The screen
 * timing out, the display being switched off with the power key, an incoming activity
 * stealing focus — none of those do. So "did he put the remote down, or did the phone just
 * go to sleep on him?" is answerable without guessing at timings or polling the keyguard.
 *
 * Hence: resuming arms, leaving on purpose disarms, and a wake only relaunches while armed.
 * Backing out of the app finishes the activity, which is also leaving on purpose, but it
 * arrives as `onStop(finishing = true)` with no leave hint — so that is checked separately.
 *
 * No Android imports here on purpose; this is the part with the rules in it, and it is
 * cheaper to test as plain Kotlin than to stand up a phone to press home on.
 */
class ResumeRule(private val stayOpen: () -> Boolean) {

    /** The remote was on screen and did not leave by choice. */
    private var armed = false

    /** Already on screen, so a wake has nothing to restore. */
    private var foreground = false

    fun onResumed() {
        foreground = true
        armed = true
    }

    fun onPaused() {
        foreground = false
    }

    /** Home or the task switcher — a deliberate exit, whatever comes after. */
    fun onUserLeave() {
        armed = false
    }

    /**
     * Back out of the app finishes it. That is the same intent as pressing home and gets the
     * same treatment, but it never produces a leave hint, so it is caught here instead.
     */
    fun onStopped(finishing: Boolean) {
        foreground = false
        if (finishing) armed = false
    }

    /**
     * Read on `ACTION_SCREEN_ON`. `stayOpen` is read last and late rather than cached, so
     * turning the setting off takes effect on the very next wake instead of the next launch.
     */
    fun shouldRelaunchOnWake(): Boolean = armed && !foreground && stayOpen()

    /** Visible for tests and for the setting, which disarms as it is switched off. */
    fun disarm() {
        armed = false
    }
}
