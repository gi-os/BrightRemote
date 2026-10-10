package com.gios.lightremote.net

/**
 * The phone's default network, cut down to the two things a reconnect cares about.
 *
 * Plain Kotlin on purpose, like the rule below it: [NetworkWatch] builds one of these from a
 * `Network` and its `NetworkCapabilities`, and everything after that is testable on the JVM.
 */
data class DefaultNetwork(
    /** `Network.getNetworkHandle()`. Two equal handles are one network announced twice. */
    val handle: Long,
    /**
     * Wi-Fi or Ethernet underneath it: a path a television on the local network can be reached
     * over. Cellular is not one, and neither is a VPN that is not riding on Wi-Fi.
     */
    val reachesLan: Boolean,
)

/**
 * When a change of default network is worth another connect attempt.
 *
 * ## What v1.28 did, and the case it missed
 *
 * v1.28 treated "no network, then a network" as the edge, and that is right for the drop it was
 * written for (light-reports#253: `ENETUNREACH`, a radio that was down). It misses the more common
 * way the radio comes back on this phone. When Wi-Fi goes away the default network usually does
 * not become *nothing*, it becomes *cellular*, so the watcher never saw "none" and never armed. The
 * reconnect attempts then go out over cellular to a 192.168 address and time out after five
 * seconds each: light-reports#639 failed five times from `48.227.213.209` and #360 from
 * `192.0.0.8`, neither of them an address on the television's network. And when Wi-Fi came back a
 * moment later and took over as the default, the watcher saw one network replace another, which
 * it did not count, so nothing tried again and the remote sat on Retry.
 *
 * ## The rule
 *
 * An edge is the default network becoming one that can reach the television: a different network
 * with Wi-Fi or Ethernet under it, or the same network gaining one (a VPN whose underlying network
 * moved from cellular to Wi-Fi). Nothing at all becoming Wi-Fi is still an edge, as before.
 * Nothing becoming *cellular* no longer is: an attempt over cellular cannot reach a television on
 * the LAN, and spending one there also spent the thirty-second floor that the real edge, Wi-Fi a
 * second later, then ran into.
 *
 * A television that is switched off still produces no edge and costs nothing, which is the
 * property the bounded ladder in the view model exists to protect.
 */
object NetworkEdge {

    fun isEdge(before: DefaultNetwork?, after: DefaultNetwork): Boolean {
        if (!after.reachesLan) return false
        if (before == null) return true
        return before.handle != after.handle || !before.reachesLan
    }
}
