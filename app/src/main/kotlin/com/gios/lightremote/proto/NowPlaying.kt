package com.gios.lightremote.proto

/**
 * What is playing on the television right now, from the Companion link's `NowPlayingInfo`
 * event (tvOS 18 and later).
 *
 * The event's `_c.NowPlayingInfoKey` is an NSKeyedArchiver graph of a `TVRCNowPlayingInfo`
 * (the same shape the Apple TV Remote app uses for its scrub bar). The fields that matter
 * here live one level down on its `metadata` (`TVRCNowPlayingMetadata`): `timeOffset` is the
 * position in seconds and `duration` is the total in seconds. `playbackRate` rides on the
 * outer object so the client can tell playing (1.0) from paused (0.0) and keep the bar
 * moving between pushes.
 *
 * tvOS pushes this packet on state changes — play, pause, skip, title change — and the Apple
 * client extrapolates the position in between from the rate. This remote does the same: the
 * view model advances [position] against a local clock while [rate] is non-zero, so the bar
 * walks forward on its own instead of jumping once a second.
 */
data class NowPlayingInfo(
    /** Seconds into the item as reported by the TV. */
    val position: Double,
    /** Total length in seconds. */
    val duration: Double,
    /** Playback rate: 1.0 playing, 0.0 paused, 2.0 double-speed and so on. */
    val rate: Double = 1.0,
    /** When the packet arrived, so the caller can extrapolate from here. */
    val receivedAtNanos: Long = System.nanoTime(),
) {
    /** Fraction through the item, 0..1, or 0 when the duration is unknown. */
    val fraction: Double
        get() = if (duration > 0.0) (position / duration).coerceIn(0.0, 1.0) else 0.0

    /**
     * Where playback *would* be [elapsedNanos] after this packet arrived, given the rate.
     * Paused or rate-less items do not move. Position never walks past the end or before the
     * start — that keeps the bar from slamming to full on the last reported packet.
     */
    fun extrapolate(elapsedNanos: Long): Double {
        val movement = rate * (elapsedNanos / 1_000_000_000.0)
        return (position + movement).coerceIn(0.0, duration.coerceAtLeast(0.0))
    }
}

object NowPlayingPayloads {

    /**
     * Pull the position, duration and rate out of a `NowPlayingInfoKey` archive.
     *
     * The archive is walked the same way [RtiPayloads.readSessionState] walks a `_tiD`
     * archive: [Uid]s are resolved against `$objects` as they are met, instead of running a
     * general unarchiver over a graph we only need three leaves out of.
     *
     * Returns null when the graph is not a `TVRCNowPlayingInfo` we can follow, or when it has
     * no duration to measure progress against — the TV sends a stripped-down packet with only
     * `playbackRate` on some title changes, and there is nothing to draw a bar for then.
     */
    fun readNowPlaying(archive: ByteArray): NowPlayingInfo? {
        val root = BPlist.read(archive) as? Map<*, *> ?: return null
        val objects = root["\$objects"] as? List<*> ?: return null
        val top = root["\$top"] as? Map<*, *> ?: return null

        fun resolve(start: Any?, path: List<String>): Any? {
            var element: Any? = start
            for (key in path) {
                if (element is Uid) element = objects.getOrNull(element.value)
                val map = element as? Map<*, *> ?: return null
                element = map[key] ?: return null
            }
            if (element is Uid) element = objects.getOrNull(element.value)
            return element
        }

        // A scalar leaf is a Uid in the archive — `timeOffset` points at the value rather
        // than carrying it — so dereference any Uids before reading the number.
        fun number(value: Any?): Double? {
            var element: Any? = value
            while (element is Uid) element = objects.getOrNull(element.value)
            return when (element) {
                is Number -> element.toDouble()
                else -> null
            }
        }

        val info = resolve(top, listOf("root")) as? Map<*, *> ?: return null
        val metadata = resolve(info, listOf("metadata")) as? Map<*, *> ?: return null
        val position = number(metadata["timeOffset"]) ?: return null
        val duration = number(metadata["duration"]) ?: return null
        val rate = number(info["playbackRate"]) ?: 1.0
        return NowPlayingInfo(position = position, duration = duration, rate = rate)
    }
}
