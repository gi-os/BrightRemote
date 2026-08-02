package com.gios.lightremote

import android.app.Application
import com.gios.lightremote.resume.WakeWatcher

/**
 * Exists for one reason: something has to outlive the activity to hear the screen come back
 * on. See [WakeWatcher] — `ACTION_SCREEN_ON` is only delivered to receivers registered in
 * code, and the activity is stopped at the moment the broadcast arrives.
 *
 * The receiver is registered unconditionally, including when the setting is off. Registering
 * an `IntentFilter` costs nothing until a broadcast lands, and the alternative — registering
 * and unregistering as the setting is toggled — has a failure mode where the app was killed
 * with the setting off, the user turns it on, and nothing is listening on the path that
 * matters. The rule itself stays disarmed, so an off setting relaunches nothing.
 */
class LightRemoteApp : Application() {

    lateinit var wake: WakeWatcher
        private set

    override fun onCreate() {
        super.onCreate()
        wake = WakeWatcher(this)
        wake.register()
    }
}
