package com.gios.lightremote.watched

import android.content.Context
import android.content.SharedPreferences
import com.gios.lightremote.proto.MrpNowPlaying

/**
 * The bridge between the MRP stream and the [WatchedAssembler]: feeds every now-playing
 * snapshot in, persists whatever closes.
 *
 * Called from the tunnel's data-reader thread, so everything is behind one lock. Sessions are
 * written only when they close — the open one lives in memory — which is what makes a crash
 * harmless: the worst it can lose is the sitting in progress, and it can never persist a
 * session whose end it did not see. [flush] is the disconnect/teardown hook that closes the
 * open session at the moment the link actually went away.
 */
class WatchedRecorder(context: Context, private val clock: () -> Long = System::currentTimeMillis) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private val assembler = WatchedAssembler()
    private val lock = Any()

    /** One now-playing snapshot from the tunnel; null means nothing is playing. */
    fun onNowPlaying(nowPlaying: MrpNowPlaying?) {
        val now = clock()
        val closed = synchronized(lock) {
            val title = nowPlaying?.title
            if (nowPlaying != null && nowPlaying.isPlaying && !title.isNullOrBlank()) {
                assembler.playing(title, subtitleOf(nowPlaying), now)
            } else {
                assembler.notPlaying(now)
            }
        }
        closed?.let(::store)
    }

    /** Close any open session; the link is going away. Safe to call twice. */
    fun flush() {
        val closed = synchronized(lock) { assembler.flush(clock()) }
        closed?.let(::store)
    }

    private fun store(session: WatchedSession) {
        val current = prefs.getString(KEY_SESSIONS, "") ?: ""
        prefs.edit().putString(KEY_SESSIONS, WatchedCodec.append(current, session, clock())).apply()
    }

    private fun subtitleOf(nowPlaying: MrpNowPlaying): String =
        nowPlaying.artist?.takeIf { it.isNotBlank() }
            ?: nowPlaying.appName?.takeIf { it.isNotBlank() }
            ?: ""

    companion object {
        const val STORE = "lightremote.watched"
        const val KEY_SESSIONS = "sessions"
    }
}
