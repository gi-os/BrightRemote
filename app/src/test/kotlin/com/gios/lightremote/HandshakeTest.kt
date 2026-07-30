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

    @Test
    fun `credentials parse rejects malformed input`() {
        assertNull(Credentials.parse("nope"))
        assertNull(Credentials.parse("aa:bb:cc"))
        assertNull(Credentials.parse("aa:bb:cc:d"))
        assertNotNull(Credentials.parse("aa:bb:cc:dd"))
    }
}
