package com.gios.lightremote

import com.gios.lightremote.airplay.AirPlayAuth
import com.gios.lightremote.airplay.AirPlayPinSetup
import com.gios.lightremote.airplay.MrpTunnel
import com.gios.lightremote.companion.AuthenticationException
import com.gios.lightremote.proto.MrpNowPlaying
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end MRP over AirPlay against [FakeAirPlayReceiver] on a real loopback socket.
 *
 * This is the merge gate for the AirPlay tunnel — the counterpart to [HandshakeTest] for
 * Companion. It exercises the whole chain the golden vectors cannot: transient pairing deriving
 * a shared session key both ways, the Control/DataStream key derivation and its direction, the
 * `/setup` plist exchange, the data-stream framing and its plist envelope, and the protobuf
 * SET_STATE coming out as real now-playing metadata.
 */
class AirPlayHandshakeTest {

    private fun <T> withScope(block: suspend (CoroutineScope) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO)
        return try {
            runBlocking { block(scope) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `transient pairing tunnels now-playing metadata end to end`() {
        FakeAirPlayReceiver().use { receiver ->
            receiver.start()
            withScope { scope ->
                val tunnel = MrpTunnel(host = "127.0.0.1", deviceId = "AA:BB:CC:DD:EE:01", scope = scope)
                val playing = CompletableDeferred<MrpNowPlaying>()
                tunnel.onNowPlaying = { np -> if (np != null && !playing.isCompleted) playing.complete(np) }

                tunnel.connect(port = receiver.port, auth = AirPlayAuth.Transient)

                val np = withTimeout(8_000) { playing.await() }
                receiver.failure?.let { throw AssertionError("receiver rejected the session", it) }

                assertEquals(receiver.pushedTitle, np.title)
                assertEquals(receiver.pushedArtist, np.artist)
                assertEquals(receiver.pushedAlbum, np.album)
                assertEquals(receiver.pushedDuration, np.duration)
                assertEquals(receiver.pushedElapsed, np.elapsed)
                assertTrue(np.isPlaying, "the pushed state was Playing")
                assertTrue(np.hasItem)

                // The regression behind v1.25: /pair-pin-start is the request that makes a
                // real Apple TV draw a pairing code on screen, and the silent transient
                // probe used to open with it. No automatic connect path may ever send it.
                assertTrue(
                    receiver.requests.none { it.contains("/pair-pin-start") },
                    "transient pairing must never ask the TV to display a code",
                )

                tunnel.close()
            }
        }
    }

    @Test
    fun `PIN pair-setup stores credentials and the next connect pair-verifies with them`() {
        FakeAirPlayReceiver(pin = "5309").use { receiver ->
            receiver.start()
            withScope { scope ->
                // The interactive pairing: begin() is the moment the TV shows its code...
                val setup = AirPlayPinSetup(host = "127.0.0.1", port = receiver.port)
                setup.begin()
                assertTrue(
                    receiver.requests.any { it.contains("/pair-pin-start") },
                    "beginning an interactive pairing is exactly when the TV draws its code",
                )
                // ...and complete() turns the typed code into long-term credentials.
                val credentials = setup.complete("5309", displayName = "Light Phone")
                receiver.failure?.let { throw AssertionError("receiver rejected pair-setup", it) }
                assertNotNull(receiver.pairedClientLtpk, "the accessory stored our long-term key")
                assertTrue(
                    credentials.devicePublicKey.isNotEmpty() && credentials.clientPrivateKey.isNotEmpty(),
                    "credentials carry both halves",
                )

                // A later connect uses pair-verify with those credentials — and only that.
                val before = receiver.requests.size
                val tunnel = MrpTunnel(host = "127.0.0.1", deviceId = "AA:BB:CC:DD:EE:03", scope = scope)
                val playing = CompletableDeferred<MrpNowPlaying>()
                tunnel.onNowPlaying = { np -> if (np != null && !playing.isCompleted) playing.complete(np) }
                tunnel.connect(port = receiver.port, auth = AirPlayAuth.WithCredentials(credentials))

                val np = withTimeout(8_000) { playing.await() }
                receiver.failure?.let { throw AssertionError("receiver rejected pair-verify", it) }
                assertEquals(receiver.pushedTitle, np.title)
                assertEquals(receiver.pushedArtist, np.artist)

                val later = receiver.requests.drop(before)
                assertTrue(later.any { it.contains("/pair-verify") }, "the reconnect pair-verified")
                assertTrue(
                    later.none { it.contains("/pair-setup") || it.contains("/pair-pin-start") },
                    "a device with stored credentials never pairs again: got $later",
                )

                tunnel.close()
            }
        }
    }

    @Test
    fun `refused transient with nothing stored means no pair-setup and no code on the TV`() {
        // A real Apple TV: transient pairing refused (it is HomePod-only), nothing stored.
        // The rule under test is bug 1 from the field: the tunnel must fail silently — it
        // must NOT fall back to PIN pair-setup, and above all it must never send
        // /pair-pin-start, which is what makes tvOS draw a pairing code uninvited.
        FakeAirPlayReceiver(supportsTransient = false).use { receiver ->
            receiver.start()
            withScope { scope ->
                val tunnel = MrpTunnel(host = "127.0.0.1", deviceId = "AA:BB:CC:DD:EE:04", scope = scope)
                val result = runCatching { tunnel.connect(port = receiver.port, auth = AirPlayAuth.Transient) }

                assertTrue(result.isFailure, "a refused transient pairing fails the connect")
                assertTrue(
                    result.exceptionOrNull() is AuthenticationException,
                    "the refusal surfaces as an authentication failure, not a crash",
                )
                assertTrue(
                    receiver.requests.none { it.contains("/pair-pin-start") },
                    "nothing on the automatic path may put a code on the television",
                )
                assertTrue(
                    receiver.requests.none { it.contains("/pair-setup") && it.contains("hkp=3") },
                    "no PIN pair-setup may be attempted without the user asking",
                )
                assertTrue(
                    receiver.requests.filter { it.contains("/pair-setup") }.all { it.contains("hkp=4") },
                    "only the transient form of pair-setup was tried: ${receiver.requests}",
                )
                tunnel.close()
            }
        }
    }

    @Test
    fun `a wrong PIN fails cleanly and pairing succeeds on a fresh exchange`() {
        FakeAirPlayReceiver(pin = "4321").use { receiver ->
            receiver.start()
            withScope {
                val setup = AirPlayPinSetup(host = "127.0.0.1", port = receiver.port)
                setup.begin()
                val wrong = runCatching { setup.complete("0000", displayName = null) }

                assertTrue(wrong.isFailure, "a wrong code must not yield credentials")
                val error = wrong.exceptionOrNull()
                assertTrue(error is AuthenticationException, "wrong code is an authentication error")
                assertTrue(
                    error!!.message!!.contains("PIN") || error.message!!.contains("code"),
                    "the error names the code as the problem: ${error.message}",
                )
                assertNull(receiver.pairedClientLtpk, "nothing may be stored on either side")

                // HAP tears the exchange down after a failed proof: the retry is a fresh
                // pair-setup — and a fresh code on the screen — not a resumed one.
                setup.begin()
                val credentials = setup.complete("4321", displayName = "Light Phone")
                receiver.failure?.let { throw AssertionError("receiver rejected the retry", it) }
                assertNotNull(receiver.pairedClientLtpk)
                assertTrue(credentials.devicePublicKey.isNotEmpty())
            }
        }
    }

    @Test
    fun `a closed AirPlay port degrades to no metadata without throwing out of the caller`() {
        // Nothing listening on this port: connect must fail, and the caller (the view model)
        // treats that as "no metadata", never a disturbance to the Companion session.
        withScope { scope ->
            val tunnel = MrpTunnel(host = "127.0.0.1", deviceId = "AA:BB:CC:DD:EE:02", scope = scope)
            val result = runCatching { tunnel.connect(port = 1, auth = AirPlayAuth.Transient) }
            assertTrue(result.isFailure, "connecting to a dead port should fail")
            assertNotNull(result.exceptionOrNull())
            tunnel.close()
        }
    }
}
