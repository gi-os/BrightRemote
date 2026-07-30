package com.gios.lightremote.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightremote.hw.WheelScroll
import com.gios.lightremote.ui.theme.LightColors

/** Everything launchable on the TV, by name. */
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
            LightBottomBar(
                listOf(
                    BarAction("Back") { onBack() },
                    BarAction("Reload", enabled = !state.appsLoading) { vm.loadApps() },
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { ErrorBanner(it) { vm.dismissError() } }

            when {
                state.appsLoading && state.apps.isEmpty() -> CenteredMessage("Loading…")
                state.apps.isEmpty() -> CenteredMessage(
                    "No apps reported",
                    "Some tvOS versions only answer this once the TV is awake.",
                )
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(state.apps, key = { it.bundleId }) { app ->
                        LightRow(
                            label = app.name,
                            onClick = {
                                vm.launchApp(app)
                                onBack()
                            },
                        )
                        Rule()
                    }
                }
            }
        }
    }
}
