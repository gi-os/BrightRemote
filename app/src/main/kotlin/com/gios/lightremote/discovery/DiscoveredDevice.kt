package com.gios.lightremote.discovery

/** One device answering `_companion-link._tcp` on the network. */
data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    /** Raw `rpMd` model string from the mDNS record, e.g. "AppleTV14,1". */
    val model: String? = null,
    /** Raw `rpFl` flags from the mDNS record. Null when the platform handed no TXT record over. */
    val flags: Long? = null,
    /** What the record says about pairing it; see [CompanionTxt.pairingSupport]. */
    val support: PairingSupport = PairingSupport.Unknown,
) {
    /** Whether tapping the row may start a pair-setup. Unknown stays tappable, as it always was. */
    val pairable: Boolean
        get() = support == PairingSupport.Pairable || support == PairingSupport.Unknown

    /** "Apple TV 4K" reads better on a 27-unit-wide screen than "AppleTV14,1". */
    val friendlyModel: String? get() = when {
        model == null -> null
        model.startsWith("AppleTV5,3") -> "Apple TV HD"
        model.startsWith("AppleTV6,2") -> "Apple TV 4K"
        model.startsWith("AppleTV11,1") -> "Apple TV 4K (2nd gen)"
        model.startsWith("AppleTV14,1") -> "Apple TV 4K (3rd gen)"
        model.startsWith("AudioAccessory") -> "HomePod"
        model.startsWith("AppleTV") -> "Apple TV"
        else -> model
    }

    /** The line under the name in Found: the model, or why the row cannot be tapped. */
    val foundLabel: String get() = when (support) {
        PairingSupport.PairingOff -> "Pairing is turned off on this TV"
        PairingSupport.NotATelevision -> "${friendlyModel ?: "This device"} is not an Apple TV"
        else -> friendlyModel ?: host
    }

    /** One trace line's worth of what the record said, so a pairing report names what was tapped. */
    val describe: String get() =
        "${model ?: "no model"}, rpFl ${flags?.let { "0x" + it.toString(16) } ?: "none"}, $support"
}

/** What a `_companion-link._tcp` record says about pairing the device as a remote. */
enum class PairingSupport {
    /** An Apple TV whose record says pair-setup with a code is allowed. */
    Pairable,

    /** An Apple TV with pairing restricted in Settings > AirPlay and HomeKit. */
    PairingOff,

    /** A HomePod, Mac, iPad or iPhone: the same service, but nothing that can show a code. */
    NotATelevision,

    /** No TXT record to go on (the platform hands them over from API 34 only). */
    Unknown,
}

/**
 * Reading the Companion TXT record, the way pyatv does.
 *
 * The flag masks are pyatv's (`pyatv/protocols/companion/__init__.py`). This app had its own pair,
 * `0x40000000` for "pairing disabled" and `0x200` for "pairs with a PIN", which match nothing in
 * any record pyatv has catalogued — Apple TVs advertise values like `0x36782` and `0x367A2` — so
 * the "listed but not tappable" rule in the README never fired, and every device on the network was
 * offered for pairing: HomePods, Macs, iPads, and televisions that only accept their own household.
 * Tapping one of those is a pair-setup the device closes or ignores, which is what
 * light-reports#676 and #590 look like on the wire.
 *
 * Plain Kotlin, no Android, so the masks are tested on the JVM against the catalogued values.
 */
object CompanionTxt {

    /** pyatv `PAIRING_DISABLED_MASK`: set by "Allow access: only people sharing this home". */
    const val PAIRING_DISABLED_MASK = 0x04L

    /**
     * pyatv `PAIRING_WITH_PIN_SUPPORTED_MASK`. Set on every Apple TV pyatv has catalogued and on no
     * HomePod, Mac or iPad. Recorded rather than enforced on its own: the model already says which
     * of those a device is, and it is the safer of the two to trust with hiding a television.
     */
    const val PIN_PAIRING_MASK = 0x4000L

    /**
     * A TXT value by key, ignoring case.
     *
     * DNS-SD keys are case-insensitive and rapportd writes them as `rpMd` and `rpFl`; depending on
     * the Android build they arrive either way, and an exact lookup of "rpmd" against "rpMd" is a
     * model that is silently never read.
     */
    fun value(attributes: Map<String, ByteArray?>, key: String): String? =
        attributes.entries
            .firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value
            ?.toString(Charsets.UTF_8)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** `rpFl` is hex, written with a `0x` prefix. Null when absent or unreadable. */
    fun flags(raw: String?): Long? =
        raw?.trim()?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16)

    fun pairingSupport(model: String?, flags: Long?): PairingSupport = when {
        model != null && !model.startsWith("AppleTV", ignoreCase = true) -> PairingSupport.NotATelevision
        flags == null -> PairingSupport.Unknown
        flags and PAIRING_DISABLED_MASK != 0L -> PairingSupport.PairingOff
        else -> PairingSupport.Pairable
    }
}
