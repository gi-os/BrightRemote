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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightremote.ui.theme.LightColors
import com.gios.lightremote.ui.theme.gridDp
import com.gios.lightremote.ui.theme.lightClickable

/**
 * PIN entry.
 *
 * A custom keypad rather than the system keyboard: the code is always four digits, the
 * eyes are on the television while typing, and the LP3's keyboard would cover most of the
 * screen to offer thirty keys that are not needed.
 */
@Composable
fun PairScreen(vm: RemoteViewModel, onPaired: () -> Unit, onCancel: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = LightColors.Background,
        topBar = { LightTopBar(state.pairingDeviceName ?: "Pair", onBack = onCancel) },
        bottomBar = {
            LightBottomBar(
                listOf(
                    BarAction("Cancel") { vm.cancelPairing(); onCancel() },
                    BarAction("Delete", enabled = state.pairingPin.isNotEmpty()) { vm.deletePinDigit() },
                    BarAction(
                        "Pair",
                        enabled = state.pairingPin.length == 4 && !state.pairingBusy,
                    ) { vm.submitPin(onPaired) },
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            state.error?.let { ErrorBanner(it) { vm.dismissError() } }

            Text(
                if (state.pairingBusy && state.pairingPin.isEmpty()) {
                    "Waiting for the code…"
                } else {
                    "Enter the code shown on the TV"
                },
                style = MaterialTheme.typography.bodySmall,
                color = LightColors.ContentSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.8f.gridDp(), bottom = 0.4f.gridDp()),
            )

            // Four slots, so a missing digit is visible rather than inferred.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 0.6f.gridDp()),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(4) { index ->
                    val digit = state.pairingPin.getOrNull(index)
                    Text(
                        digit?.toString() ?: "–",
                        style = MaterialTheme.typography.displayMedium,
                        color = if (digit != null) LightColors.Content else LightColors.Faint,
                        modifier = Modifier.padding(horizontal = 0.7f.gridDp()),
                    )
                }
            }

            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Keypad(
                    enabled = !state.pairingBusy,
                    onDigit = { vm.appendPin(it) },
                )
            }
        }
    }
}

@Composable
private fun Keypad(enabled: Boolean, onDigit: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", ""),
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.Center) {
                row.forEach { digit ->
                    Box(
                        Modifier
                            .size(width = 7f.gridDp(), height = 3.4f.gridDp())
                            .let {
                                if (digit.isEmpty()) it
                                else it.lightClickable(enabled = enabled) { onDigit(digit) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (digit.isNotEmpty()) {
                            Text(
                                digit,
                                style = MaterialTheme.typography.headlineMedium,
                                color = if (enabled) LightColors.Content else LightColors.Faint,
                            )
                        }
                    }
                }
            }
        }
    }
}
