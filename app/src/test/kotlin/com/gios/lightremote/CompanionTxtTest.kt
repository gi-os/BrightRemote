package com.gios.lightremote

import com.gios.lightremote.discovery.CompanionTxt
import com.gios.lightremote.discovery.DiscoveredDevice
import com.gios.lightremote.discovery.PairingSupport
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Reading `rpMd` and `rpFl` off a `_companion-link._tcp` record.
 *
 * The values below are the ones pyatv has catalogued from real devices
 * (`pyatv/protocols/companion/__init__.py`). The app's own masks, `0x40000000` and `0x200`, matched
 * none of them, so every device on the network was offered for pairing — including the ones that
 * close or ignore a pair-setup, which is what light-reports#676 and #590 show on the wire.
 */
class CompanionTxtTest {

    private fun support(model: String?, flags: String?) =
        CompanionTxt.pairingSupport(model, CompanionTxt.flags(flags))

    @Test
    fun `Apple TVs pyatv has seen are pairable`() {
        assertEquals(PairingSupport.Pairable, support("AppleTV6,2", "0x36782"))
        assertEquals(PairingSupport.Pairable, support("AppleTV14,1", "0x367A2"))
        // And they all carry the PIN-pairing bit, which no HomePod, Mac or iPad does.
        for (raw in listOf("0x36782", "0x367A2")) {
            assertTrue(CompanionTxt.flags(raw)!! and CompanionTxt.PIN_PAIRING_MASK != 0L, raw)
        }
    }

    @Test
    fun `a television restricted to its household is listed but not pairable`() {
        // pyatv: 0x627B6, "only devices in same home".
        assertEquals(PairingSupport.PairingOff, support("AppleTV11,1", "0x627B6"))
    }

    @Test
    fun `HomePods, Macs and iPads answer the search but are not televisions`() {
        assertEquals(PairingSupport.NotATelevision, support("AudioAccessory5,1", "0x627B2"))
        assertEquals(PairingSupport.NotATelevision, support("AudioAccessory1,1", "0x62792"))
        assertEquals(PairingSupport.NotATelevision, support("Macmini9,1", "0x20000"))
        assertEquals(PairingSupport.NotATelevision, support("iPad13,4", "0x30000"))
        for (raw in listOf("0x627B2", "0x62792", "0x20000", "0x30000")) {
            assertEquals(0L, CompanionTxt.flags(raw)!! and CompanionTxt.PIN_PAIRING_MASK, raw)
        }
    }

    @Test
    fun `no record at all stays tappable, as it always was`() {
        // Below API 34 NsdManager hands over no TXT record; refusing every device there would turn
        // a missing hint into a remote that cannot pair with anything.
        assertEquals(PairingSupport.Unknown, support(null, null))
        assertTrue(DiscoveredDevice(name = "Living Room", host = "10.0.0.2", port = 49153).pairable)
    }

    @Test
    fun `an Apple TV with no flags is unknown, and still tappable`() {
        assertEquals(PairingSupport.Unknown, support("AppleTV6,2", null))
    }

    @Test
    fun `keys are read whatever their case`() {
        // rapportd writes rpMd and rpFl; an exact lookup of "rpmd" never found the model.
        val attributes = mapOf(
            "rpMd" to "AppleTV14,1".toByteArray(),
            "rpFl" to "0x36782".toByteArray(),
            "rpHN" to null,
        )
        assertEquals("AppleTV14,1", CompanionTxt.value(attributes, "rpmd"))
        assertEquals("0x36782", CompanionTxt.value(attributes, "RPFL"))
        assertNull(CompanionTxt.value(attributes, "rpHN"))
        assertNull(CompanionTxt.value(attributes, "rpVr"))
    }

    @Test
    fun `flags parse with or without the prefix, and garbage is no flags`() {
        assertEquals(0x36782L, CompanionTxt.flags("0x36782"))
        assertEquals(0x36782L, CompanionTxt.flags("36782"))
        assertNull(CompanionTxt.flags("not hex"))
        assertNull(CompanionTxt.flags(null))
    }

    @Test
    fun `a row that cannot be tapped says why`() {
        val homePod = DiscoveredDevice(
            name = "Kitchen",
            host = "192.168.68.58",
            port = 49153,
            model = "AudioAccessory5,1",
            flags = 0x627B2L,
            support = PairingSupport.NotATelevision,
        )
        assertFalse(homePod.pairable)
        assertEquals("HomePod is not an Apple TV", homePod.foundLabel)

        val restricted = homePod.copy(model = "AppleTV11,1", flags = 0x627B6L, support = PairingSupport.PairingOff)
        assertFalse(restricted.pairable)
        assertEquals("Pairing is turned off on this TV", restricted.foundLabel)

        val tv = homePod.copy(model = "AppleTV14,1", flags = 0x367A2L, support = PairingSupport.Pairable)
        assertTrue(tv.pairable)
        assertEquals("Apple TV 4K (3rd gen)", tv.foundLabel)
    }
}
