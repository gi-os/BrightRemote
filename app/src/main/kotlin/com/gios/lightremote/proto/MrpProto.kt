package com.gios.lightremote.proto

import com.gios.lightremote.proto.ProtoBuf.ProtoMessage
import com.gios.lightremote.proto.ProtoBuf.Writer
import java.util.UUID

/**
 * The slice of MediaRemote (MRP) this app speaks, by field number.
 *
 * MRP is Apple's protocol for now-playing metadata and transport control. This remote drives
 * transport over Companion, so the *only* reason to speak MRP at all is the one thing Companion
 * will not give up: the title, artist, artwork and playback state of what is on screen. So this
 * is deliberately half a protocol — it writes the three messages that open a read-only
 * subscription and parses the two that carry now-playing, and treats every other message type
 * as noise to skip.
 *
 * All the numbers here are field numbers and message-type enum values from pyatv's `.proto`
 * files (`ProtocolMessage.proto`, `SetStateMessage.proto`, and the rest). They are the contract;
 * the names are just commentary.
 *
 * Strictly one-way and strictly optional: nothing in here can fail a Companion session, and a
 * malformed frame degrades to "no metadata" rather than throwing out of the parse.
 */
object Mrp {

    /** ProtocolMessage.Type enum values (the outer `type`, field 1). */
    object Type {
        const val SET_STATE = 4
        const val DEVICE_INFO = 15
        const val CLIENT_UPDATES_CONFIG = 16
        const val PLAYBACK_QUEUE_REQUEST = 32
        const val SET_CONNECTION_STATE = 38
        const val SET_NOW_PLAYING_CLIENT = 46
    }

    /** ProtocolMessage extension field numbers — where each inner message hangs off the outer. */
    private object Ext {
        const val SET_STATE = 9
        const val DEVICE_INFO = 20
        const val CLIENT_UPDATES_CONFIG = 21
        const val PLAYBACK_QUEUE_REQUEST = 37
        const val SET_CONNECTION_STATE = 42
        const val SET_NOW_PLAYING_CLIENT = 50
    }

    /** ProtocolMessage common fields. */
    private const val F_TYPE = 1
    private const val F_UNIQUE_IDENTIFIER = 85

    /** PlaybackState.Enum. */
    enum class PlaybackState(val wire: Int) {
        Unknown(0), Playing(1), Paused(2), Stopped(3), Interrupted(4), Seeking(5);

        companion object {
            fun of(wire: Int?): PlaybackState = entries.firstOrNull { it.wire == wire } ?: Unknown
        }
    }

    // ------------------------------------------------------------------ messages we send

    private fun envelope(type: Int): Writer =
        Writer().int(F_TYPE, type).string(F_UNIQUE_IDENTIFIER, UUID.randomUUID().toString().uppercase())

    /**
     * DEVICE_INFO_MESSAGE — always the first message on the data channel; tvOS answers nothing
     * until it arrives. The field set mirrors pyatv's `device_information`; [identifier] is the
     * pairing id the remote introduces itself as.
     */
    fun deviceInfo(name: String, identifier: String): ByteArray {
        val info = Writer()
            .string(1, identifier)              // uniqueIdentifier
            .string(2, name)                    // name (required)
            .string(3, "iPhone")                // localizedModelName
            .string(5, "com.apple.TVRemote")    // applicationBundleIdentifier
            .string(6, "344.28")                // applicationBundleVersion
            .int(7, 1)                          // protocolVersion
            .int(8, 108)                        // lastSupportedMessageType
            .bool(9, true)                      // supportsSystemPairing
            .bool(10, true)                     // allowsPairing
            .bool(13, true)                     // supportsACL
            .bool(14, true)                     // supportsSharedQueue
            .bool(15, true)                     // supportsExtendedMotion
            .enum(21, 1)                        // deviceClass = iPhone
            .int(22, 1)                         // logicalDeviceCount
        return envelope(Type.DEVICE_INFO).message(Ext.DEVICE_INFO, info).toByteArray()
    }

    /** SET_CONNECTION_STATE_MESSAGE with state = Connected(2); the first message after DEVICE_INFO. */
    fun setConnectionState(): ByteArray {
        val inner = Writer().enum(1, 2) // Connected
        return envelope(Type.SET_CONNECTION_STATE).message(Ext.SET_CONNECTION_STATE, inner).toByteArray()
    }

    /**
     * CLIENT_UPDATES_CONFIG_MESSAGE — subscribes to the pushes this remote wants. now-playing on,
     * artwork on; the rest ride along cheaply.
     */
    fun clientUpdatesConfig(): ByteArray {
        val inner = Writer()
            .bool(1, true)   // artworkUpdates
            .bool(2, true)   // nowPlayingUpdates
            .bool(3, true)   // volumeUpdates
            .bool(4, false)  // keyboardUpdates
            .bool(5, true)   // outputDeviceUpdates
        return envelope(Type.CLIENT_UPDATES_CONFIG).message(Ext.CLIENT_UPDATES_CONFIG, inner).toByteArray()
    }

