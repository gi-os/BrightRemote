package com.gios.lightremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightremote.R
import com.gios.lightremote.companion.CompanionClient
import com.gios.lightremote.companion.HidCommand
import com.gios.lightremote.companion.PowerState
import com.gios.lightremote.companion.TouchPhase
import com.gios.lightremote.hw.LocalVolumeBus
import com.gios.lightremote.hw.WheelSteps
import com.gios.lightremote.ui.theme.LightColors
import com.gios.lightremote.ui.theme.LightGrid
import com.gios.lightremote.ui.theme.gridDp
import com.gios.lightremote.ui.theme.lightClickable
import com.gios.lightremote.ui.theme.lightHoldable
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

    // The wheel walks the tvOS focus vertically. Two notches a step, rate-limited — the sensor
    // fires faster than a moving highlight can be read. Awaited rather than launched, so a slow
    // link slows the wheel down instead of queueing presses behind it.
    WheelSteps(active = connected) { direction ->
        vm.pressAwait(if (direction > 0) HidCommand.Up else HidCommand.Down)
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
                    //
                    // Held for three seconds rather than tapped. It sits in the corner your
                    // thumb reaches for on the way to the back chevron, and putting the
                    // television to sleep by accident is the most annoying thing this app
                    // could do. The climbing buzz is what makes three seconds bearable — it
                    // tells you the hold is being counted rather than leaving you guessing.
                    Box(
                        Modifier
                            .size(LightGrid.BAR_ICON_UNITS.gridDp())
                            .lightHoldable(enabled = connected) { vm.togglePower() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_power_white),
                            contentDescription = "Power, hold for three seconds",
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
                //
                // The drawer slides up from behind the bar, which needs two things to look
                // right: the bar has to be opaque, and it has to draw last. Declaration order
                // already gives the second, but zIndex says so out loud — this is the kind of
                // thing a later reorder breaks silently.
                AnimatedVisibility(
                    visible = showMore && connected,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it },
                    modifier = Modifier.zIndex(0f),
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
                Box(Modifier.zIndex(1f)) {
                    LightIconBar(
                        listOf(
                            // Tap is back; hold is the menu. tvOS has no separate menu button
                            // — holding back is how you get the overlay — so there is no
                            // reason for this app to carry one either.
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
                            // Holding it skips the drawer and goes straight to typing.
                            // Searching is the one thing you arrive at the phone already
                            // meaning to do, and two taps to reach a text field is one too
                            // many when the TV is sitting there with a search box open.
                            BarIcon(
                                R.drawable.ic_more_white,
                                if (showMore) "Hide controls, hold to type" else "Show controls, hold to type",
                                enabled = connected,
                                onLongClick = {
                                    showMore = false
                                    onOpenKeyboard()
                                },
                            ) { showMore = !showMore },
                        ),
                    )
                }
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
 * to roughly one per 16ms — sending every pointer event floods the link and the TV reads the
 * burst as a flick, so a small drag overshoots by several rows.
 *
 * Nothing at all is sent until the finger has travelled past the platform's touch slop. That
 * is what stops the phantom scrolling: a tap is never perfectly still, and the previous
 * version opened the gesture on touch-down and then forwarded every wobble, so a thumb that
 * rolled a few pixels while pressing sent the TV a swipe nobody asked for. Now a press that
 * stays inside slop is only ever a click, and one that leaves it opens the drag from where the
 * finger actually started rather than from wherever it happened to cross the threshold.
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
                    val slop = viewConfiguration.touchSlop
                    val scaleX = CompanionClient.TOUCHPAD_SIZE / size.width.toFloat()
                    val scaleY = CompanionClient.TOUCHPAD_SIZE / size.height.toFloat()
                    val startX = down.position.x
                    val startY = down.position.y
                    var dragging = false
                    var lastSent = 0L

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!change.pressed) {
                            if (dragging) {
                                vm.touch(
                                    (change.position.x * scaleX).toInt(),
                                    (change.position.y * scaleY).toInt(),
                                    TouchPhase.Release,
                                )
                            } else {
                                // Never left slop, so it was a tap all along and the TV has
                                // heard nothing about it yet.
                                vm.click()
                            }
                            break
                        }

                        if (!dragging) {
                            val dx = change.position.x - startX
                            val dy = change.position.y - startY
                            if (kotlin.math.hypot(dx, dy) < slop) continue
                            dragging = true
                            vm.touch(
                                (startX * scaleX).toInt(),
                                (startY * scaleY).toInt(),
                                TouchPhase.Press,
                            )
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastSent >= 16) {
                            vm.touch(
                                (change.position.x * scaleX).toInt(),
                                (change.position.y * scaleY).toInt(),
                                TouchPhase.Hold,
                            )
                            lastSent = now
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

    // Opaque too: while it slides the pad is behind it, and a translucent drawer over a
    // trackpad reads as a rendering fault rather than as a panel.
    Column(
        Modifier
            .fillMaxWidth()
            .background(LightColors.Background),
    ) {
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
        }
        Rule()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 0.45f.gridDp()),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelIcon(R.drawable.ic_volume_down_white, "Volume down") { vm.volumeDown() }
            PanelIcon(
                R.drawable.ic_mute_white,
                if (state.muted) "Unmute" else "Mute",
                // Dimmed while muted, so the button shows the state it put the TV into.
                dim = state.muted,
            ) { vm.toggleMute() }
            PanelIcon(R.drawable.ic_volume_up_white, "Volume up") { vm.volumeUp() }
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
    /** Drawn quietly but still tappable — for a button whose state is "on", like mute. */
    dim: Boolean = false,
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
            tint = when {
                !enabled -> LightColors.Faint
                dim -> LightColors.ContentSecondary
                else -> LightColors.Content
            },
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
