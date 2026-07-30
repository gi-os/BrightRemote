package com.gios.lightremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightremote.R
import com.gios.lightremote.companion.CompanionClient
import com.gios.lightremote.companion.HidCommand
import com.gios.lightremote.companion.PowerState
import com.gios.lightremote.companion.TouchPhase
import com.gios.lightremote.hw.LocalVolumeBus
import com.gios.lightremote.ui.theme.LightColors
import com.gios.lightremote.ui.theme.LightGrid
import com.gios.lightremote.ui.theme.gridDp
import com.gios.lightremote.ui.theme.lightClickable
import com.gios.lightremote.ui.theme.tick

/**
 * The remote.
 *
 * Almost all of the panel is the thing you touch. Back and Home earn their place in the
 * bottom bar because they are used constantly; everything else — playback, volume, the face
 * toggle, apps, the keyboard — lives behind the third button and slides up when asked for.
 * A remote you glance at should not present fourteen targets.
 */
@Composable
fun RemoteScreen(
    vm: RemoteViewModel,
    onOpenDevices: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenKeyboard: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var touchpad by remember { mutableStateOf(vm.preferTouchpad) }
    var showMore by remember { mutableStateOf(false) }
    val connected = state.connection == ConnectionState.Connected

    // The volume rocker drives the television, but only while one is connected and only while
    // this screen is up — released on the way out so the phone gets its own volume back.
    val volumeBus = LocalVolumeBus.current
    DisposableEffect(volumeBus, connected) {
        volumeBus?.intercept = connected
        onDispose { volumeBus?.intercept = false }
    }
    LaunchedEffect(volumeBus) {
        volumeBus?.presses?.collect { delta ->
            if (delta > 0) vm.volumeUp() else vm.volumeDown()
        }
    }

    Scaffold(
        containerColor = LightColors.Background,
        topBar = {
            LightTopBar(
                // No title: the panel is short, and the device name is not worth three grid
                // units when the chevron already leads to the list that names it.
                title = null,
                onBack = onOpenDevices,
                action = {
                    // Power doubles as the connection indicator: dim when we don't know.
                    Box(
                        Modifier
                            .size(LightGrid.BAR_ICON_UNITS.gridDp())
                            .lightClickable(enabled = connected) { vm.togglePower() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_power_white),
                            contentDescription = "Power",
                            tint = when {
                                !connected -> LightColors.Faint
                                state.power == PowerState.On -> LightColors.Content
                                state.power == PowerState.Screensaver -> LightColors.ContentSecondary
                                else -> LightColors.Faint
                            },
                            modifier = Modifier.size(LightGrid.BAR_ICON_UNITS.gridDp()),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // Inside the bottom bar slot, so opening the drawer shortens the pad instead
                // of covering it. Scaffold hands the content whatever height is left.
                AnimatedVisibility(
                    visible = showMore && connected,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it },
                ) {
                    MorePanel(
                        vm = vm,
                        touchpad = touchpad,
                        onToggleFace = {
                            touchpad = !touchpad
                            vm.preferTouchpad = touchpad
                        },
                        onOpenApps = { showMore = false; onOpenApps() },
                        onOpenKeyboard = { showMore = false; onOpenKeyboard() },
                    )
                }
                LightIconBar(
                    listOf(
                        // Tap is back; hold is the menu. tvOS has no separate menu button —
                        // holding back is how you get the overlay — so there is no reason for
                        // this app to carry one either.
                        BarIcon(
                            R.drawable.ic_back_white,
                            "Back, hold for menu",
                            enabled = connected,
                            onLongClick = { vm.controlCenter() },
                        ) { vm.press(HidCommand.Menu) },
                        BarIcon(
                            R.drawable.ic_home_white,
                            "Home, hold for app switcher",
                            enabled = connected,
                            onLongClick = { vm.hold(HidCommand.Home) },
                        ) { vm.press(HidCommand.Home) },
                        BarIcon(
                            R.drawable.ic_more_white,
                            if (showMore) "Hide controls" else "Show controls",
                            enabled = connected,
                        ) { showMore = !showMore },
                    ),
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            state.error?.let { ErrorBanner(it) { vm.dismissError() } }

            when (state.connection) {
                ConnectionState.Connecting -> CenteredMessage("Connecting…")
                ConnectionState.Disconnected -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        if (state.activeName == null) {
                            CenteredMessage(
                                "No Apple TV paired",
                                "Open Devices to find one on your network.",
                            )
                        } else {
                            CenteredMessage("Not connected", "Tap Retry to reconnect.")
                        }
                    }
                    if (state.activeName != null) {
                        LightRow(label = "Retry", onClick = { vm.reconnect() })
                        Rule()
                    }
                    LightRow(label = "Devices", onClick = onOpenDevices)
                    Rule()
                }
                ConnectionState.Connected ->
                    if (touchpad) Touchpad(vm, Modifier.weight(1f))
                    else DirectionPad(vm, Modifier.weight(1f))
            }
        }
    }
}

/**
 * The swipe surface: the whole area, no border, nothing drawn.
 *
 * Coordinates map straight onto the TV's 1000x1000 touch surface, and samples are throttled
 * to roughly one per 16ms. Both details matter: sending every pointer event floods the link
 * and the TV reads the burst as a flick, so a small drag overshoots by several rows.
 */
@Composable
private fun Touchpad(vm: RemoteViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    tick(context)
                    val scaleX = CompanionClient.TOUCHPAD_SIZE / size.width.toFloat()
                    val scaleY = CompanionClient.TOUCHPAD_SIZE / size.height.toFloat()
                    var lastSent = 0L
                    var lastX = (down.position.x * scaleX).toInt()
                    var lastY = (down.position.y * scaleY).toInt()
                    var moved = false
                    vm.touch(lastX, lastY, TouchPhase.Press)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val x = (change.position.x * scaleX).toInt()
                        val y = (change.position.y * scaleY).toInt()
                        if (!change.pressed) {
                            vm.touch(x, y, TouchPhase.Release)
                            // A press that never moved is a click, not a swipe.
                            if (!moved) vm.click()
                            break
                        }
                        if (kotlin.math.abs(x - lastX) > 8 || kotlin.math.abs(y - lastY) > 8) {
                            moved = true
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastSent >= 16) {
                            vm.touch(x, y, TouchPhase.Hold)
                            lastSent = now
                            lastX = x
                            lastY = y
                        }
                    }
                }
            },
    )
}

