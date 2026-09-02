package com.gios.lightremote.watched

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Read-only viewing history for the Notebook journal.
 *
 * `content://com.gios.lightremote.watched/sessions/<yyyy-MM-dd>` answers with the sessions
 * that *started* on that local calendar date, columns exactly as the journal bus contract
 * pins them: `startAt` (Long, epoch ms), `endAt` (Long, epoch ms), `title` (String),
 * `subtitle` (String, artist/show/app or ""), `durationMin` (Int, playing time excluding
 * pauses).
 *
 * Failure shape, same as every provider on this bus: anything wrong — a date that does not
 * parse, a path that is not `sessions/`, a store that will not read — is an empty cursor,
 * never an exception across the binder. Writes are refused; this is a window, not a door.
 */
class WatchedProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val context = context ?: return cursor
        runCatching {
            val segments = uri.pathSegments
            if (segments.size != 2 || segments[0] != PATH) return@runCatching
            val day = LocalDate.parse(segments[1])
            val zone = ZoneId.systemDefault()
            sessions(context)
                .filter { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() == day }
                .sortedBy { it.startAt }
                .forEach {
                    cursor.addRow(arrayOf<Any?>(it.startAt, it.endAt, it.title, it.subtitle, it.durationMin))
                }
        }
        return cursor
    }

    private fun sessions(context: Context) = WatchedCodec.decode(
        context.getSharedPreferences(WatchedRecorder.STORE, Context.MODE_PRIVATE)
            .getString(WatchedRecorder.KEY_SESSIONS, "") ?: "",
    )

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("watched sessions are read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("watched sessions are read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("watched sessions are read-only")

    companion object {
        const val AUTHORITY = "com.gios.lightremote.watched"
        const val PATH = "sessions"
        val COLUMNS = arrayOf("startAt", "endAt", "title", "subtitle", "durationMin")
    }
}
