package com.gios.lightremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gios.lightremote.data.PairedDevice
import com.gios.lightremote.hw.LightKey
import com.gios.lightremote.hw.LightKeys
import com.gios.lightremote.hw.LocalVolumeBus
import com.gios.lightremote.hw.LocalWheelBus
import com.gios.lightremote.hw.VolumeBus
import com.gios.lightremote.hw.WheelBus
import com.gios.lightremote.ui.AppsScreen
import com.gios.lightremote.ui.DevicesScreen
import com.gios.lightremote.ui.ForgetDeviceScreen
import com.gios.lightremote.ui.KeyboardScreen
import com.gios.lightremote.ui.PairScreen
import com.gios.lightremote.ui.RemoteScreen
import com.gios.lightremote.ui.RemoteViewModel
import com.gios.lightremote.ui.theme.LightRemoteTheme
import com.gios.lightremote.report.CrashLog
import com.gios.lightremote.report.ReportOverlay

class MainActivity : ComponentActivity() {

    /** Wheel notches on their way to whichever screen is up. */
    private val wheel = WheelBus()

    /** Volume rocker presses, when a television is connected to take them. */
    private val volume = VolumeBus()

    /**
     * Every hardware key arrives here first — `DecorView` calls the window callback before
     * it walks the view hierarchy — so the wheel is read before the focused text field on
     * the Type screen can take it as a letter. Both halves of a notch are consumed: one
     * notch is a complete DOWN+UP pair, and the UP would otherwise arrive as a keypress.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }

        // The rocker drives the television while one is connected. Both halves of the press
        // are swallowed — leaving the UP to Android is what pops its volume panel over the
        // remote. Key repeats arrive as further ACTION_DOWNs, so holding it walks the volume.
        if (volume.intercept) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN) volume.send(1)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) volume.send(-1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing, before anything else can throw: the handler chains onto whatever is
        // already installed and only writes a file, so it is safe this early.
        CrashLog.install(this)

        requestNearbyDevicesIfNeeded()

        setContent {
            LightRemoteTheme {
                val nav = rememberNavController()
                val vm: RemoteViewModel = viewModel()

                // The selected device is held here rather than in the route: credentials
                // have no business being URL-encoded into a navigation argument.
                var managing by remember { mutableStateOf<PairedDevice?>(null) }

                // The remote is home. Opening the app should land on the television, not on
                // a list with one entry — the device list is a place you visit, not the
                // front door. With nothing paired yet there is no remote to show, so the
                // first launch goes straight to discovery instead.
                var launched by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (!launched) {
                        launched = true
                        if (!vm.autoConnect()) nav.navigate("devices")
                    }
                }

                // And on every return after that. Coming back to the app is the only signal a
                // frozen process gets that its socket has probably died, so it is the one place
                // the connection can be picked up without being asked to. `lifecycle` is the
                // activity's own, read directly rather than through a composition local —
                // which package that local lives in has moved between Compose versions.
                DisposableEffect(vm) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) vm.onForeground()
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }

                CompositionLocalProvider(
                    LocalWheelBus provides wheel,
                    LocalVolumeBus provides volume,
                ) {
                    NavHost(nav, startDestination = "remote") {
                        composable("devices") {
                            DevicesScreen(
                                vm = vm,
                                onBack = if (vm.hasPairedDevices()) {
                                    { nav.popBackStack("remote", inclusive = false) }
                                } else {
                                    null
                                },
                                onOpenRemote = { device ->
                                    // byHand: a tap outranks whatever attempt is grinding away.
                                    vm.connect(device, byHand = true)
                                    nav.popBackStack("remote", inclusive = false)
                                },
                                onPair = { device ->
                                    vm.beginPairing(device)
                                    nav.navigate("pair")
                                },
                                onManage = { device ->
                                    managing = device
                                    nav.navigate("forget")
                                },
                            )
                        }
                        composable("pair") {
                            PairScreen(
                                vm = vm,
                                // Straight to the remote on success — pairing is only ever done
                                // in order to use the thing.
                                onPaired = {
                                    vm.autoConnect()
                                    nav.popBackStack("remote", inclusive = false)
                                },
                                onCancel = { nav.popBackStack("devices", inclusive = false) },
                            )
                        }
                        composable("forget") {
                            val device = managing
                            if (device == null) {
                                nav.popBackStack("devices", inclusive = false)
                            } else {
                                ForgetDeviceScreen(
                                    vm = vm,
                                    device = device,
                                    onDone = {
                                        managing = null
                                        nav.popBackStack("devices", inclusive = false)
                                    },
                                )
                            }
                        }
                        composable("remote") {
                            RemoteScreen(
                                vm = vm,
                                // Keep the connection alive while browsing devices; coming
                                // straight back should not have cost a reconnect.
                                onOpenDevices = { nav.navigate("devices") },
                                onOpenApps = { nav.navigate("apps") },
                                onOpenKeyboard = { nav.navigate("keyboard") },
                            )
                        }
                        composable("apps") {
                            AppsScreen(vm = vm, onBack = { nav.popBackStack() })
                        }
                        composable("keyboard") {
                            KeyboardScreen(vm = vm, onBack = { nav.popBackStack() })
                        }
                    }
                }
                // Shake to report, the crash offer on next launch, and the app's own noticed
                // failures. A sibling, not a wrapper — the sheet is its own window, so it covers
                // the app whether or not it contains it.
                ReportOverlay()
            }
        }
    }

    /**
     * Ask for NEARBY_WIFI_DEVICES on Android 13 and later.
     *
     * Whether NsdManager actually needs this is murky — the documented Android 13 gate
     * covers Wi-Fi scanning, Aware and Direct, and mDNS browsing has historically needed no
     * permission at all. Asking costs one dialog and removes the failure mode where
     * discovery silently returns nothing; the app carries on either way, and
     * `onStartDiscoveryFailed` surfaces the problem if it turns out to be refused.
     */
    private fun requestNearbyDevicesIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.NEARBY_WIFI_DEVICES
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return
        runCatching { requestPermissions(arrayOf(permission), 1) }
    }
}