    /**
     * PLAYBACK_QUEUE_REQUEST_MESSAGE — asks for the current item's artwork at a size. Optional:
     * artwork also arrives unbidden on the content item, so a device that ignores this costs
     * nothing.
     */
    fun playbackQueueRequest(location: Int, width: Int, height: Int): ByteArray {
        val inner = Writer()
            .int(1, location)                    // location
            .int(2, 1)                           // length
            .double(4, width.toDouble())         // artworkWidth
            .double(5, height.toDouble())        // artworkHeight
            .bool(13, true)                      // returnContentItemAssetsInUserCompletion
        return envelope(Type.PLAYBACK_QUEUE_REQUEST).message(Ext.PLAYBACK_QUEUE_REQUEST, inner).toByteArray()
    }

    // ------------------------------------------------------------------ messages we read

    /** A parsed inbound message, narrowed to what this remote acts on. */
    sealed class Inbound {
        /** SET_STATE_MESSAGE: any subset of now-playing metadata, timing, artwork and state. */
        data class State(
            val title: String?,
            val artist: String?,
            val album: String?,
            val duration: Double?,
            val elapsed: Double?,
            val rate: Double?,
            val playbackState: PlaybackState?,
            val artwork: ByteArray?,
            val artworkMimeType: String?,
        ) : Inbound()

        /** SET_NOW_PLAYING_CLIENT_MESSAGE: which app owns the screen, or none. */
        data class Client(val bundleIdentifier: String?, val displayName: String?) : Inbound()

        /** Anything else on the wire — skipped. */
        data object Other : Inbound()
    }

    /**
     * Parse one ProtocolMessage. Never throws: a frame it cannot make sense of returns
     * [Inbound.Other], which the accumulator ignores.
     */
    fun parse(message: ByteArray): Inbound = try {
        val root = ProtoBuf.decode(message)
        when (root.int(F_TYPE)) {
            Type.SET_STATE -> parseSetState(root)
            Type.SET_NOW_PLAYING_CLIENT -> parseNowPlayingClient(root)
            else -> Inbound.Other
        }
    } catch (e: ProtoBuf.FormatException) {
        Inbound.Other
    }

    private fun parseSetState(root: ProtoMessage): Inbound {
        val setState = root.message(Ext.SET_STATE) ?: return Inbound.Other
        val info = setState.message(1)                       // nowPlayingInfo
        val playbackState = setState.int(6)                  // playbackState

        var title = info?.string(9)
        var artist = info?.string(2)
        var album = info?.string(1)
        var duration = info?.realNumber(3)
        var elapsed = info?.realNumber(4)
        val rate = info?.realNumber(5)

        // The content item, if the queue came along: artwork lives here, and its metadata
        // fills anything nowPlayingInfo did not carry.
        var artwork: ByteArray? = null
        var artworkMime: String? = null
        val queue = setState.message(3)                      // playbackQueue
        if (queue != null) {
            val location = queue.int(1) ?: 0
            val items = queue.messages(2)                    // contentItems
            val item = items.getOrNull(location) ?: items.firstOrNull()
            if (item != null) {
                item.bytes(3)?.let { if (it.isNotEmpty()) artwork = it }   // artworkData
                val meta = item.message(2)                   // metadata
                if (meta != null) {
                    title = title ?: meta.string(1)
                    artist = artist ?: meta.string(7)        // trackArtistName
                    album = album ?: meta.string(6)          // albumName
                    duration = duration ?: meta.realNumber(14)
                    elapsed = elapsed ?: meta.realNumber(35)
                    artworkMime = meta.string(31)            // artworkMIMEType
                }
            }
        }

        return Inbound.State(
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            elapsed = elapsed,
            rate = rate,
            playbackState = playbackState?.let { PlaybackState.of(it) },
            artwork = artwork,
            artworkMimeType = artworkMime,
        )
    }

    private fun parseNowPlayingClient(root: ProtoMessage): Inbound {
        val msg = root.message(Ext.SET_NOW_PLAYING_CLIENT) ?: return Inbound.Other
        val client = msg.message(1)                          // client
        return Inbound.Client(
            bundleIdentifier = client?.string(2)?.takeIf { it.isNotEmpty() },
            displayName = client?.string(7)?.takeIf { it.isNotEmpty() },
        )
    }
}

