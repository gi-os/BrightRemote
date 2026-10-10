package com.gios.lightremote.ui

/** What the view model does with a network edge from [com.gios.lightremote.net.NetworkWatch]. */
enum class NetworkRetry {
    /** Nothing to do: connected, no television to go back to, pairing, or inside the floor. */
    Ignore,

    /** Disconnected and idle: one fresh ladder. */
    Connect,

    /** A ladder is already running, against the network that just went away: start it again. */
    Restart,
}

/**
 * The view model's connection decisions that do not need Android to be made.
 *
 * Kept apart from [RemoteViewModel] so they can be tested on the JVM: both of these are rules
 * whose failure looked, from the sofa, exactly like the Apple TV dropping the connection.
 */
object LinkRules {

    /**
     * The least time between two reconnects triggered by the network coming back.
     *
     * Wi-Fi that is flapping produces an edge every time it settles, and each edge would otherwise
     * buy a full ladder of attempts. Half a minute is longer than any flap and shorter than the
     * patience of somebody holding a remote that has stopped working.
     */
    const val NETWORK_RETRY_FLOOR_MS = 30_000L

    /**
     * The answer to Wi-Fi coming back.
     *
     * A ladder in flight used to swallow the edge ("an attempt is already running"). That is the
     * wrong answer exactly when it matters. The ladder starts the moment a drop is noticed, and a
     * drop caused by the phone's own network going away — the `Software caused connection abort` in
     * light-reports#661, #628 and #591 — is noticed while that network is still away. So the
     * attempts go down a path that cannot reach the television: in those traces they fail at once
     * or time out after five seconds, and all three reports were filed still disconnected. Wi-Fi
     * returning in the middle of that ladder is the moment the reason for failing stopped being
     * true, so the ladder starts over on the new network instead of being left to finish failing.
     *
     * Still bounded: one edge buys at most one ladder, the floor applies to restarts as well as
     * fresh connects, and a reconnect sends no button presses, so nothing the user pressed can be
     * sent twice by it.
     */
    fun onNetworkEdge(
        hasTelevision: Boolean,
        pairing: Boolean,
        connected: Boolean,
        attemptInFlight: Boolean,
        sinceLastRetryMs: Long,
    ): NetworkRetry = when {
        !hasTelevision || pairing || connected -> NetworkRetry.Ignore
        sinceLastRetryMs < NETWORK_RETRY_FLOOR_MS -> NetworkRetry.Ignore
        attemptInFlight -> NetworkRetry.Restart
        else -> NetworkRetry.Connect
    }

    /**
     * Whether a connect request is for the television this remote is already talking to.
     *
     * Then it is navigation, not a request to tear down a working session and spend half a second
     * rebuilding it. This used to be checked for taps only, and the launch path got past it: the
     * activity runs `autoConnect` whenever it is created, a configuration change creates it again
     * while the view model and its live connection carry on, and the healthy connection was thrown
     * away and redialled. The wire traces show it as a new connection with no drop before it —
     * "connecting … (gen 8)" straight after the television's last push, then "ignoring teardown from
     * stale connection 7" (light-reports#591, twice inside a second; #598; #496).
     */
    fun alreadyOn(sameTelevision: Boolean, connected: Boolean, socketOpen: Boolean): Boolean =
        sameTelevision && connected && socketOpen
}
