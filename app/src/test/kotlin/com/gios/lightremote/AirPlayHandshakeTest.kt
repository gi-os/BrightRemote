package com.gios.lightremote

import com.gios.lightremote.airplay.AirPlayAuth
import com.gios.lightremote.airplay.MrpTunnel
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

                tunnel.close()
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