/** Up/down/left/right around a centre Select. */
@Composable
private fun DirectionPad(vm: RemoteViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PadButton({ vm.press(HidCommand.Up) }) { ArrowIcon(R.drawable.ic_up_white) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PadButton({ vm.press(HidCommand.Left) }) { ArrowIcon(R.drawable.ic_back_white) }
            Box(
                Modifier
                    .size(7f.gridDp())
                    .border(1.dp, LightColors.Rule)
                    .lightClickable { vm.press(HidCommand.Select) },
                contentAlignment = Alignment.Center,
            ) {
                Text("OK", style = MaterialTheme.typography.titleMedium, color = LightColors.Content)
            }
            PadButton({ vm.press(HidCommand.Right) }) { ArrowIcon(R.drawable.ic_arrow_right_white) }
        }
        PadButton({ vm.press(HidCommand.Down) }) { ArrowIcon(R.drawable.ic_down_white) }
    }
}

/**
 * The drawer: playback and volume on top, the three places to go underneath.
 *
 * The playback row dims when the TV reports it has no media controls — `_iMC` tracks the
 * foreground app, so on the home screen these genuinely do nothing and showing them as live
 * would be a lie.
 */
@Composable
private fun MorePanel(
    vm: RemoteViewModel,
    touchpad: Boolean,
    onToggleFace: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenKeyboard: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val playing = state.controls.anyPlayback

    Column(Modifier.fillMaxWidth()) {
        Rule()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 0.45f.gridDp()),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelIcon(R.drawable.ic_skip_backward_fifteen_white, "Back 15 seconds", playing) { vm.skipBackward() }
            PanelIcon(R.drawable.ic_play_white, "Play or pause") { vm.playPause() }
            PanelIcon(R.drawable.ic_skip_forward_fifteen_white, "Forward 15 seconds", playing) { vm.skipForward() }
            PanelIcon(R.drawable.ic_speaker_muted, "Volume down") { vm.volumeDown() }
            PanelIcon(R.drawable.ic_speaker_on, "Volume up") { vm.volumeUp() }
        }
        Rule()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 0.45f.gridDp()),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelIcon(
                // Shows what you would switch *to*, the convention LightOS uses for a toggle.
                if (touchpad) R.drawable.ic_dpad_white else R.drawable.ic_trackpad_white,
                if (touchpad) "Switch to D-pad" else "Switch to trackpad",
                onClick = onToggleFace,
            )
            PanelIcon(R.drawable.ic_apps_grid_white, "Apps", onClick = onOpenApps)
            PanelIcon(R.drawable.ic_keyboard_white, "Type", onClick = onOpenKeyboard)
        }
    }
}

@Composable
private fun PanelIcon(
    resource: Int,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 4.6f.gridDp(), height = 3f.gridDp())
            .lightClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(resource),
            contentDescription = label,
            tint = if (enabled) LightColors.Content else LightColors.Faint,
            modifier = Modifier.size(2.2f.gridDp()),
        )
    }
}

@Composable
private fun ArrowIcon(resource: Int, rotation: Float = 0f) {
    Icon(
        painterResource(resource),
        contentDescription = null,
        tint = LightColors.Content,
        modifier = Modifier
            .size(2.6f.gridDp())
            .graphicsLayer(rotationZ = rotation),
    )
}

@Composable
private fun PadButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(width = 7f.gridDp(), height = 4f.gridDp())
            .lightClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// Reconnecting automatically was tried and removed: a failed connect goes
// Connecting -> Disconnected, which is a state *change*, so an effect keyed on the
// connection state retries forever against a TV that is simply switched off. The Retry row
// above is deliberate instead.
