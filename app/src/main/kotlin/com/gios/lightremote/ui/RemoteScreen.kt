package com.gios.lightremote.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightremote.R
import com.gios.lightremote.companion.CompanionClient
import com.gios.lightremote.companion.HidCommand
import com.gios.lightremote.companion.PowerState
import com.gios.lightremote.companion.TouchPhase
import com.gios.lightremote.ui.theme.LightColors
import com.gios.lightremote.ui.theme.LightGrid
import com.gios.lightremote.ui.theme.gridDp
import com.gios.lightremote.ui.theme.lightClickable
import com.gios.lightremote.ui.theme.tick

/**
 * The remote. Two faces — a D-pad and a swipe trackpad — because they suit different
 * things: the D-pad for stepping through a grid of tiles, the trackpad for long lists and
 * for scrubbing.
 */
@Composable
fun RemoteScreen(
    vm: RemoteViewModel,
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenKeyboard: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var touchpad by remember { mutableStateOf(vm.preferTouchpad) }

    Scaffold(
        containerColor = LightColors.Background,
        topBar = {
            LightTopBar(
                title = state.activeName ?: "Remote",
                onBack = onBack,
                action = {
                    // Power doubles as the connection indicator: dim when we don't know.
                    Box(
                        Modifier
                            .size(LightGrid.BAR_ICON_UNITS.gridDp())
                            .lightClickable(
                                enabled = state.connection == ConnectionState.Connected,
                            ) { vm.togglePower() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "⏻",
                            style = MaterialTheme.typography.bodyLarge,
                            color = when (state.power) {
                                PowerState.On -> LightColors.Content
                                PowerState.Screensaver -> LightColors.ContentSecondary
                                else -> LightColors.Faint
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            LightBottomBar(
                listOf(
                    BarAction(if (touchpad) "Pad" else "Keys") {
                        touchpad = !touchpad
                        vm.preferTouchpad = touchpad
                    },
                    BarAction("Apps", enabled = state.connection == ConnectionState.Connected) {
                        onOpenApps()
                    },
                    BarAction("Type", enabled = state.connection == ConnectionState.Connected) {
                        onOpenKeyboard()
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
            state.error?.let { ErrorBanner(it) { vm.dismissError() } }

            when (state.connection) {
                ConnectionState.Connecting -> CenteredMessage("Connecting…")
                ConnectionState.Disconnected -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        CenteredMessage("Not connected", "Tap Retry to reconnect.")
                    }
                    LightRow(label = "Retry", onClick = { vm.reconnect() })
                    Rule()
                }
                ConnectionState.Connected -> if (touchpad) {
                    Touchpad(vm, Modifier.weight(1f))
                    TransportRow(vm)
                } else {
                    DirectionPad(vm, Modifier.weight(1f))
                    TransportRow(vm)
                }
            }
        }
    }
}

/** Up/down/left/right around a centre Select, plus Menu and Home underneath. */
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

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 1f.gridDp()),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Holding Menu is how you get back to the home screen on a real remote, and
            // holding Home opens the app switcher, so both gestures are wired up.
            LabelButton("Menu", onClick = { vm.press(HidCommand.Menu) }, onHold = { vm.hold(HidCommand.Menu) })
            LabelButton("Home", onClick = { vm.press(HidCommand.Home) }, onHold = { vm.hold(HidCommand.Home) })
            LabelButton("Ctrl", onClick = { vm.controlCenter() })
        }
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LabelButton(label: String, onClick: () -> Unit, onHold: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(width = 6.5f.gridDp(), height = 3f.gridDp())
            .let { base ->
                if (onHold == null) {
                    base.lightClickable(onClick = onClick)
                } else {
                    base.combinedClickableNoRipple(onClick = onClick, onLongClick = onHold)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = LightColors.Content)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoRipple(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = combinedClickable(
    interactionSource = null,
    indication = null,
    onLongClick = onLongClick,
    onClick = onClick,
)

/**
 * The swipe trackpad.
 *
 * Coordinates map straight onto the TV's 1000x1000 touch surface, and samples are throttled
 * to roughly one per 16ms. Both details matter: sending every pointer event floods the link
 * and the TV reads the burst as a flick, so a small drag overshoots by several rows.
 */
@Composable
private fun Touchpad(vm: RemoteViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .padding(horizontal = 2f.gridDp())
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(1.dp, LightColors.Rule)
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
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "swipe · tap",
                style = MaterialTheme.typography.labelSmall,
                color = LightColors.Faint,
                textAlign = TextAlign.Center,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 1f.gridDp()),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            LabelButton("Menu", onClick = { vm.press(HidCommand.Menu) }, onHold = { vm.hold(HidCommand.Menu) })
            LabelButton("Home", onClick = { vm.press(HidCommand.Home) }, onHold = { vm.hold(HidCommand.Home) })
            LabelButton("Ctrl", onClick = { vm.controlCenter() })
        }
    }
}

/**
 * Transport and volume.
 *
 * The playback buttons dim when the TV says it has no media controls to offer — the `_iMC`
 * event tracks the foreground app, so on the home screen these genuinely do nothing and
 * showing them as live would just be a lie.
 */
@Composable
private fun TransportRow(vm: RemoteViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val playing = state.controls.anyPlayback

    Column {
        Rule()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 0.5f.gridDp()),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(R.drawable.ic_skip_backward_fifteen_white, enabled = playing) { vm.skipBackward() }
            IconButton(R.drawable.ic_play_white, enabled = true) { vm.playPause() }
            IconButton(R.drawable.ic_skip_forward_fifteen_white, enabled = playing) { vm.skipForward() }
            IconButton(R.drawable.ic_speaker_muted) { vm.volumeDown() }
            IconButton(R.drawable.ic_speaker_on) { vm.volumeUp() }
        }
    }
}

@Composable
private fun IconButton(resource: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 4.6f.gridDp(), height = 3f.gridDp())
            .lightClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(resource),
            contentDescription = null,
            tint = if (enabled) LightColors.Content else LightColors.Faint,
            modifier = Modifier.size(2.2f.gridDp()),
        )
    }
}
// Reconnecting automatically was tried and removed: a failed connect goes
// Connecting -> Disconnected, which is a state *change*, so an effect keyed on the
// connection state retries forever against a TV that is simply switched off. The Retry row
// above is deliberate instead.
