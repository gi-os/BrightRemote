package com.gios.lightremote.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Tells the caller when the phone gets a network back that can reach the television, and nothing
 * else.
 *
 * ## Why an edge and not a timer
 *
 * A dropped Companion link is answered by three connect attempts 1.2 and 2.4 seconds apart and then
 * left to the user. That ladder is right for the failure it was written for — a television tearing
 * down its own session refuses pair-verify for a moment — and wrong for the other one. light-reports#253
 * is the wrong one written out in full: the drop, then
 * `ConnectException: ... ENETUNREACH (Network is unreachable)`. The phone had no network at all, so
 * all three attempts were spent inside four seconds against a radio that was down, and the app then
 * sat disconnected while the user was still holding it.
 *
 * The obvious repair is a longer ladder, and it is the wrong one. An earlier build of the view model
 * retried on every state change and ground away at a television that was simply switched off, which
 * is why the attempts are counted and bounded now. A timer cannot tell those two apart.
 *
 * An edge can. Wi-Fi coming back is the moment the reason for failing stopped being true, and it is
 * the only moment worth spending another attempt on. A television that is off produces no edge and
 * therefore costs nothing, which is exactly the property the bounded ladder was protecting.
 *
 * ## Which changes count
 *
 * Not only "nothing, then something". Losing Wi-Fi usually hands the default to cellular rather
 * than to nothing, and Wi-Fi coming back then *replaces* cellular — which the first version of this
 * watcher did not count, so the reconnect it existed for never happened. [NetworkEdge] holds the
 * rule and the reports behind it; this class only feeds it.
 *
 * ## Availability, not validation
 *
 * `NET_CAPABILITY_VALIDATED` and `NET_CAPABILITY_INTERNET` both mean *the internet is reachable*,
 * and neither is what this needs. The Apple TV is on the far side of the room, not the far side of
 * the internet — a Wi-Fi network with no route out, or one still behind a captive portal, reaches it
 * perfectly well. Waiting for validation would mean not reconnecting on exactly the networks where
 * the remote still works. So this reports the transport and lets the connect attempt be the thing
 * that finds out.
 */
class NetworkWatch(context: Context, private val onBack: () -> Unit) {

    private val manager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * The default network as this has last been told, or null for none.
     *
     * Seeded at [start] from what is already there, because `registerDefaultNetworkCallback`
     * delivers the current network the moment it is registered. Without the seed every start of
     * the app would look like a network that had just come back and would fire a reconnect on top
     * of the one the resume already does.
     */
    @Volatile
    private var current: DefaultNetwork? = null

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        val cm = manager ?: return
        if (callback != null) return
        current = runCatching {
            cm.activeNetwork?.let { network ->
                // Unknown capabilities are seeded as reachable: a seed that is wrong in that
                // direction costs nothing, while one wrong the other way would fire on the very
                // first callback, which is just the current network being announced.
                val caps = cm.getNetworkCapabilities(network)
                DefaultNetwork(network.networkHandle, caps?.let { reachesLan(it) } ?: true)
            }
        }.getOrNull()
        val cb = object : ConnectivityManager.NetworkCallback() {
            // Not onAvailable: the transport is what decides, and for a default-network callback
            // onAvailable is always followed straight away by this, with the capabilities in hand.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val after = DefaultNetwork(network.networkHandle, reachesLan(caps))
                val edge = NetworkEdge.isEdge(current, after)
                current = after
                if (edge) onBack()
            }

            // A default-network callback only hears onLost when nothing replaced the network.
            override fun onLost(network: Network) {
                if (current?.handle == network.networkHandle) current = null
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { callback = cb }
    }

    fun stop() {
        val cm = manager ?: return
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    private fun reachesLan(caps: NetworkCapabilities): Boolean =
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}
