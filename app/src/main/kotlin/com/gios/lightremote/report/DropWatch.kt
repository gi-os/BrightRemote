package com.gios.lightremote.report

/**
 * Decides when the app files its own report about a link that failed, and what it says.
 *
 * The app used to *offer* — a chip, or a Send error row on the disconnected screen. Both are
 * one tap away from being dismissed, and they appear while somebody is standing in front of a
 * television that has stopped working, which is the worst moment to ask them for admin. So the
 * failure is sent now, and the banner afterwards says it went.
 *
 * Sending changes the throttling problem completely, and the change is not obvious. An offer
 * rationed per message text is fine: one is up, a second finds it already there. Sent, the same
 * rule filed thirty issues for one dead socket in another app, because the message named the
 * *command* and every command failing on one broken link looked like a brand new fault. Three
 * rules come out of that, and this class is all three:
 *
 * 1. **One report per episode, not per failure.** A drop, its two automatic reconnects and their
 *    failures are one thing that went wrong. [record] opens an episode and folds everything that
 *    follows into it; the report goes out once the dust settles ([settleMs] later), which is also
 *    what lets it say how it ended — recovered in four seconds, or still down.
 * 2. **A hard floor between any two reports**, whatever they say ([FLOOR_MS]). A signature that
 *    turns out to be accidentally unique cannot flood through it.
 * 3. **Escalating backoff per signature**, so the same fault reports promptly the second time —
 *    two data points ten minutes apart are worth far more than one — and then backs off to
 *    hourly. Everything suppressed in between is counted and carried into the next report that
 *    does go out, so nothing is silently lost: the report says "14 more like this since".
 *
 * Pure Kotlin on purpose: no Android imports, so the whole policy is testable on the JVM. The
 * ledger is a short string the caller keeps in SharedPreferences — it has to survive process
 * death or an app that crashes on launch would report on every launch.
 */
