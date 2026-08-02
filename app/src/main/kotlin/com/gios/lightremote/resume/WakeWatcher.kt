package com.gios.lightremote.resume

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.gios.lightremote.MainActivity
import com.gios.lightremote.data.Prefs

/**
 * Puts the remote back on screen when the phone wakes, if it was open when the phone slept.
 *
 * Three things make this more than a one-liner:
 *
 * 1. **`ACTION_SCREEN_ON` cannot be declared in the manifest.** It is one of the protected
 *    broadcasts Android only delivers to receivers registered in code, precisely so that
 *    every installed app does not get woken by a screen press. That means something has to
 *    already be running to hold the registration — hence registering from `Application`
 *    rather than from the activity, which is stopped at exactly the moment we care about.
 *    If the process is reclaimed while the phone sleeps the registration goes with it and
 *    the wake is missed; that is the honest ceiling on this feature without a foreground
 *    service, and a remote you used minutes ago is a cached process that usually survives.
 *
 * 2. **Starting an activity from a broadcast is background activity launch**, which Android
 *    10 closed and Android 14 tightened further. The exemption used here is
 *    `SYSTEM_ALERT_WINDOW` — "Display over other apps" — which the setting asks for when it
 *    is switched on. Without it the `startActivity` is dropped silently by the system, which
 *    is why the setting refuses to look enabled until the grant is real.
 *
 * 3. **The launcher is also waking up.** LightOS brings its home screen forward on wake, so
 *    firing immediately is a race we sometimes lose and the user sees a flicker of home
 *    either way. Landing deliberately a beat later is both more reliable and less jarring
 *    than winning by a few milliseconds sometimes.
 */
class WakeWatcher(private val app: Application) {

    val rule = ResumeRule { Prefs(app).stayOpen }

    private val main = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!rule.shouldRelaunchOnWake()) return
            // Only ever one pending relaunch: a wake on a locked phone delivers SCREEN_ON and
            // then USER_PRESENT a moment later, and both are worth listening to — some Light
            // Phones have no keyguard at all, so neither one alone covers every setup.
            main.removeCallbacksAndMessages(TOKEN)
            main.postAtTime({ relaunch() }, TOKEN, android.os.SystemClock.uptimeMillis() + DELAY_MS)
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        app.registerReceiver(receiver, filter)
    }

    private fun relaunch() {
        if (!rule.shouldRelaunchOnWake()) return
        if (!canRelaunch()) return
        // REORDER_TO_FRONT brings the task back exactly as it was left — same nav
        // destination, same view model, same live connection to the television. A plain
        // launcher-style start would reset the task to the remote screen and throw away a
        // half-typed search.
        val intent = Intent(app, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
        runCatching { app.startActivity(intent) }
    }

    /**
     * The overlay grant is re-read every time rather than remembered. It is revocable from
     * Settings at any moment, and an app that keeps trying after it is gone just writes the
     * same denial into logcat once per wake, forever.
     */
    fun canRelaunch(): Boolean = Settings.canDrawOverlays(app)

    private companion object {
        /** Long enough to land after the launcher, short enough to feel like it never left. */
        const val DELAY_MS = 450L
        val TOKEN = Any()
    }
}
