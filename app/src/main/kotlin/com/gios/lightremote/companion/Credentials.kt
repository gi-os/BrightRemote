package com.gios.lightremote.companion

/**
 * The long-term pairing material for one Apple TV.
 *
 * @param devicePublicKey the TV's Ed25519 public key (its "LTPK")
 * @param clientPrivateKey our own Ed25519 seed, which also doubles as the SRP secret
 * @param deviceId the identifier the TV announced during pair-setup
 * @param clientId the identifier we announced, a UUID string as bytes
 *
 * Serialised in the same colon-separated hex form pyatv uses, so a credential string can
 * be moved between this app and `atvremote` for debugging.
 */
data class Credentials(
    val devicePublicKey: ByteArray,
    val clientPrivateKey: ByteArray,
    val deviceId: ByteArray,
    val clientId: ByteArray,
) {
    fun serialize(): String = listOf(devicePublicKey, clientPrivateKey, deviceId, clientId)
        .joinToString(":") { bytes -> bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) } }

    // Data classes compare ByteArray by identity, which would make two credentials for the
    // same device look different.
    override fun equals(other: Any?): Boolean = other is Credentials &&
        devicePublicKey.contentEquals(other.devicePublicKey) &&
        clientPrivateKey.contentEquals(other.clientPrivateKey) &&
        deviceId.contentEquals(other.deviceId) &&
        clientId.contentEquals(other.clientId)

    override fun hashCode(): Int = serialize().hashCode()

    override fun toString(): String = "Credentials(device=${deviceId.decodeToString()})"

    companion object {
        fun parse(text: String): Credentials? {
            val parts = text.split(":")
            if (parts.size != 4) return null
            val decoded = parts.map { hex ->
                if (hex.length % 2 != 0) return null
                ByteArray(hex.length / 2) { i ->
                    val byte = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
                    byte.toByte()
                }
            }
            if (decoded.any { it.isEmpty() }) return null
            return Credentials(decoded[0], decoded[1], decoded[2], decoded[3])
        }
    }
}
