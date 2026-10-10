package com.gios.lightremote

import com.gios.lightremote.ui.LinkRules
import com.gios.lightremote.ui.NetworkRetry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The view model's reconnect decisions, without the view model.
 *
 * Both rules here are ones whose failure looked, from the sofa, exactly like the Apple TV
 * dropping the connection: a network edge swallowed by a ladder that was already doomed, and a
 * healthy link thrown away and redialled because the activity was created again.
 */
class LinkRulesTest {

    private fun edge(
        hasTelevision: Boolean = true,
        pairing: Boolean = false,
        connected: Boolean = false,
        attemptInFlight: Boolean = false,
        sinceLastRetryMs: Long = Long.MAX_VALUE,
    ) = LinkRules.onNetworkEdge(hasTelevision, pairing, connected, attemptInFlight, sinceLastRetryMs)

    @Test
    fun `disconnected and idle, Wi-Fi coming back buys one ladder`() {
        assertEquals(NetworkRetry.Connect, edge())
    }

    @Test
    fun `a ladder in flight is started again rather than left to finish failing`() {
        // light-reports#661, #628, #591: the attempts after a drop went down a network that could
        // not reach the television, and the edge that would have fixed it arrived mid-ladder.
        assertEquals(NetworkRetry.Restart, edge(attemptInFlight = true))
    }

    @Test
    fun `the floor holds for restarts as well as connects`() {
        val inside = LinkRules.NETWORK_RETRY_FLOOR_MS - 1
        assertEquals(NetworkRetry.Ignore, edge(sinceLastRetryMs = inside))
        assertEquals(NetworkRetry.Ignore, edge(attemptInFlight = true, sinceLastRetryMs = inside))
        assertEquals(
            NetworkRetry.Connect,
            edge(sinceLastRetryMs = LinkRules.NETWORK_RETRY_FLOOR_MS),
        )
    }

    @Test
    fun `nothing happens while connected, without a television, or during a pairing`() {
        assertEquals(NetworkRetry.Ignore, edge(connected = true))
        assertEquals(NetworkRetry.Ignore, edge(hasTelevision = false))
        // A reconnect would take the client's one socket, which is the pairing's.
        assertEquals(NetworkRetry.Ignore, edge(pairing = true))
        assertEquals(NetworkRetry.Ignore, edge(pairing = true, attemptInFlight = true))
    }

    @Test
    fun `a flapping network buys at most one ladder per floor`() {
        // Simulate the edges a flapping Wi-Fi produces, ten seconds apart, against a clock.
        var lastRetryAt = 0L
        var ladders = 0
        var inFlight = false
        for (now in listOf(60_000L, 70_000L, 80_000L, 89_999L, 90_000L)) {
            when (edge(attemptInFlight = inFlight, sinceLastRetryMs = now - lastRetryAt)) {
                NetworkRetry.Connect, NetworkRetry.Restart -> {
                    lastRetryAt = now
                    ladders++
                    inFlight = true
                }
                NetworkRetry.Ignore -> Unit
            }
        }
        assertEquals(2, ladders, "edges at 60s and 90s, everything between them inside the floor")
    }

    @Test
    fun `already on the television means a healthy link to that same set`() {
        assertTrue(LinkRules.alreadyOn(sameTelevision = true, connected = true, socketOpen = true))
        // The activity created again around a live link: nothing to do.
        assertFalse(LinkRules.alreadyOn(sameTelevision = false, connected = true, socketOpen = true))
        assertFalse(LinkRules.alreadyOn(sameTelevision = true, connected = false, socketOpen = true))
        // The state says connected but the socket has gone: that one is worth reconnecting.
        assertFalse(LinkRules.alreadyOn(sameTelevision = true, connected = true, socketOpen = false))
    }
}
