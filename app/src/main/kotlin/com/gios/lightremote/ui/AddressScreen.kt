package com.gios.lightremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.gios.lightremote.ui.theme.LightColors
import com.gios.lightremote.ui.theme.gridDp
import com.gios.lightremote.ui.theme.lightClickable

/**
 * Type the television's address in by hand.
 *
 * The escape hatch for when discovery finds nothing. mDNS is multicast, and multicast is the
 * first thing a network gives up on: guest and IoT networks with client isolation drop it
 * outright, mesh systems often do not forward it between nodes or between bands, and plenty of
 * routers have an "AP isolation" or "multicast filtering" setting on by default that nobody
 * remembers turning on. On every one of those the app and the television can reach each other
 * perfectly well over TCP and simply cannot *find* each other — the one failure the device list
 * cannot distinguish from being on the wrong Wi-Fi, which is why it kept asking.
 *
 * The address is on the television under Settings → General → About. Pairing from here is the
 * ordinary pair-setup: the same PIN on screen, the same credentials afterwards.
 *
 * Digits and a dot on a keypad, not the system keyboard — same reasoning as the PIN screen, and
 * the LPIII keyboard would cover most of the panel to offer thirty keys that are not wanted.
 */
@Composable
fun AddressScreen(vm: RemoteViewModel, onPair: () -> Unit, onCancel: () -> Unit) {
    var address by remember { mutableStateOf("") }
    val valid = remember(address) { isPlausibleIpv4(address) }

    Scaffold(
        containerColor = LightColors.Background,
        topBar = { LightTopBar("Address", onBack = onCancel) },
        bottomBar = {
            LightBottomBar(
                listOf(
                    BarAction("Delete", enabled = address.isNotEmpty()) {
                        address = address.dropLast(1)
                    },
                    BarAction("Pair", enabled = valid) {
                        val (host, port) = splitAddress(address.trim())
                        vm.beginPairingAt(host, port)
                        onPair()
                    },
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Text(
                "The TV's IP address, from Settings → General → About. " +
                    "Add :port only if the default one does not answer.",
                style = MaterialTheme.typography.bodySmall,
                color = LightColors.ContentSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1.5f.gridDp())
                    .padding(top = 0.8f.gridDp(), bottom = 0.4f.gridDp()),
            )

            Text(
                address.ifEmpty { "0.0.0.0" },
                style = MaterialTheme.typography.headlineMedium,
                // The placeholder is a shape to type into, not a value — it has to read as
                // absent, or the Pair button looks broken while a full address is on screen.
                color = if (address.isEmpty()) LightColors.Faint else LightColors.Content,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 0.6f.gridDp()),
            )

            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                AddressKeypad { key ->
                    // Bounded so a stuck thumb cannot run the string off the screen. Fifteen for
                    // the address, six more for ":65535".
                    if (address.length < 21) address += key
                }
            }
        }
    }
}

/**
 * Four numbers under 256, separated by dots, with an optional `:port`.
 *
 * Deliberately not a hostname check. Resolving a name needs DNS to know about the television, and
 * a network whose multicast is broken is not one whose DNS has a record for the living room — so
 * the thing worth accepting here is the number the television shows you.
 *
 * The port is offered because Companion does not have a fixed one: the television takes the first
 * free port from 49152 upwards, so a set that has been up for a long time can be several higher,
 * and mDNS is normally what carries that number. On this screen there is no mDNS, which makes a
 * wrong port the one failure a typed address cannot otherwise recover from.
 */
internal fun isPlausibleIpv4(text: String): Boolean {
    val (host, port) = splitAddress(text)
    if (text.contains(":") && port == null) return false
    val parts = host.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && (part.toIntOrNull() ?: -1) in 0..255
    }
}

/** Splits `1.2.3.4:49153` into its halves. A missing or unusable port comes back null. */
internal fun splitAddress(text: String): Pair<String, Int?> {
    val colon = text.indexOf(':')
    if (colon < 0) return text to null
    val port = text.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 }
    return text.substring(0, colon) to port
}

@Composable
private fun AddressKeypad(onKey: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(".", "0", ":"),
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.Center) {
                row.forEach { key ->
                    Box(
                        Modifier
                            .size(width = 7f.gridDp(), height = 3.4f.gridDp())
                            .let { if (key.isEmpty()) it else it.lightClickable { onKey(key) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key.isNotEmpty()) {
                            Text(
                                key,
                                style = MaterialTheme.typography.headlineMedium,
                                color = LightColors.Content,
                            )
                        }
                    }
                }
            }
        }
    }
}