/**
 * What is playing on the television, assembled from MRP over AirPlay.
 *
 * This is the MRP counterpart to Companion's [NowPlayingInfo] — richer (it has a title and
 * artwork, which Companion never sends), and the thing the MediaSession and the remote screen
 * both draw from. Position is a snapshot; the reader extrapolates from [rate] against a local
 * clock exactly as [NowPlayingInfo] does.
 */
data class MrpNowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    val appName: String?,
    val bundleIdentifier: String?,
    val playbackState: Mrp.PlaybackState,
    val elapsed: Double?,
    val duration: Double?,
    val rate: Double,
    val artwork: ByteArray?,
    val artworkMimeType: String?,
    val receivedAtNanos: Long = System.nanoTime(),
) {
    /** Something worth showing: a title or an artist, and not stopped. */
    val hasItem: Boolean
        get() = (title != null || artist != null) && playbackState != Mrp.PlaybackState.Stopped

    val isPlaying: Boolean get() = playbackState == Mrp.PlaybackState.Playing

    override fun equals(other: Any?): Boolean = other is MrpNowPlaying &&
        title == other.title && artist == other.artist && album == other.album &&
        appName == other.appName && bundleIdentifier == other.bundleIdentifier &&
        playbackState == other.playbackState && elapsed == other.elapsed &&
        duration == other.duration && rate == other.rate &&
        (artwork?.contentEquals(other.artwork) ?: (other.artwork == null)) &&
        artworkMimeType == other.artworkMimeType

    override fun hashCode(): Int {
        var h = title.hashCode()
        h = 31 * h + (artist?.hashCode() ?: 0)
        h = 31 * h + playbackState.hashCode()
        h = 31 * h + (artwork?.size ?: 0)
        return h
    }
}

/**
 * Folds a stream of MRP messages into the current now-playing snapshot.
 *
 * tvOS does not send one tidy record; it dribbles partial [Mrp.Inbound.State] updates — a
 * playback-state flip here, a fresh title there, artwork in its own message — and a
 * [Mrp.Inbound.Client] when the owning app changes or goes away. This merges them, keeping the
 * last value for anything an update omits, and clears artwork when the item itself changes so a
 * new song does not wear the last one's cover.
 *
 * [snapshot] is null when nothing is worth showing — the item went away, or playback stopped —
 * which is exactly the signal the MediaSession needs to deactivate.
 */
class MrpAccumulator {

    private var title: String? = null
    private var artist: String? = null
    private var album: String? = null
    private var duration: Double? = null
    private var elapsed: Double? = null
    private var rate: Double = 0.0
    private var state: Mrp.PlaybackState = Mrp.PlaybackState.Unknown
    private var artwork: ByteArray? = null
    private var artworkMime: String? = null
    private var appName: String? = null
    private var bundleId: String? = null
    private var receivedAtNanos: Long = System.nanoTime()

    /** Apply one parsed message. Returns the snapshot after applying it (may be null). */
    fun apply(inbound: Mrp.Inbound): MrpNowPlaying? {
        when (inbound) {
            is Mrp.Inbound.State -> applyState(inbound)
            is Mrp.Inbound.Client -> applyClient(inbound)
            Mrp.Inbound.Other -> Unit
        }
        return snapshot()
    }

    private fun applyState(s: Mrp.Inbound.State) {
        // A change of item — a new title arriving — retires the old artwork so it cannot linger
        // over the next track.
        val newTitle = s.title
        if (newTitle != null && newTitle != title) {
            artwork = null
            artworkMime = null
        }
        s.title?.let { title = it }
        s.artist?.let { artist = it }
        s.album?.let { album = it }
        s.duration?.let { duration = it }
        s.elapsed?.let { elapsed = it }
        s.rate?.let { rate = it }
        s.playbackState?.let { state = it }
        s.artwork?.let { artwork = it; artworkMime = s.artworkMimeType }
        receivedAtNanos = System.nanoTime()
    }

    private fun applyClient(c: Mrp.Inbound.Client) {
        if (c.bundleIdentifier == null) {
            // The owning app went away: nothing is playing. Reset to an empty item.
            clear()
        } else {
            bundleId = c.bundleIdentifier
            appName = c.displayName
        }
    }

    private fun clear() {
        title = null; artist = null; album = null
        duration = null; elapsed = null; rate = 0.0
        state = Mrp.PlaybackState.Stopped
        artwork = null; artworkMime = null
        // Keep bundleId/appName as-is; they describe the (now empty) owner and are harmless.
    }

    fun snapshot(): MrpNowPlaying? {
        val np = MrpNowPlaying(
            title = title, artist = artist, album = album,
            appName = appName, bundleIdentifier = bundleId,
            playbackState = state, elapsed = elapsed, duration = duration, rate = rate,
            artwork = artwork, artworkMimeType = artworkMime,
            receivedAtNanos = receivedAtNanos,
        )
        return if (np.hasItem) np else null
    }
}
