package com.gios.lightremote.airplay

import com.gios.lightremote.companion.Trace
import com.gios.lightremote.proto.Mrp
import com.gios.lightremote.proto.MrpAccumulator
import com.gios.lightremote.proto.MrpNowPlaying
import kotlinx.coroutines.CoroutineScope

/**
 * The MRP-over-AirPlay tunnel: connect, subscribe, and turn the stream of protobufs into a
 * changing [MrpNowPlaying].
 *
 * This is the one piece the rest of the app talks to. It owns an [AirPlaySession], sends the
 * three messages that open a now-playing subscription, folds the pushes that come back, and
 * calls [onNowPlaying] whenever the picture changes. Artwork it does not already have it asks
 * for once per item, since that is the thing Gio wanted on the screen.
 *
 * Everything here is best-effort by contract. The Companion session is the remote; MRP is a
 * read-only luxury layered beside it, so [connect] may throw and the caller is expected to
 * shrug — no metadata, nothing disturbed.
 */
class MrpTunnel(
    private val host: String,
    private val deviceId: String,
    private val scope: CoroutineScope,
) {
    companion object {
        /** The AirPlay control port. Real devices advertise it; 7000 is the default. */
        const val DEFAULT_AIRPLAY_PORT = 7000

        private const val ARTWORK_WIDTH = 512
        private const val ARTWORK_HEIGHT = 512
    }

    private var session: AirPlaySession? = null
    private val accumulator = MrpAccumulator()
    private val lock = Any()
    private var lastItemKey: String? = null

    /** Fired on the data-reader thread whenever the now-playing snapshot changes. Null = away. */
    var onNowPlaying: ((MrpNowPlaying?) -> Unit)? = null

    var nowPlaying: MrpNowPlaying? = null
        private set

    suspend fun connect(port: Int = DEFAULT_AIRPLAY_PORT, auth: AirPlayAuth = AirPlayAuth.Transient) {
        // A second attempt (verify failed, caller now trying transient) must not leak the
        // half-open control socket of the first.
        runCatching { session?.close() }
        val s = AirPlaySession(host, port, deviceId, scope)
        s.onProtobuf = ::onProtobuf
        session = s
        s.connect(auth)

        // First DEVICE_INFO, then the connection state, then the update subscription — the
        // order tvOS expects on a fresh data channel.
        s.sendProtobuf(Mrp.deviceInfo(name = "Light Phone", identifier = deviceId))
        s.sendProtobuf(Mrp.setConnectionState())
        s.sendProtobuf(Mrp.clientUpdatesConfig())
        Trace.step("mrp: subscribed to now-playing")
    }

    private fun onProtobuf(message: ByteArray) {
        val inbound = Mrp.parse(message)
        val snapshot = synchronized(lock) { accumulator.apply(inbound) }
        nowPlaying = snapshot
        onNowPlaying?.invoke(snapshot)

        // A new item with no artwork yet: ask for it once. The answer arrives as a SET_STATE
        // carrying the content item, which the accumulator absorbs on its own.
        if (snapshot != null && snapshot.artwork == null) {
            val key = "${snapshot.title}|${snapshot.artist}"
            if (key != lastItemKey) {
                lastItemKey = key
                runCatching {
                    session?.sendProtobuf(Mrp.playbackQueueRequest(0, ARTWORK_WIDTH, ARTWORK_HEIGHT))
                }
            }
        } else if (snapshot == null) {
            lastItemKey = null
        }
    }

    fun close() {
        runCatching { session?.close() }
        session = null
        nowPlaying = null
    }
}
