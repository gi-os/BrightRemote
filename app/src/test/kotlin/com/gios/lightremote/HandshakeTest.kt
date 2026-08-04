package com.gios.lightremote

import com.gios.lightremote.companion.AuthenticationException
import com.gios.lightremote.companion.ClientIdentity
import com.gios.lightremote.companion.CompanionClient
import com.gios.lightremote.companion.Credentials
import com.gios.lightremote.companion.HidCommand
import com.gios.lightremote.companion.TouchPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end handshake against [FakeAppleTv] over a real loopback socket.
 *
 * This covers what the golden vectors cannot: frame types and their replies, which HKDF
 * label belongs to which direction, when the ChaCha counters begin, and the order the
 * connect sequence has to run in.
 */
class HandshakeTest {

    private val identity = ClientIdentity(deviceId = "AA:BB:CC:DD:EE:01")

    private fun <T> withClient(block: suspend (CompanionClient) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO)
        return try {
            runBlocking { block(CompanionClient(identity, scope)) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `pair-setup produces credentials the device recognises`() {
        FakeAppleTv().use { tv ->
            tv.start()
            val credentials = withClient { client ->
                val setup = client.startPairing("127.0.0.1", tv.port)
                client.finishPairing(setup, "1234")
            }
            tv.failure?.let { throw AssertionError("device rejected the handshake", it) }

            assertTrue(
                credentials.devicePublicKey.contentEquals(tv.devicePublicKey),
                "should have learned the device's long-term key",
            )
            assertTrue(credentials.deviceId.contentEquals(tv.deviceId))
            assertEquals(32, credentials.clientPrivateKey.size)
            assertTrue(credentials.clientId.isNotEmpty())
        }
    }

    @Test
    fun `credentials survive a round trip through storage`() {
        FakeAppleTv().use { tv ->
            tv.start()
            val credentials = withClient { client ->
                val setup = client.startPairing("127.0.0.1", tv.port)
                client.finishPairing(setup, "1234")
            }
            val restored = Credentials.parse(credentials.serialize())
            assertEquals(credentials, restored)
        }
    }

    /**
     * The full sequence: pair, drop the socket, reconnect with the saved credentials, and
     * run encrypted commands. If the session keys were swapped between directions the
     * device would fail to decrypt and this would hang until the request timed out.
     */
    @Test
    fun `pair then reconnect and send encrypted commands`() {
        FakeAppleTv().use { tv ->
            tv.start()
            val credentials = withClient { client ->
                val setup = client.startPairing("127.0.0.1", tv.port)
                client.finishPairing(setup, "1234")
            }

            withClient { client ->
                client.connect("127.0.0.1", tv.port, credentials)
                assertTrue(client.isConnected)

                client.press(HidCommand.Select)
                client.press(HidCommand.Home)
                client.touch(500, 500, TouchPhase.Press)
                val apps = client.appList()
                client.disconnect()

                assertEquals(2, apps.size)
                assertEquals("Netflix", apps.first { it.bundleId == "com.netflix.Netflix" }.name)
            }
            tv.failure?.let { throw AssertionError("device rejected the session", it) }

            val seen = synchronized(tv.requests) { tv.requests.toList() }
            // _systemInfo has to be first: the device withholds status events otherwise.
            assertEquals("_systemInfo", seen.first())
            assertTrue(seen.contains("_touchStart"), seen.toString())
            assertTrue(seen.contains("_sessionStart"), seen.toString())
            assertTrue(seen.contains("_hidC"), seen.toString())
            assertTrue(seen.contains("FetchLaunchableApplicationsEvent"), seen.toString())
            // _sessionStart must land before any button press, or presses do nothing.
            assertTrue(
                seen.indexOf("_sessionStart") < seen.indexOf("_hidC"),
                "session must be started before buttons are sent: $seen",
            )
        }
    }

    /**
     * A dozen requests started at once, which is not a stress test — it is Tuesday.
     *
     * Every command in the view model runs in its own coroutine, so anything the user does
     * quickly overlaps: the wheel steps every 110 ms against round trips that can take longer
     * than that, and volume, power and the app list all arrive on their own.
     *
     * The failure this guards is not a dropped request, it is a dead link. The output cipher is
     * a counter, and if a frame is sealed outside the lock that serialises the writes, two
     * senders can take nonces n and n+1 and then reach the socket in the other order. The
     * device's stream cipher cannot resynchronise from that, so the whole connection ends —
     * which is why [FakeAppleTv.failure] is the assertion that matters here.
     */
    @Test
    fun `many overlapping requests do not desynchronise the cipher`() {
        FakeAppleTv().use { tv ->
            tv.start()
            val credentials = withClient { client ->
                val setup = client.startPairing("127.0.0.1", tv.port)
                client.finishPairing(setup, "1234")
            }

            withClient { client ->
                client.connect("127.0.0.1", tv.port, credentials)
                val before = synchronized(tv.requests) { tv.requests.size }
                coroutineScope {
                    repeat(4) { launch { client.press(HidCommand.Up) } }
                    repeat(4) { launch { client.refreshPowerState() } }
                    repeat(4) { launch { client.appList() } }
                }
                assertTrue(client.isConnected, "the link died while commands overlapped")
                client.disconnect()

                val after = synchronized(tv.requests) { tv.requests.toList() }.drop(before)
                // Eight button frames — a press is a DOWN and an UP — plus the other eight.
                assertEquals(8, after.count { it == "_hidC" }, after.toString())
                assertEquals(4, after.count { it == "FetchAttentionState" }, after.toString())
                assertEquals(
                    4,
                    after.count { it == "FetchLaunchableApplicationsEvent" },
                    after.toString(),
                )
            }
            tv.failure?.let { throw AssertionError("device could not read the stream", it) }
        }
    }

    /**
     * A press is a DOWN and an UP, and they mean nothing apart.
     *
     * Two presses that overlap used to put DOWN, DOWN, UP, UP on the wire, because each half is
     * a request that suspends waiting for its answer. The television reads the second DOWN
     * arriving under the first as a key repeat and the focus travels further than the two rows
     * asked for — the wheel "jumping around". The halves have to stay paired.
     */
    @Test
    fun `overlapping presses still arrive as pairs`() {
        FakeAppleTv().use { tv ->
            tv.start()
            val credentials = withClient { client ->
                val setup = client.startPairing("127.0.0.1", tv.port)
                client.finishPairing(setup, "1234")
            }

            withClient { client ->
                client.connect("127.0.0.1", tv.port, credentials)
                synchronized(tv.hidStates) { tv.hidStates.clear() }
                coroutineScope {
                    repeat(8) { launch { client.press(HidCommand.Down) } }
                }
                client.disconnect()
            }
            tv.failure?.let { throw AssertionError("device could not read the stream", it) }

            val states = synchronized(tv.hidStates) { tv.hidStates.toList() }
            assertEquals(16, states.size, states.toString())
            // Strictly alternating from DOWN: any interleaving shows up as two of a kind.
            assertEquals(List(8) { listOf(1L, 2L) }.flatten(), states, states.toString())
        }
    }

    @Test
    fun `power state is read from the device`() {
        FakeAppleTv().use { tv ->
            tv.start()
            val credentials = withClient { client ->
                val setup = client.startPairing("127.0.0.1", tv.port)
                client.finishPairing(setup, "1234")
            }
            withClient { client ->
                client.connect("127.0.0.1", tv.port, credentials)
                assertEquals(
                    com.gios.lightremote.companion.PowerState.On,
                    client.powerState,
                )
                client.refreshVolume()
                assertEquals(0.25, client.volume)
                client.disconnect()
            }
        }
    }

    /** A tvOS version with no handler for a command must not break the connection. */
    @Test
    fun `an unsupported command is tolerated during connect`() {
        FakeAppleTv().use { tv ->
            tv.start()
            val credentials = withClient { client ->
                val setup = client.startPairing("127.0.0.1", tv.port)
                client.finishPairing(setup, "1234")
            }
            withClient { client ->
                // TVRCSessionStart answers "No request handler" in the fake; connect() has
                // to shrug that off rather than propagate it.
                client.connect("127.0.0.1", tv.port, credentials)
                assertTrue(client.isConnected)
                client.disconnect()
            }
        }
    }

    @Test
    fun `a rejected proof surfaces as an authentication error`() {
        FakeAppleTv(rejectProof = true).use { tv ->
            tv.start()
            val error = assertFailsWith<AuthenticationException> {
                withClient { client ->
                    val setup = client.startPairing("127.0.0.1", tv.port)
                    client.finishPairing(setup, "9999")
                }
            }
            assertTrue(error.message!!.contains("PIN"), error.message!!)
        }
    }

    @Test
    fun `a wrong pin does not yield credentials`() {
        // The fake computes its verifier from "1234", so a different PIN produces a
        // different session key and the proof check fails on the device side.
        FakeAppleTv(pin = "1234").use { tv ->
            tv.start()
            assertFailsWith<Exception> {
                withClient { client ->
                    val setup = client.startPairing("127.0.0.1", tv.port)
                    client.finishPairing(setup, "4321")
                }
            }
        }
    }

    @Test
    fun `verify fails against a device that does not know us`() {
        FakeAppleTv().use { tv ->
            tv.start()
            // Credentials for a controller the device has never seen: the signature over the
            // ephemeral keys will not match the long-term key we claim.
            val bogus = Credentials(
                devicePublicKey = ByteArray(32) { 9 },
                clientPrivateKey = ByteArray(32) { 3 },
                deviceId = tv.deviceId,
                clientId = "nobody".toByteArray(),
            )
            assertFailsWith<Exception> {
                withClient { client -> client.connect("127.0.0.1", tv.port, bogus) }
            }
        }
    }

    @Test
    fun `commands before connecting fail cleanly`() {
        withClient { client ->
            assertFailsWith<Exception> { client.press(HidCommand.Up) }
            assertNull(client.isConnected.takeIf { it })
        }
    }

    /**
     * Regression: pairing frames were going out without `_x`, one entry short of what pyatv
     * sends. A real Apple TV tolerates it, but "matches the reference client byte for byte"
     * is the only claim this code can actually make, so hold it to that.
     */
    @Test
    fun `every pairing frame carries a transaction id`() {
        FakeAppleTv().use { tv ->
            tv.start()
            val credentials = withClient { client ->
                val setup = client.startPairing("127.0.0.1", tv.port)
                client.finishPairing(setup, "1234")
            }
            withClient { client ->
                client.connect("127.0.0.1", tv.port, credentials)
                client.disconnect()
            }

            val frames = synchronized(tv.authFrames) { tv.authFrames.toList() }
            // Three pair-setup frames plus two pair-verify frames.
            assertEquals(5, frames.size, "unexpected pairing frame count")
            frames.forEachIndexed { index, frame ->
                assertNotNull(frame["_x"], "pairing frame $index has no _x: ${frame.keys}")
            }
            // The pair-setup frames also carry the password type, verify frames the auth type.
            assertEquals(1L, frames[0]["_pwTy"])
            assertEquals(4L, frames[3]["_auTy"])
        }
    }

    /**
     * Regression: `_idsID` was being sent as the client's own device id instead of the
     * pairing identifier from the credentials. The device matches that field against its
     * paired-controller list, so the handshake completed and then the very first request was
     * rejected — which presents as "it paired but will not connect".
     */
    @Test
    fun `system info identifies us by our pairing id`() {
        FakeAppleTv().use { tv ->
            tv.start()
            val credentials = withClient { client ->
                val setup = client.startPairing("127.0.0.1", tv.port)
                client.finishPairing(setup, "1234")
            }
            withClient { client ->
                client.connect("127.0.0.1", tv.port, credentials)
                client.disconnect()
            }

            val info = assertNotNull(tv.systemInfo, "no _systemInfo request arrived")
            val idsId = info["_idsID"] as? ByteArray
            assertNotNull(idsId, "_idsID missing or not bytes: ${info["_idsID"]}")
            assertTrue(
                idsId.contentEquals(credentials.clientId),
                "_idsID should be the pairing identifier, got ${String(idsId)}",
            )
            assertEquals(identity.deviceId, info["_pubID"])
            assertEquals(identity.plainId, info["_i"])
        }
    }

    /** A connection that comes up should report no survivable failures against the fake. */
    @Test
    fun `a healthy connection records no warnings`() {
        FakeAppleTv().use { tv ->
            tv.start()
            val credentials = withClient { client ->
                val setup = client.startPairing("127.0.0.1", tv.port)
                client.finishPairing(setup, "1234")
            }
            withClient { client ->
                client.connect("127.0.0.1", tv.port, credentials)
                val warnings = client.connectWarnings.toList()
                client.disconnect()
                // TVRCSessionStart is answered with "no request handler" by the fake, and
                // that one is swallowed inside the step, so it should not show up here.
                assertTrue(warnings.isEmpty(), "unexpected connect warnings: $warnings")
            }
        }
    }

    @Test
    fun `credentials parse rejects malformed input`() {
        assertNull(Credentials.parse("nope"))
        assertNull(Credentials.parse("aa:bb:cc"))
        assertNull(Credentials.parse("aa:bb:cc:d"))
        assertNotNull(Credentials.parse("aa:bb:cc:dd"))
    }
}
