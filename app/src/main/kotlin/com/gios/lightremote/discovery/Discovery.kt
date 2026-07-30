package com.gios.lightremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One Apple TV found on the network. */
data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    /** Raw `rpmd` model string from the mDNS record, e.g. "AppleTV14,1". */
    val model: String? = null,
    /** False when the device has pairing turned off in Settings. */
    val pairable: Boolean = true,
) {
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
}

/**
 * Finds Apple TVs by browsing `_companion-link._tcp` over mDNS.
 *
 * Uses the platform [NsdManager] rather than bundling jmDNS. That costs some control — in
 * particular NsdManager only hands over TXT records from API 34, so the model name and the
 * pairing flag are best-effort — but it avoids shipping a second mDNS stack and the
 * multicast socket handling that comes with it.
 */
class Discovery(context: Context) {

    companion object {
        private const val TAG = "LightRemote/Discovery"
        private const val SERVICE_TYPE = "_companion-link._tcp"

        /** Bit in the `rpfl` flags that means "pairing is disabled on this device". */
        private const val PAIRING_DISABLED_MASK = 0x40000000L
        private const val PAIRING_WITH_PIN_MASK = 0x00000200L
    }

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * Emits the set of devices found so far, re-emitting on every change.
     *
     * Resolution is where this gets fiddly. Discovery reports a service name; the address
     * only arrives after a separate resolve, and on older releases NsdManager will only run
     * one resolve at a time, so failures are swallowed and retried by the next browse
     * rather than surfaced.
     */
    fun devices(): Flow<List<DiscoveredDevice>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredDevice>()

        fun publish() {
            trySend(found.values.sortedBy { it.name.lowercase() })
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.d(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                // TXT records only come through from API 34; below that these stay null and
                // the row falls back to showing the address.
                val attributes = serviceInfo.attributes ?: emptyMap()
                val model = attributes["rpmd"]?.let { String(it, Charsets.UTF_8) }
                val flags = attributes["rpfl"]?.let { String(it, Charsets.UTF_8) }
                    ?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
                val pairable = (flags and PAIRING_DISABLED_MASK) == 0L

                found[serviceInfo.serviceName] = DiscoveredDevice(
                    name = serviceInfo.serviceName,
                    host = host,
                    port = serviceInfo.port,
                    model = model,
                    pairable = pairable,
                )
                publish()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "browsing $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                runCatching { nsd.resolveService(serviceInfo, resolveListener) }
                    .onFailure { Log.d(TAG, "resolve rejected: ${it.message}") }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (found.remove(serviceInfo.serviceName) != null) publish()
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "could not start discovery: $errorCode")
                // Close rather than hang on an empty list forever.
                close(IllegalStateException("network discovery unavailable (error $errorCode)"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        publish()

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        }
    }
}
