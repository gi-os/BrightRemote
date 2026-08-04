package com.gios.lightremote.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightremote.R
import com.gios.light.common.hw.WheelScroll
import com.gios.lightremote.ui.theme.LightColors
import com.gios.lightremote.ui.theme.LightGrid
import com.gios.lightremote.ui.theme.gridDp

/**
 * Everything launchable on the TV.
 *
 * Pinned apps float to the top. A tvOS box with everything installed lists thirty-odd
 * entries, and the three or four you actually open should not be a scroll away — hold a row
 * to pin or unpin it.
 */
@Composable
fun AppsScreen(vm: RemoteViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadApps() }

    // A tvOS box with everything installed lists more than fits, so the wheel drives it.
    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = LightColors.Background,
        topBar = { LightTopBar("Apps", onBack = onBack) },
        bottomBar = {
            LightIconBar(
                listOf(
                    BarIcon(R.drawable.ic_back_white, "Back") { onBack() },
                    BarIcon(
                        R.drawable.ic_refresh_white,
                        "Reload",
                        enabled = !state.appsLoading,
                    ) { vm.loadApps() },
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { ErrorBanner(it) { vm.dismissError() } }

            val pinned = state.apps.filter { it.bundleId in state.pinned }
            val rest = state.apps.filter { it.bundleId !in state.pinned }

            when {
                state.appsLoading && state.apps.isEmpty() -> CenteredMessage("Loading…")
                state.apps.isEmpty() -> CenteredMessage(
                    "No apps reported",
                    "Some tvOS versions only answer this once the TV is awake.",
                )
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    // Keys are namespaced because an app appears in exactly one of the two
                    // sections, but the section it lands in changes as it is pinned.
                    items(pinned, key = { "pin-${it.bundleId}" }) { app ->
                        AppRow(app.name, isPinned = true, onOpen = {
                            vm.launchApp(app)
                            onBack()
                        }, onTogglePin = { vm.togglePin(app) })
                        Rule()
                    }
                    items(rest, key = { "app-${it.bundleId}" }) { app ->
                        AppRow(app.name, isPinned = false, onOpen = {
                            vm.launchApp(app)
                            onBack()
                        }, onTogglePin = { vm.togglePin(app) })
                        Rule()
                    }
                    item(key = "hint") {
                        Text(
                            "Hold an app to pin it",
                            style = MaterialTheme.typography.labelSmall,
                            color = LightColors.Faint,
                            textAlign = TextAlign.Center,
                            // fillMaxWidth, not fillMaxSize: a lazy item is handed an
                            // unbounded height and asking to fill it is a crash.
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 0.8f.gridDp()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    name: String,
    isPinned: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
) {
    LightRow(
        label = name,
        onClick = onOpen,
        onLongClick = onTogglePin,
        trailing = if (!isPinned) {
            null
        } else {
            {
                Icon(
                    painterResource(R.drawable.ic_star_white),
                    contentDescription = "Pinned",
                    tint = LightColors.ContentSecondary,
                    modifier = Modifier
                        .padding(start = LightGrid.INSET_UNITS.gridDp())
                        .size(1.6f.gridDp()),
                )
            }
        },
    )
}
