package com.gios.lightremote.data

import android.content.Context
import android.content.SharedPreferences
import com.gios.lightremote.companion.ClientIdentity
import com.gios.lightremote.companion.Credentials
import java.security.SecureRandom

/** A TV we have paired with before. */
data class PairedDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val credentials: Credentials,
)

/**
 * Paired devices and the client identity, in SharedPreferences.
 *
 * The credentials are not additionally encrypted. They live in the app's private storage,
 * and what they grant is the ability to press buttons on a television on the same network —
 * wrapping them in an AndroidKeyStore key would make a lost-and-restored install fail with
 * undecryptable blobs for no real gain.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("lightremote", Context.MODE_PRIVATE)

    /**
     * A stable per-install identifier. The TV keys its paired-controller list on this, so
     * regenerating it would orphan the pairing and show a second entry on the TV.
     */
    val identity: ClientIdentity by lazy {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        val deviceId = existing ?: randomDeviceId().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        ClientIdentity(deviceId = deviceId)
    }

    private fun randomDeviceId(): String {
        // Shaped like a MAC address because that is what the field carries on real clients.
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte() // locally administered
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun devices(): List<PairedDevice> {
        val ids = prefs.getStringSet(KEY_DEVICE_IDS, emptySet()) ?: emptySet()
        return ids.mapNotNull { id ->
            val credentials = prefs.getString("$id.credentials", null)
                ?.let { Credentials.parse(it) } ?: return@mapNotNull null
            PairedDevice(
                id = id,
                name = prefs.getString("$id.name", "Apple TV") ?: "Apple TV",
                host = prefs.getString("$id.host", null) ?: return@mapNotNull null,
                port = prefs.getInt("$id.port", 49152),
                credentials = credentials,
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun save(device: PairedDevice) {
        val ids = (prefs.getStringSet(KEY_DEVICE_IDS, emptySet()) ?: emptySet()).toMutableSet()
        ids.add(device.id)
        prefs.edit()
            .putStringSet(KEY_DEVICE_IDS, ids)
            .putString("${device.id}.name", device.name)
            .putString("${device.id}.host", device.host)
            .putInt("${device.id}.port", device.port)
            .putString("${device.id}.credentials", device.credentials.serialize())
            .apply()
    }

    /** Addresses move when a router reboots; the credentials stay valid. */
    fun updateAddress(id: String, host: String, port: Int) {
        prefs.edit().putString("$id.host", host).putInt("$id.port", port).apply()
    }

    fun forget(id: String) {
        val ids = (prefs.getStringSet(KEY_DEVICE_IDS, emptySet()) ?: emptySet()).toMutableSet()
        ids.remove(id)
        prefs.edit()
            .putStringSet(KEY_DEVICE_IDS, ids)
            .remove("$id.name")
            .remove("$id.host")
            .remove("$id.port")
            .remove("$id.credentials")
            .apply()
    }

    var lastDeviceId: String?
        get() = prefs.getString(KEY_LAST_DEVICE, null)
        set(value) = prefs.edit().putString(KEY_LAST_DEVICE, value).apply()

    /** Swipe pad or D-pad as the remote face. The pad is the default. */
    var preferTouchpad: Boolean
        get() = prefs.getBoolean(KEY_PREFER_TOUCHPAD, true)
        set(value) = prefs.edit().putBoolean(KEY_PREFER_TOUCHPAD, value).apply()

    /**
     * Whether leaving the remote open should survive the screen going off.
     *
     * Off by default, and deliberately so: it needs the "Display over other apps" grant to
     * work at all (see WakeWatcher), and a fresh install that silently asks for an overlay
     * permission is an app that looks like it is up to something. Turning it on is the ask.
     */
    var stayOpen: Boolean
        get() = prefs.getBoolean(KEY_STAY_OPEN, false)
        set(value) = prefs.edit().putBoolean(KEY_STAY_OPEN, value).apply()

    /**
     * Bundle ids pinned to the top of the app list.
     *
     * Kept per install rather than per device: the handful of apps worth pinning are the ones
     * *you* watch, and they are the same on every TV in the house.
     */
    fun pinnedApps(): Set<String> = prefs.getStringSet(KEY_PINNED, emptySet()) ?: emptySet()

    fun togglePin(bundleId: String) {
        val pinned = pinnedApps().toMutableSet()
        if (!pinned.add(bundleId)) pinned.remove(bundleId)
        prefs.edit().putStringSet(KEY_PINNED, pinned).apply()
    }

    private companion object {
        const val KEY_DEVICE_ID = "client.device_id"
        const val KEY_DEVICE_IDS = "devices"
        const val KEY_LAST_DEVICE = "last_device"
        const val KEY_PREFER_TOUCHPAD = "prefer_touchpad"
        const val KEY_PINNED = "pinned_apps"
        const val KEY_STAY_OPEN = "stay_open"
    }
}
