package com.gios.lightremote.backup

import com.gios.light.common.sync.Contents
import com.gios.light.common.sync.FileStore
import com.gios.light.common.sync.LightSyncBackup
import com.gios.light.common.sync.SyncableStore

/**
 * What LightSync backs up for this app.
 *
 * One store, because there is one file: `lightremote.xml`. It holds the televisions you have
 * paired with, the client identity the Apple TV keys its paired-controller list on, which
 * remote face you prefer and which apps you pinned.
 *
 * **The pairing credentials travel, and that is deliberate.** They are stored as plain
 * SharedPreferences — the same colon-separated hex `atvremote` uses — and not sealed with an
 * AndroidKeyStore key, so they restore onto another phone and still work. That decision is
 * older than the backup and is written down in `data/Prefs.kt`: what these keys grant is the
 * ability to press buttons on a television on the same network, they already sit in the app's
 * private storage, and wrapping them in a key that cannot leave the device would have bought
 * nothing except a restore that decrypts to nothing. A backup of ciphertext whose key died
 * with the phone is worse than no backup, because it looks like one. This one is real: restore
 * it and the remote reconnects to your television without pairing again.
 *
 * The identity travels with it, which is the part that matters most. Regenerating it would
 * orphan the pairing on the television's side and leave a second dead controller listed there.
 *
 * Not included: the queued crash reports under `filesDir/reports`. They are in flight to
 * GitHub, they are about a phone that no longer exists, and a restore that re-files week-old
 * issues from a new install is noise.
 */
class Backup : LightSyncBackup() {

    override fun label(): String = "Remote"

    override fun stores(): List<SyncableStore> = listOf(
        FileStore("devices", Contents(prefs = listOf("lightremote"))),
    )
}
