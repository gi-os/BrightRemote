package com.gios.lightremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import kotlinx.coroutines.delay

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
    var wheelHorizontal by remember { mutableStateOf(vm.wheelHorizontal) }
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

    // The wheel walks the tvOS focus. Two notches a step, rate-limited — the sensor fires
    // faster than a moving highlight can be read. Awaited rather than launched, so a slow
    // link slows the wheel down instead of queueing presses behind it.
    //
    // Which axis it walks is the top-bar toggle: vertical for lists, horizontal for the home
    // screen's rows and every app's shelf. Sideways, a roll of the wheel *up* goes Left —
    // up is "back the way you came" on the vertical axis, and Left is the same direction in
    // a row. The lambda reads the state through rememberUpdatedState inside WheelSteps, so
    // flipping the toggle takes effect on the very next notch.
    WheelSteps(active = connected) { direction ->
        vm.pressAwait(
            when {
                wheelHorizontal -> if (direction > 0) HidCommand.Left else HidCommand.Right
                else -> if (direction > 0) HidCommand.Up else HidCommand.Down
            },
        )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Which axis the wheel walks. The icon shows the current mode — a
                        // glance answers "what will the wheel do right now" — and a tap
                        // flips it. A preference, not a session flag: the person who lives
                        // on the home screen's rows wants it sideways every time.
                        Box(
                            Modifier
                                .size(LightGrid.BAR_ICON_UNITS.gridDp())
                                .lightClickable {
                                    wheelHorizontal = !wheelHorizontal
                                    vm.wheelHorizontal = wheelHorizontal
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(
                                    if (wheelHorizontal) R.drawable.ic_wheel_horizontal_white
                                    else R.drawable.ic_wheel_vertical_white,
                                ),
                                contentDescription = if (wheelHorizontal) {
                                    "Wheel scrolls sideways, tap for up and down"
                                } else {
                                    "Wheel scrolls up and down, tap for sideways"
                                },
                                tint = if (connected) LightColors.Content else LightColors.Faint,
                                modifier = Modifier.size(LightGrid.BAR_ICON_UNITS.gridDp()),
                            )
                        }
                        Spacer(Modifier.width(1f.gridDp()))
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
                            // Back is a tap and nothing else.
                            //
                            // It used to carry Control Centre on a long press, which was wrong
                            // twice over. Wrong on the television, where Control Centre is a
                            // held *Home*, not a held Back. And wrong in the hand: Compose
                            // calls anything past ~500 ms a long press, which is an ordinary
                            // careful tap on a small icon while you are looking at the screen
                            // across the room — so pressing Back opened an overlay instead of
                            // going back, and Back "did not work".
                            BarIcon(
                                R.drawable.ic_back_white,
                                "Back",
                                enabled = connected,
                            ) { vm.press(HidCommand.Menu) },
                            // Held Home is Control Centre, which is what tvOS does with it.
                            BarIcon(
                                R.drawable.ic_home_white,
                                "Home, hold for Control Centre",
                                enabled = connected,
                                onLongClick = { vm.controlCenter() },
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
            // What the app sent about itself, and the only notice it gets. Ten seconds, then
            // it goes on its own; the tap target is the line, not the screen, so it cannot eat
            // a press meant for the television.
            state.sent?.let { note ->
                LaunchedEffect(note) {
                    delay(SENT_BANNER_MS)
                    vm.dismissSent()
                }
                ErrorBanner(note) { vm.dismissSent() }
            }

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
                    // Nothing to press in the ordinary case: a drop files itself, and the
                    // banner at the top says so. This row is only for the one a throttle
                    // refused — the second drop in five minutes, which is often the one worth
                    // having — so the automatic manners never stand between somebody and
                    // sending what they are looking at.
                    if (state.reportable != null && !state.reportSent) {
                        LightRow(
                            label = "Send error anyway",
                            sub = "This one was held back to avoid repeats",
                            onClick = { vm.sendDropReport() },
                        )
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
 * How much of the television's touch surface one panel-width drag covers.
 *
 * Mapping the panel one-to-one onto the surface was the overshoot. A Siri Remote's pad is about
 * 35 mm across and is stroked with the ball of a thumb; the LPIII panel is nearly twice that and
 * is dragged with a whole finger, so the same gesture arrived as a far longer, far faster one —
 * and tvOS reads a long fast stroke as a flick, which does not move a row, it throws the list.
 *
 * At 0.5 a drag across the full panel is exactly half the surface, which is also the largest gain
 * that keeps every part of the gesture live: the origin sits at the middle of the surface, so
 * anything above a half saturates before the finger reaches the far edge, and the last of the
 * travel — on the one gesture where somebody is deliberately asking for maximum throw — would
 * silently do nothing. It halves the *velocity* the television infers too, which is the part that
 * actually stops the flick.
 */
/**
 * How long the "report sent" line stays up.
 *
 * Long enough to read while looking at a television, short enough that it is gone before the
 * next thing goes wrong.
 */
private const val SENT_BANNER_MS = 10_000L

private const val SWIPE_GAIN = 0.5f

/**
 * The swipe surface: the whole area, no border, nothing drawn.
 *
 * Coordinates are relative to where the finger landed, taken from the middle of the television's
 * surface and scaled by [SWIPE_GAIN]. Relative rather than absolute because the panel is not a
 * scale model of the pad — where on the glass you happen to start says nothing, only how far you
 * then move. Samples are throttled to roughly one per 16 ms; sending every pointer event floods
 * the link, and the client collapses stale positions if it falls behind anyway.
 *
 * Nothing at all is sent until the finger has travelled past the platform's touch slop. That is
 * what stops the phantom scrolling: a tap is never perfectly still, and an earlier version opened
 * the gesture on touch-down and then forwarded every wobble, so a thumb that rolled a few pixels
 * while pressing sent the TV a swipe nobody asked for. Now a press that stays inside slop is only
 * ever a click, and one that leaves it opens the drag from where the finger actually started
 * rather than from wherever it happened to cross the threshold.
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
                    val centre = CompanionClient.TOUCHPAD_SIZE / 2f
                    val scaleX = CompanionClient.TOUCHPAD_SIZE / size.width.toFloat() * SWIPE_GAIN
                    val scaleY = CompanionClient.TOUCHPAD_SIZE / size.height.toFloat() * SWIPE_GAIN
                    val startX = down.position.x
                    val startY = down.position.y

                    fun padX(x: Float) =
                        (centre + (x - startX) * scaleX).toInt().coerceIn(0, CompanionClient.TOUCHPAD_SIZE)
                    fun padY(y: Float) =
                        (centre + (y - startY) * scaleY).toInt().coerceIn(0, CompanionClient.TOUCHPAD_SIZE)

                    var dragging = false
                    var lastSent = 0L
                    var lastX = startX
                    var lastY = startY

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            lastX = change.position.x
                            lastY = change.position.y

                            if (!change.pressed) {
                                if (dragging) {
                                    vm.touch(padX(lastX), padY(lastY), TouchPhase.Release)
                                    dragging = false
                                } else {
                                    // Never left slop, so it was a tap all along and the TV has
                                    // heard nothing about it yet.
                                    vm.click()
                                }
                                break
                            }

                            if (!dragging) {
                                val dx = lastX - startX
                                val dy = lastY - startY
                                if (kotlin.math.hypot(dx, dy) < slop) continue
                                dragging = true
                                // Opens at the centre by construction, since this is the origin.
                                vm.touch(padX(startX), padY(startY), TouchPhase.Press)
                            }

                            val now = System.currentTimeMillis()
                            if (now - lastSent >= 16) {
                                vm.touch(padX(lastX), padY(lastY), TouchPhase.Hold)
                                lastSent = now
                            }
                        }
                    } finally {
                        // The finger going away without a Release is not hypothetical: the
                        // pointer can be cancelled, and the pad itself is swapped out the moment
                        // the connection drops or the drawer opens. Leaving mid-drag would leave
                        // the television believing a finger is still down — the touchpad's
                        // version of a stuck key.
                        if (dragging) vm.touch(padX(lastX), padY(lastY), TouchPhase.Release)
                    }
                }
            },
    )
}

/**
 * Up/down/left/right around a centre Select, filling everything it is given.
 *
 * The buttons were fixed at seven grid units by four and floated in the middle of the panel,
 * which left most of the pad area doing nothing and the arrows small enough to miss while
 * looking at the television rather than at the phone. They are weighted now: three rows, three
 * columns, every cell as large as the space allows. Nothing here has a border — the layout is
 * the affordance, and on a panel this size an outline around a target that already fills a third
 * of the screen is decoration.
 *
 * The corners are deliberately dead rather than mapped to the nearest arrow. A diagonal thumb on
 * a remote means "I was not sure", and guessing on its behalf is how the focus ends up somewhere
 * nobody chose.
 */
@Composable
private fun DirectionPad(vm: RemoteViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Spacer(Modifier.weight(1f))
            PadButton(Modifier.weight(2f).fillMaxHeight(), { vm.press(HidCommand.Up) }) {
                ArrowIcon(R.drawable.ic_up_white)
            }
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            PadButton(Modifier.weight(1f).fillMaxHeight(), { vm.press(HidCommand.Left) }) {
                ArrowIcon(R.drawable.ic_back_white)
            }
            PadButton(Modifier.weight(2f).fillMaxHeight(), { vm.press(HidCommand.Select) }) {
                Text(
                    "OK",
                    style = MaterialTheme.typography.headlineMedium,
                    color = LightColors.Content,
                )
            }
            PadButton(Modifier.weight(1f).fillMaxHeight(), { vm.press(HidCommand.Right) }) {
                ArrowIcon(R.drawable.ic_arrow_right_white)
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Spacer(Modifier.weight(1f))
            PadButton(Modifier.weight(2f).fillMaxHeight(), { vm.press(HidCommand.Down) }) {
                ArrowIcon(R.drawable.ic_down_white)
            }
            Spacer(Modifier.weight(1f))
        }
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
            .size(3.4f.gridDp())
            .graphicsLayer(rotationZ = rotation),
    )
}

@Composable
private fun PadButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.lightClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// Reconnecting automatically was tried and removed: a failed connect goes
// Connecting -> Disconnected, which is a state *change*, so an effect keyed on the
// connection state retries forever against a TV that is simply switched off. The Retry row
// above is deliberate instead.