class DropWatch(
    private val nowMs: () -> Long,
    private val load: () -> String,
    private val store: (String) -> Unit,
    private val settleMs: Long = SETTLE_MS,
) {

    private class Episode(val kind: FaultKind, val firstAt: Long) {
        val lines = mutableListOf<String>()
        var faults = 0
        var lastAt = firstAt
        var recoveredAt: Long? = null
    }

    private var episode: Episode? = null

    /** The last episode a throttle refused to file, kept so a person can still send it. */
    private var held: Episode? = null

    /**
     * Note a failure.
     *
     * @return how long the caller should wait before calling [due], or null if an episode was
     * already open — in which case a flush is already on its way and a second one would report
     * the same episode twice.
     */
    fun record(kind: FaultKind, cause: String): Long? {
        val now = nowMs()
        val open = episode
        if (open == null) {
            val fresh = Episode(kind, now)
            fresh.faults = 1
            fresh.lines += "+0ms ${kind.label}: ${oneLine(cause)}"
            episode = fresh
            return settleMs
        }
        open.faults++
        open.lastAt = now
        open.lines += "+${now - open.firstAt}ms ${kind.label}: ${oneLine(cause)}"
        return null
    }

    /**
     * The link came back on its own.
     *
     * Does not cancel the report. A drop that self-heals is exactly the one that went unreported
     * for a fortnight here — the old rule called it noise — and "dropped, back in 4s" is a more
     * useful sentence than either half of it.
     */
    fun recovered() {
        episode?.let { if (it.recoveredAt == null) it.recoveredAt = nowMs() }
    }

    /** Whether there is an episode waiting to be reported. */
    fun pending(): Boolean = episode != null

    /**
     * The report to file now, or null if the throttles say not yet.
     *
     * Either way the episode is closed: a suppressed one is added to its signature's running
     * count instead of being dropped.
     *
     * @param force a person asked for it — skips both throttles. Their judgement about whether
     * this drop matters is better than any rule in here.
     */
    fun due(trace: String, force: Boolean = false): DropReport? {
        val ep = episode ?: return null
        episode = null
        val now = nowMs()
        val ledger = Ledger.parse(load())
        val row = ledger.row(signature(ep))
        if (now - row.lastSentAt > STREAK_RESET_MS) row.streak = 0
        // `== 0L` is "nothing has ever been sent", not "sent at the epoch". Without that clause
        // the very first report of an install is held back by a floor it cannot have breached.
        val floorClear = ledger.lastAnyAt == 0L || now - ledger.lastAnyAt >= FLOOR_MS
        val backoffClear = row.lastSentAt == 0L || now - row.lastSentAt >= backoffFor(row.streak)
        if (!force && !(floorClear && backoffClear)) {
            row.suppressed += ep.faults
            if (row.suppressedFirstAt == 0L) row.suppressedFirstAt = ep.firstAt
            row.suppressedLastAt = ep.lastAt
            store(ledger.serialize())
            held = ep
            return null
        }
        return emit(ep, ledger, row, trace)
    }

    /**
     * Whether a suppressed episode is still sitting here, unfiled.
     *
     * This is what puts the manual row back on the disconnected screen. Auto-sending replaces
     * the *asking*, not the ability to send: a throttle that silently swallowed the one drop
     * somebody actually wanted to report would be the old problem wearing a new hat.
     */
    fun heldCause(): String? = held?.lines?.firstOrNull()

    /** Send the suppressed episode anyway, because a person asked. Ignores both throttles. */
    fun forceHeld(trace: String): DropReport? {
        val ep = held ?: return null
        held = null
        val ledger = Ledger.parse(load())
        return emit(ep, ledger, ledger.row(signature(ep)), trace)
    }

    private fun emit(ep: Episode, ledger: Ledger, row: Ledger.Row, trace: String): DropReport {
        val detail = detail(ep, row, trace)
        row.lastSentAt = nowMs()
        row.streak++
        row.suppressed = 0
        row.suppressedFirstAt = 0L
        row.suppressedLastAt = 0L
        ledger.lastAnyAt = nowMs()
        store(ledger.serialize())
        if (held === ep) held = null
        return DropReport(kind = ep.kind, note = ep.kind.note, what = ep.kind.what, detail = detail)
    }

    private fun detail(ep: Episode, row: Ledger.Row, trace: String): String {
        val ended = when (val at = ep.recoveredAt) {
            null -> "still disconnected when this was filed"
            else -> "picked itself back up after ${seconds(at - ep.firstAt)}"
        }
        val span = if (ep.faults > 1) " over ${seconds(ep.lastAt - ep.firstAt)}" else ""
        val head = "${ep.faults} failure${if (ep.faults == 1) "" else "s"}$span, $ended."
        val suppressed = if (row.suppressed > 0) {
            "\n\n${row.suppressed} more like this went unreported between " +
                "${clock(row.suppressedFirstAt)} and ${clock(row.suppressedLastAt)} " +
                "(throttled, so one problem does not file itself twenty times)."
        } else {
            ""
        }
        return head + suppressed +
            "\n\nWhat happened:\n" + ep.lines.joinToString("\n") +
            "\n\nWire trace (newest last):\n" + trace
    }

    /**
     * What counts as "the same fault" for backoff.
     *
     * The kind plus the exception class, and deliberately *not* the message. The message is what
     * broke the last version of this idea: it named the command, or carried a port, a byte count
     * or a transaction id, so two copies of one fault looked unrelated and each one got to be a
     * first offence — thirty issues for one dead socket. Anything a failure can vary must stay
     * out of the key. The full message is in the report body, where variety is useful instead of
     * expensive.
     *
     * Where there is no exception — a failure the app worded itself — the wording is the key,
     * which is safe because the app wrote it as a constant.
     */
    private fun signature(ep: Episode): String {
        val cause = oneLine(ep.lines.firstOrNull()?.substringAfter(": ") ?: "")
        val head = cause.substringBefore(':').trim()
        val core = if (head.isNotEmpty() && !head.contains(' ')) head else cause
        val flattened = core.lowercase()
            .map { c -> if (c.isDigit()) '#' else c }
            .joinToString("")
            .filter { c -> c.isLetter() || c == '#' || c == ' ' || c == '.' }
            .replace(Regex(" +"), " ")
            .trim()
        return "${ep.kind.name} ${flattened.take(60)}"
    }

    private fun oneLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(160)

    private fun seconds(ms: Long): String = "${(ms / 100L).coerceAtLeast(0L) / 10.0}s"

    private fun clock(ms: Long): String {
        val minutes = ms / 60_000L % 60L
        val hours = ms / 3_600_000L % 24L
        return "%02d:%02d".format(hours, minutes) + " UTC"
    }

    private fun backoffFor(streak: Int): Long =
        BACKOFF[streak.coerceIn(0, BACKOFF.size - 1)]

    companion object {
        /**
         * How long to let a failure finish failing.
         *
         * Long enough to cover the two automatic reconnects (1.2s + 2.4s apart, each with its own
         * timeouts) so their outcome is in the report, short enough that a report about a live
         * problem is filed while the person is still holding the phone.
         */
        const val SETTLE_MS = 9_000L

        /** No two reports closer together than this, whatever they say. */
        const val FLOOR_MS = 60_000L

        /**
         * Same signature again: now, then two minutes, ten, thirty, then hourly.
         *
         * The second one is deliberately soon. A link that dies every time playback starts is
         * diagnosed by comparing two traces, and an hour of silence after the first report is how
         * the last round of this went unfixed for weeks.
         */
        val BACKOFF = listOf(0L, 2 * 60_000L, 10 * 60_000L, 30 * 60_000L, 60 * 60_000L)

        /** A fault that has stayed away this long starts over at the top of the backoff. */
        const val STREAK_RESET_MS = 6 * 60 * 60_000L
    }
}

