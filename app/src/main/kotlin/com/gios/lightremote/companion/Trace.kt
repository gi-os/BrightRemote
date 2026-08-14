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

    /**
     * The last [KEEP] lines, timestamped, whether or not logcat is on.
     *
     * This exists for the error reports. `adb logcat` needs a laptop and a cable, and the one
     * person these reports come from is standing in front of a television — so when the link
     * dies, the report has to carry the wire narrative with it or the disconnect is
     * undiagnosable from the field. Frame types, lengths and step names only, same as the
     * log: payload bytes never land here, for the same reason they never land in logcat.
     */
    private const val KEEP = 150
    private val ring = ArrayDeque<String>(KEEP)
    private val started = System.nanoTime()

    private fun keep(line: String) {
        val ms = (System.nanoTime() - started) / 1_000_000
        synchronized(ring) {
            if (ring.size == KEEP) ring.removeFirst()
            ring.addLast("+${ms}ms $line")
        }
    }

    /** The recent wire narrative, newest last, for attaching to a report. */
    fun tail(): String = synchronized(ring) { ring.joinToString("\n") }

    fun sent(type: FrameType, length: Int, encrypted: Boolean) {
        keep(">> ${type.name} ${length}B${if (encrypted) " enc" else ""}")
        if (enabled) Log.d(TAG, ">> ${type.name} ${length}B${if (encrypted) " enc" else ""}")
    }

    fun received(type: FrameType, length: Int, encrypted: Boolean) {
        keep("<< ${type.name} ${length}B${if (encrypted) " enc" else ""}")
        if (enabled) Log.d(TAG, "<< ${type.name} ${length}B${if (encrypted) " enc" else ""}")
    }

    fun step(name: String) {
        keep("-- $name")
        if (enabled) Log.d(TAG, "-- $name")
    }

    fun request(identifier: String, xid: Long) {
        keep(">> request $identifier x=$xid")
        if (enabled) Log.d(TAG, ">> request $identifier x=$xid")
    }

    fun response(identifier: String?, xid: Long?) {
        keep("<< response ${identifier ?: "?"} x=$xid")
        if (enabled) Log.d(TAG, "<< response ${identifier ?: "?"} x=$xid")
    }

    fun event(name: String) {
        keep("<< event $name")
        if (enabled) Log.d(TAG, "<< event $name")
    }

    /**
     * A frame nobody was waiting for. This is the single most useful line in the log: an
     * unmatched reply is what turns into a timeout a few seconds later, and knowing the
     * frame type or transaction id says immediately whether the device answered with
     * something unexpected or did not answer at all.
     */
    fun unmatched(what: String) {
        keep("!! unmatched $what")
        Log.w(TAG, "!! unmatched $what")
    }

    fun problem(what: String, error: Throwable? = null) {
        keep("!! $what${error?.let { " (${it::class.java.simpleName}: ${it.message})" } ?: ""}")
        if (error != null) Log.w(TAG, "!! $what", error) else Log.w(TAG, "!! $what")
    }
}
