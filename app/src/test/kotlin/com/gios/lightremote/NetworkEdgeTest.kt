package com.gios.lightremote

import com.gios.lightremote.net.DefaultNetwork
import com.gios.lightremote.net.NetworkEdge
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * When a change of default network buys another connect attempt.
 *
 * The case this was written for is the one the first version missed: Wi-Fi going away usually
 * hands the default to cellular, not to nothing, and Wi-Fi coming back then replaces cellular.
 * The old watcher only counted "nothing, then something", so it never fired, and the remote sat on
 * Retry with Wi-Fi already back (light-reports#639 dialled the television five times from a
 * cellular address).
 */
class NetworkEdgeTest {

    private val wifi = DefaultNetwork(handle = 100, reachesLan = true)
    private val otherWifi = DefaultNetwork(handle = 101, reachesLan = true)
    private val cellular = DefaultNetwork(handle = 200, reachesLan = false)

    @Test
    fun `Wi-Fi replacing cellular is an edge`() {
        assertTrue(NetworkEdge.isEdge(before = cellular, after = wifi))
    }

    @Test
    fun `Wi-Fi after no network at all is still an edge`() {
        // The v1.28 case, light-reports#253: ENETUNREACH, then the radio came back.
        assertTrue(NetworkEdge.isEdge(before = null, after = wifi))
    }

    @Test
    fun `the same network announced again is not`() {
        // registerDefaultNetworkCallback announces the current network the moment it is
        // registered, and every capability change announces it again.
        assertFalse(NetworkEdge.isEdge(before = wifi, after = wifi))
    }

    @Test
    fun `cellular is never an edge, whatever it replaced`() {
        // An attempt over cellular cannot reach a television on the LAN, and spending one there
        // also spent the floor the real edge, Wi-Fi a moment later, then ran into.
        assertFalse(NetworkEdge.isEdge(before = null, after = cellular))
        assertFalse(NetworkEdge.isEdge(before = wifi, after = cellular))
    }

    @Test
    fun `a different Wi-Fi network is an edge`() {
        assertTrue(NetworkEdge.isEdge(before = wifi, after = otherWifi))
    }

    @Test
    fun `a VPN whose underlying network moved to Wi-Fi is an edge`() {
        // Same network handle, the VPN's, but it now rides on something that reaches the LAN.
        val vpnOverCellular = DefaultNetwork(handle = 300, reachesLan = false)
        val vpnOverWifi = DefaultNetwork(handle = 300, reachesLan = true)
        assertTrue(NetworkEdge.isEdge(before = vpnOverCellular, after = vpnOverWifi))
    }
}
