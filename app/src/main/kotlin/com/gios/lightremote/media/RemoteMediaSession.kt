package com.gios.lightremote.media

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.gios.lightremote.proto.Mrp
import com.gios.lightremote.proto.MrpNowPlaying

/**
 * What the phone tells the rest of the system is playing on the Apple TV.
 *
 * A [MediaSessionCompat] is the standard way an app publishes now-playing and accepts transport
 * commands, and standing one up is what makes BrightControl's lock face grow a media row over
 * this remote for free — title, artwork, a play/pause and skip that come straight back here as
 * Companion commands. So this owns no logic of its own: it is fed a [MrpNowPlaying] and turns
 * the callbacks it receives into calls on [Controls].
 *
 * The activation rule is the lock face's rule. A session that is active with nothing playing
 * leaves a dead media row on the lock screen, and a session that flaps active/inactive on every
 * two-second buffering blip reads as the media failing. So: active only while connected *and*
 * something is playing or paused; deactivated the moment playback stops or the item goes away;
 * and buffering-like states (interrupted, seeking) are reported as playing rather than as a
 * stop, because a lock face that sees STATE_NONE for two seconds treats it as the session dying.
 *
 * ### Threading
 *
 * Callable from any thread; everything that touches the platform session runs on the main
 * looper. This is not a nicety — it is the fix for the v1.23.38 launch crash. The view model
 * reaches this object lazily from wherever now-playing changes, and one of those places is the
 * Companion socket's reader, a `Dispatchers.IO` worker with no [Looper]. `MediaSessionCompat`'s
 * one-argument `setCallback(callback)` does `new Handler()` on the calling thread (its
 * constructor guards against a looper-less thread; `setCallback` does not), so first touch from
 * the reader threw `RuntimeException: Can't create handler inside thread ... that has not called
 * Looper.prepare()`, and with the phone auto-connecting on launch the app died within a second
 * of opening, every time. Hence two rules here, and both stay:
 *
 *  - the callback is registered with an explicit main-looper [Handler];
 *  - [update], [deactivate] and [release] hop to the main thread before touching [session]
 *    (which also keeps [lastArtwork] single-threaded).
 */
class RemoteMediaSession(context: Context, private val controls: Controls) {

    /** The transport actions the lock face can send back. */
    interface Controls {
        fun playPause()
        fun nextTrack()
        fun previousTrack()
        fun seekTo(positionMs: Long)
    }

    private val main = Handler(Looper.getMainLooper())

    private val session = MediaSessionCompat(context, "BrightRemote").apply {
        setCallback(
            object : MediaSessionCompat.Callback() {
                override fun onPlay() = controls.playPause()
                override fun onPause() = controls.playPause()
                override fun onSkipToNext() = controls.nextTrack()
                override fun onSkipToPrevious() = controls.previousTrack()
                override fun onSeekTo(pos: Long) = controls.seekTo(pos)
            },
            // Explicit handler, never the one-argument overload — see the threading note above.
            Handler(Looper.getMainLooper()),
        )
    }

    /** The last artwork bytes turned into metadata, so a redraw does not decode them again. */
    private var lastArtwork: ByteArray? = null

    /**
     * Publish [nowPlaying], or deactivate the session when it is null (or stopped).
     *
     * @param title a fallback label when MRP has no title of its own — "Apple TV" when the only
     *   source is Companion's now-playing, which carries a position but no name.
     */
    fun update(nowPlaying: MrpNowPlaying?, fallbackTitle: String) = onMain {
        if (nowPlaying == null || nowPlaying.playbackState == Mrp.PlaybackState.Stopped) {
            deactivateNow()
            return@onMain
        }

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, nowPlaying.title ?: fallbackTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, nowPlaying.artist ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, nowPlaying.album ?: "")
        nowPlaying.duration?.let { metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (it * 1000).toLong()) }
        applyArtwork(metadata, nowPlaying.artwork)
        session.setMetadata(metadata.build())

        val state = when (nowPlaying.playbackState) {
            // Buffering-like states report as playing: a lock face reading STATE_NONE for the
            // couple of seconds a buffer takes treats the media as having failed.
            Mrp.PlaybackState.Playing,
            Mrp.PlaybackState.Seeking,
            Mrp.PlaybackState.Interrupted -> PlaybackStateCompat.STATE_PLAYING
            Mrp.PlaybackState.Paused -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        val position = ((nowPlaying.elapsed ?: 0.0) * 1000).toLong()
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO,
            )
            .setState(state, position, nowPlaying.rate.toFloat(), SystemClock.elapsedRealtime())
            .build()
        session.setPlaybackState(playbackState)

        if (!session.isActive) session.isActive = true
    }

    private fun applyArtwork(builder: MediaMetadataCompat.Builder, artwork: ByteArray?) {
        if (artwork == null) {
            lastArtwork = null
            return
        }
        if (!artwork.contentEquals(lastArtwork)) {
            lastArtwork = artwork
            runCatching { BitmapFactory.decodeByteArray(artwork, 0, artwork.size) }
                .getOrNull()
                ?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        }
    }

    /** Drop the session inactive — the lock face's rule for "playback stopped or item gone". */
    fun deactivate() = onMain { deactivateNow() }

    /** The body of [deactivate], for callers already on the main thread. */
    private fun deactivateNow() {
        if (session.isActive) {
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0, 0f, SystemClock.elapsedRealtime())
                    .build(),
            )
            session.isActive = false
        }
        lastArtwork = null
    }

    fun release() = onMain {
        deactivateNow()
        session.release()
    }

    /**
     * Run [block] on the main thread — immediately when already there, posted otherwise. Posts
     * from one thread land in order, so a burst of [update]s followed by a [deactivate] plays
     * back in the order it was sent.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
