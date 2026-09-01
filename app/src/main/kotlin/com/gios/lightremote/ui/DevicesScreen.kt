package com.gios.lightremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightremote.data.PairedDevice
import com.gios.lightremote.discovery.DiscoveredDevice
import com.gios.lightremote.hw.WheelScroll
import com.gios.lightremote.ui.theme.LightColors
import com.gios.lightremote.ui.theme.LightGrid
import com.gios.lightremote.ui.theme.gridDp

/**
 * The device list: paired TVs first, then whatever else is answering on the network.
 *
 * Discovery runs only while this screen is on top. mDNS browsing keeps a multicast socket
 * open and wakes the radio, which is not something to leave running on a phone whose whole
 * point is a long battery life.
 */
@Composable
fun DevicesScreen(
    vm: RemoteViewModel,
    onOpenRemote: (PairedDevice) -> Unit,
    onPair: (DiscoveredDevice) -> Unit,
    onManage: (PairedDevice) -> Unit,
    onEnterAddress: () -> Unit,
    /** Null on first run, when there is no remote to go back to. */
    onBack: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }

    // Paired plus everything answering on the network runs past the bottom of the panel on
    // a busy Wi-Fi, so the wheel scrolls it.
    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = LightColors.Background,
        topBar = { LightTopBar("Devices", onBack = onBack) },
        bottomBar = {
            LightBottomBar(
                listOf(
                    BarAction(if (state.scanning) "Searching" else "Search") { vm.startDiscovery() },
                    // Always offered, not only after a failed search. A browse that has just
                    // started looks identical to one that is never going to find anything, so
                    // hiding this until the app is sure would hide it exactly while it is wanted.
                    BarAction("Address") { onEnterAddress() },
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { ErrorBanner(it) { vm.dismissError() } }

            if (state.paired.isEmpty() && state.discovered.isEmpty()) {
                Box(Modifier.weight(1f)) {
                    CenteredMessage(
                        if (state.scanning) "Looking for an Apple TV" else "No Apple TV found",
                        // Not just "check your Wi-Fi" any more. Being on the right network and
                        // still finding nothing is a real and common state — plenty of routers
                        // and most guest networks block the multicast this search rides on — and
                        // telling someone to check something they have already checked is how an
                        // app makes a working setup look broken.
                        "Both devices need to be on the same Wi-Fi network. Some routers block " +
                            "the search — if yours does, tap Address and type the TV's IP in.",
                    )
                }
                LightRow(label = "Enter the TV's address", onClick = onEnterAddress)
                Rule()
                return@Column
            }

            // Filtered here as well as in the view model. The two lists are updated by
            // different events, so for a moment after pairing a device can be in both — and
            // because LazyColumn keys have to be unique across the *whole* list, an overlap
            // is a hard crash rather than a cosmetic duplicate. The keys are namespaced for
            // the same reason.
            val pairedNames = state.paired.map { it.name }.toSet()
            val found = state.discovered.filter { it.name !in pairedNames }

            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                if (state.paired.isNotEmpty()) {
                    item(key = "header-paired") { SectionLabel("Paired") }
                    items(state.paired, key = { "paired-${it.id}" }) { device ->
                        LightRow(
                            label = device.name,
                            sub = device.host,
                            onClick = { onOpenRemote(device) },
                            onLongClick = { onManage(device) },
                        )
                        Rule()
                    }
                }
                if (found.isNotEmpty()) {
                    item(key = "header-found") { SectionLabel("Found") }
                    items(found, key = { "found-${it.name}" }) { device ->
                        LightRow(
                            label = device.name,
                            sub = device.friendlyModel ?: device.host,
                            enabled = device.pairable,
                            onClick = { if (device.pairable) onPair(device) },
                        )
                        Rule()
                    }
                }
                if (state.paired.isNotEmpty()) {
                    item(key = "footer-hint") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 0.8f.gridDp())) {
                            Text(
                                "Hold a paired device to forget it",
                                style = MaterialTheme.typography.labelSmall,
                                color = LightColors.Faint,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LightColors.Faint,
        modifier = Modifier.padding(
            start = LightGrid.INSET_UNITS.gridDp(),
            top = 0.7f.gridDp(),
            bottom = 0.2f.gridDp(),
        ),
    )
}

/**
 * One device's own screen: the AirPlay pairing row, then the forget confirm underneath.
 *
 * Forgetting stays a deliberate step, because it means pairing again from the TV — and it
 * forgets both pairings at once, Companion and AirPlay alike.
 */
@Composable
fun ForgetDeviceScreen(
    vm: RemoteViewModel,
    device: PairedDevice,
    onPairAirPlay: (PairedDevice) -> Unit,
    onDone: () -> Unit,
) {
    // Re-read from state so the row's sub-label flips to "Paired" the moment the AirPlay
    // pairing completes and navigation lands back here — the argument is a snapshot.
    val state by vm.state.collectAsStateWithLifecycle()
    val current = state.paired.firstOrNull { it.id == device.id } ?: device

    Scaffold(
        containerColor = LightColors.Background,
        topBar = { LightTopBar(current.name, onBack = onDone) },
        bottomBar = {
            LightBottomBar(
                listOf(
                    BarAction("Cancel") { onDone() },
                    BarAction("Forget") { vm.forget(current); onDone() },
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LightRow(
                label = "Pair for now playing",
                sub = if (current.airPlayCredentials != null) {
                    "Paired — the remote shows titles and artwork"
                } else {
                    "Titles and artwork need a one-time code"
                },
                onClick = { onPairAirPlay(current) },
            )
            Rule()
            Box(Modifier.weight(1f)) {
                CenteredMessage(
                    "Forget ${current.name}?",
                    "You will need the code from the TV to pair again.",
                )
            }
        }
    }
}
