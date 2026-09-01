package com.gios.lightremote.airplay

import com.gios.lightremote.companion.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One interactive AirPlay PIN pairing, from "put a code on the television" to long-term
 * credentials.
 *
 * The deliberate counterpart to the silent paths in [AirPlayPairing]: nothing here runs
 * unless the user asked to pair. [begin] opens its own control connection and posts
 * `/pair-pin-start` — the exact moment tvOS draws the four digits — and [complete] finishes
 * the SRP exchange with the code they typed. Success or failure, the connection is then
 * closed: the credentials are for storing, and the tunnel that uses them opens its own
 * session and pair-verifies like any later connect.
 *
 * A wrong code invalidates the whole exchange on the device side — HAP forbids resuming SRP
 * after a failed proof — so retrying means [begin] again, and a fresh code on the screen.
 *
 * Socket work happens on [Dispatchers.IO]. Nothing here constructs a Handler, a session, or
 * any other thread-bound platform object, so it is safe to drive from a view model scope.
 */
class AirPlayPinSetup(
    private val host: String,
    private val port: Int = MrpTunnel.DEFAULT_AIRPLAY_PORT,
) {
    private var channel: RtspChannel? = null
    private var pairing: AirPlayPairing? = null
    private var setup: AirPlayPairing.Setup? = null

    /** Connect and ask the television to display its code. */
    suspend fun begin() {
        withContext(Dispatchers.IO) {
            closeQuietly()
            val ch = RtspChannel(host, port)
            ch.connect()
            channel = ch
            val p = AirPlayPairing(ch)
            pairing = p
            setup = p.beginPairSetup()
        }
    }

    /**
     * Finish with the code on the screen. The connection is closed either way: on failure the
     * device has already torn the exchange down, and on success there is nothing more this
     * connection is for.
     */
    suspend fun complete(pin: String, displayName: String?): Credentials =
        withContext(Dispatchers.IO) {
            val p = checkNotNull(pairing) { "complete() before begin()" }
            val s = checkNotNull(setup) { "complete() before begin()" }
            try {
                p.completePairSetup(s, pin, displayName)
            } finally {
                closeQuietly()
            }
        }

    /**
     * Abort. Safe to call twice, safe mid-exchange — closing the socket is the whole abort,
     * because credentials only exist once [complete] has returned them. There is no
     * half-stored state to clean up; the television drops its dialog on its own timeout.
     */
    fun closeQuietly() {
        runCatching { channel?.close() }
        channel = null
        pairing = null
        setup = null
    }
}