/** The ways this app can tell that something it owns is not working. */
enum class FaultKind(val label: String, val note: String, val what: String) {
    Dropped(
        label = "drop",
        note = "the Apple TV dropped the connection",
        what = "keep the connection to the Apple TV",
    ),
    ConnectFailed(
        label = "connect failed",
        note = "could not connect to the Apple TV",
        what = "connect to the Apple TV",
    ),
    Unanswered(
        label = "no answer",
        note = "the Apple TV stopped answering buttons",
        what = "get an answer from the Apple TV",
    ),

    /**
     * The interactive AirPlay PIN pairing failed for a reason that is not a mistyped code.
     *
     * This is the flow the v1.25 field bug lived in, and it went undiagnosed for exactly as
     * long as it filed no report: the only account of the failure was one sentence on the
     * phone's screen, remembered and relayed. The pairing errors now name their HAP step and
     * the report carries the wire trace, so the next one explains itself.
     */
    PairFailed(
        label = "pairing failed",
        note = "pairing for now-playing failed partway",
        what = "finish the AirPlay pairing that shows titles and artwork",
    ),
}

/** One report, ready to hand to `Reports.compose`. */
data class DropReport(
    val kind: FaultKind,
    val note: String,
    val what: String,
    val detail: String,
)

/**
 * What has been reported lately, as a string short enough to live in SharedPreferences.
 *
 * Hand-rolled rather than JSON because it is six numbers and a string per fault and the app has
 * no JSON dependency. Rows are capped at [MAX_ROWS], oldest evicted: the interesting rows are
 * the recent ones, and an unbounded ledger in prefs is a slow leak.
 */
internal class Ledger private constructor(
    var lastAnyAt: Long,
    private val rows: MutableList<Row>,
) {

    internal class Row(val signature: String) {
        var lastSentAt: Long = 0L
        var streak: Int = 0
        var suppressed: Int = 0
        var suppressedFirstAt: Long = 0L
        var suppressedLastAt: Long = 0L
    }

    fun row(signature: String): Row {
        rows.firstOrNull { it.signature == signature }?.let { return it }
        val fresh = Row(signature)
        rows += fresh
        while (rows.size > MAX_ROWS) {
            rows.remove(rows.minByOrNull { it.lastSentAt } ?: break)
        }
        return fresh
    }

    fun serialize(): String = buildString {
        append("A|").append(lastAnyAt).append('\n')
        rows.forEach { r ->
            append("S|").append(clean(r.signature)).append('|')
                .append(r.lastSentAt).append('|')
                .append(r.streak).append('|')
                .append(r.suppressed).append('|')
                .append(r.suppressedFirstAt).append('|')
                .append(r.suppressedLastAt).append('\n')
        }
    }

    private fun clean(text: String): String = text.replace('|', ' ').replace('\n', ' ')

    companion object {
        const val MAX_ROWS = 8

        fun parse(text: String): Ledger {
            var lastAny = 0L
            val rows = mutableListOf<Row>()
            text.lineSequence().forEach { line ->
                val parts = line.split('|')
                when {
                    parts.size == 2 && parts[0] == "A" -> lastAny = parts[1].toLongOrNull() ?: 0L
                    parts.size == 7 && parts[0] == "S" -> {
                        val row = Row(parts[1])
                        row.lastSentAt = parts[2].toLongOrNull() ?: 0L
                        row.streak = parts[3].toIntOrNull() ?: 0
                        row.suppressed = parts[4].toIntOrNull() ?: 0
                        row.suppressedFirstAt = parts[5].toLongOrNull() ?: 0L
                        row.suppressedLastAt = parts[6].toLongOrNull() ?: 0L
                        rows += row
                    }
                }
            }
            return Ledger(lastAny, rows)
        }
    }
}
