package com.gios.lightremote

import com.gios.lightremote.ui.isPlausibleIpv4
import com.gios.lightremote.ui.splitAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * What the typed-address keypad will accept.
 *
 * This is the escape hatch for a network whose multicast is broken, so it is reached by someone
 * who has already failed to find their television once. Accepting something unusable and then
 * failing at a socket five seconds later is the wrong place to find out — the Pair button should
 * simply not light up.
 */
class AddressTest {

    @Test
    fun `a plain address is accepted`() {
        assertTrue(isPlausibleIpv4("192.168.1.50"))
        assertTrue(isPlausibleIpv4("10.0.0.1"))
        assertTrue(isPlausibleIpv4("255.255.255.255"))
    }

    @Test
    fun `half-typed addresses are not`() {
        assertFalse(isPlausibleIpv4(""))
        assertFalse(isPlausibleIpv4("192"))
        assertFalse(isPlausibleIpv4("192.168"))
        assertFalse(isPlausibleIpv4("192.168.1"))
        assertFalse(isPlausibleIpv4("192.168.1."))
        assertFalse(isPlausibleIpv4("192.168.1.50.7"))
        assertFalse(isPlausibleIpv4(".168.1.50"))
    }

    @Test
    fun `an octet over 255 is not an address`() {
        assertFalse(isPlausibleIpv4("192.168.1.256"))
        assertFalse(isPlausibleIpv4("999.1.1.1"))
    }

    @Test
    fun `a port may be given and is split off`() {
        assertTrue(isPlausibleIpv4("192.168.1.50:49153"))
        assertEquals("192.168.1.50" to 49153, splitAddress("192.168.1.50:49153"))
    }

    @Test
    fun `no port means no port, not zero`() {
        assertEquals("192.168.1.50" to null, splitAddress("192.168.1.50"))
    }

    @Test
    fun `a colon with nothing usable after it is rejected`() {
        // Otherwise Pair lights up while the port is half typed and connects to the default one,
        // which is the wrong number in exactly the case this screen exists for.
        assertFalse(isPlausibleIpv4("192.168.1.50:"))
        assertFalse(isPlausibleIpv4("192.168.1.50:0"))
        assertFalse(isPlausibleIpv4("192.168.1.50:70000"))
        assertNull(splitAddress("192.168.1.50:").second)
    }
}
