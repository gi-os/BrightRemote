package com.gios.lightremote.watched

/**
 * Viewing sessions for the Notebook journal, assembled from the MRP now-playing stream.
 *
 * The tunnel already parses every SET_STATE the television pushes; this watches those same
 * snapshots go by and turns them into "watched Severance for 47 minutes" — nothing is sent,
 * requested or subscribed beyond what the remote does anyway.
 *
 * The rules, pinned by the journal contract:
 *
 *  - A session is contiguous playback of **one title**. A different title closes the session
 *    and opens the next one at the moment of the change.
 *  - A pause shorter than [MAX_PAUSE_MS] stays inside the session — pausing for a kettle is
 *    not two viewings — but the paused time never counts toward [WatchedSession.durationMin],
 *    which is actual playing time only.
 *  - A gap longer than [MAX_PAUSE_MS] closes the session at the moment playback stopped, so
 *    an evening that ends on the pause button does not smear into the night.
 *  - Anything under [MIN_SESSION_MS] of actual playing is channel-surfing, not viewing, and
 *    is dropped.
 *
 * Pure Kotlin, clock passed in per call: every rule above is exercised by unit tests over a
 * synthetic clock, because "45 seconds of Netflix at 23:59" is not a case to discover on the
 * phone.
 */
data class WatchedSession(
    /** When playback of this title began, epoch millis. */
    val startAt: Long,
    /** When the session closed — the pause/stop/change that ended it, epoch millis. */
    val endAt: Long,
    val title: String,
    /** Artist, show or app name — whatever the stream offered. Empty when it offered nothing. */
    val subtitle: String,
    /** Actual playing time in minutes, pauses excluded. */
    val durationMin: Int,
)

class WatchedAssembler {

    private class Open(val title: String, var subtitle: String, val startAt: Long) {
        /** Completed playing time, excluding the segment currently running. */
        var playedMs: Long = 0L

        /** When the running play segment began, or null while paused. */
        var segmentStart: Long? = startAt

        /** When playback stopped, while paused. Meaningless while playing. */
        var pausedAt: Long = startAt

        fun pause(now: Long) {
            segmentStart?.let { playedMs += now - it }
            segmentStart = null
            pausedAt = now
        }

        /** Total playing time as of [now]. */
        fun playedAt(now: Long): Long = playedMs + (segmentStart?.let { now - it } ?: 0L)

        /** The honest end: now if playing, else the moment playback stopped. */
        fun endAt(now: Long): Long = if (segmentStart != null) now else pausedAt
    }

    private var open: Open? = null

    /**
     * Playback of [title] observed at [now]. Returns a session that this observation closed
     * and that qualified, or null.
     */
    fun playing(title: String, subtitle: String, now: Long): WatchedSession? {
        val current = open
        var closed: WatchedSession? = null
        when {
            current == null -> open = Open(title, subtitle, now)
            current.title != title -> {
                // A new title is the end of the old one, at this exact moment if it was
                // still playing, or back when it paused if not.
                closed = seal(current, current.endAt(now))
                open = Open(title, subtitle, now)
            }
            current.segmentStart == null -> {
                if (now - current.pausedAt > MAX_PAUSE_MS) {
                    // Same title, but the pause outlived a kettle: two sittings.
                    closed = seal(current, current.pausedAt)
                    open = Open(title, subtitle, now)
                } else {
                    current.segmentStart = now
                    if (subtitle.isNotEmpty()) current.subtitle = subtitle
                }
            }
            else -> if (subtitle.isNotEmpty()) current.subtitle = subtitle
        }
        return closed
    }

    /**
     * Nothing is playing at [now] — paused, stopped, or the item went away entirely.
     *
     * A pause only marks time; the session stays open so a resume within [MAX_PAUSE_MS]
     * continues it. But once the gap has outlived that window the session closes right here,
     * without waiting for the next play: the provider must not need a future event to stop
     * counting an evening that simply ended.
     */
    fun notPlaying(now: Long): WatchedSession? {
        val current = open ?: return null
        if (current.segmentStart != null) {
            current.pause(now)
            return null
        }
        if (now - current.pausedAt > MAX_PAUSE_MS) {
            open = null
            return seal(current, current.pausedAt)
        }
        return null
    }

    /**
     * Close whatever is open, now. Called on disconnect, on teardown, whenever the observer
     * itself is going away — the rule that keeps a crash or a dropped link from leaving a
     * phantom session that "watched" until someone reopened the app.
     */
    fun flush(now: Long): WatchedSession? {
        val current = open ?: return null
        open = null
        if (current.segmentStart != null) current.pause(now)
        return seal(current, current.pausedAt)
    }

    private fun seal(session: Open, endAt: Long): WatchedSession? {
        if (session.segmentStart != null) session.pause(endAt)
        if (session.playedMs < MIN_SESSION_MS) return null
        return WatchedSession(
            startAt = session.startAt,
            endAt = endAt,
            title = session.title,
            subtitle = session.subtitle,
            durationMin = (session.playedMs / 60_000L).toInt(),
        )
    }

    companion object {
        /** A pause longer than this is two viewings; shorter is one, minus the pause. */
        const val MAX_PAUSE_MS = 5 * 60_000L

        /** Playing time under this is surfing, not a session. */
        const val MIN_SESSION_MS = 2 * 60_000L
    }
}

/**
 * The stored form: one session per line, fields tab-separated, titles escaped. Chosen over
 * JSON because it round-trips in pure Kotlin — org.json is an Android stub on the JVM, and
 * this way the pruning and parsing rules sit in the same unit tests as the assembler.
 */
object WatchedCodec {

    /** Keep roughly this much history; anything older is pruned on every write. */
    const val KEEP_MS = 60L * 24 * 60 * 60_000L

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                i++
                out.append(
                    when (text[i]) {
                        't' -> '\t'
                        'n' -> '\n'
                        else -> text[i]
                    },
                )
            } else {
                out.append(c)
            }
            i++
        }
        return out.toString()
    }

    fun encode(sessions: List<WatchedSession>): String = sessions.joinToString("\n") {
        listOf(
            it.startAt.toString(),
            it.endAt.toString(),
            it.durationMin.toString(),
            escape(it.title),
            escape(it.subtitle),
        ).joinToString("\t")
    }

    /** Tolerant of anything: a bad line is dropped, never a crash in a provider. */
    fun decode(text: String): List<WatchedSession> = text.lineSequence().mapNotNull { line ->
        val parts = line.split("\t")
        if (parts.size < 5) return@mapNotNull null
        val startAt = parts[0].toLongOrNull() ?: return@mapNotNull null
        val endAt = parts[1].toLongOrNull() ?: return@mapNotNull null
        val durationMin = parts[2].toIntOrNull() ?: return@mapNotNull null
        WatchedSession(startAt, endAt, unescape(parts[3]), unescape(parts[4]), durationMin)
    }.toList()

    /** Append [session] to [stored], pruning everything that ended more than 60 days ago. */
    fun append(stored: String, session: WatchedSession, now: Long): String {
        val kept = decode(stored).filter { it.endAt >= now - KEEP_MS }
        return encode(kept + session)
    }
}
