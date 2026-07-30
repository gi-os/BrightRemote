package com.gios.lightremote.companion

import android.util.Log

/**
 * Frame-level tracing for the Companion link.
 *
 * There is no way to watch this protocol from the outside — it is encrypted after
 * pair-verify and the only symptom of most mistakes is a device that goes quiet. So the app
 * narrates what it puts on the wire and what comes back:
 *
 * ```
 * adb logcat -s LightRemote
 * ```
 *
 * Deliberately never logs payload bytes. Frame type, length and whether the frame was
 * encrypted are enough to tell which step stalled, and dumping the bodies would put pairing
 * keys and the session keys into logcat.
 */
object Trace {
    const val TAG = "LightRemote"

    /** Off by default; the connection turns it on so a release build can still be debugged. */
    @Volatile
    var enabled: Boolean = true

    fun sent(type: FrameType, length: Int, encrypted: Boolean) {
        if (enabled) Log.d(TAG, ">> ${type.name} ${length}B${if (encrypted) " enc" else ""}")
    }

    fun received(type: FrameType, length: Int, encrypted: Boolean) {
        if (enabled) Log.d(TAG, "<< ${type.name} ${length}B${if (encrypted) " enc" else ""}")
    }

    fun step(name: String) {
        if (enabled) Log.d(TAG, "-- $name")
    }

    fun request(identifier: String, xid: Long) {
        if (enabled) Log.d(TAG, ">> request $identifier x=$xid")
    }

    fun response(identifier: String?, xid: Long?) {
        if (enabled) Log.d(TAG, "<< response ${identifier ?: "?"} x=$xid")
    }

    fun event(name: String) {
        if (enabled) Log.d(TAG, "<< event $name")
    }

    /**
     * A frame nobody was waiting for. This is the single most useful line in the log: an
     * unmatched reply is what turns into a timeout a few seconds later, and knowing the
     * frame type or transaction id says immediately whether the device answered with
     * something unexpected or did not answer at all.
     */
    fun unmatched(what: String) {
        Log.w(TAG, "!! unmatched $what")
    }

    fun problem(what: String, error: Throwable? = null) {
        if (error != null) Log.w(TAG, "!! $what", error) else Log.w(TAG, "!! $what")
    }
}
