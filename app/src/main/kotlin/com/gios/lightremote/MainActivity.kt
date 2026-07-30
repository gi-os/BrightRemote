package com.gios.lightremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gios.lightremote.data.PairedDevice
import com.gios.lightremote.ui.AppsScreen
import com.gios.lightremote.ui.DevicesScreen
import com.gios.lightremote.ui.ForgetDeviceScreen
import com.gios.lightremote.ui.KeyboardScreen
import com.gios.lightremote.ui.PairScreen
import com.gios.lightremote.ui.RemoteScreen
import com.gios.lightremote.ui.RemoteViewModel
import com.gios.lightremote.ui.theme.LightRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNearbyDevicesIfNeeded()

        setContent {
            LightRemoteTheme {
                val nav = rememberNavController()
                val vm: RemoteViewModel = viewModel()

                // The selected device is held here rather than in the route: credentials
                // have no business being URL-encoded into a navigation argument.
                var managing by remember { mutableStateOf<PairedDevice?>(null) }

                NavHost(nav, startDestination = "devices") {
                    composable("devices") {
                        DevicesScreen(
                            vm = vm,
                            onOpenRemote = { device ->
                                vm.connect(device)
                                nav.navigate("remote")
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
                            onPaired = { nav.popBackStack("devices", inclusive = false) },
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
                            onBack = {
                                vm.disconnect()
                                nav.popBackStack("devices", inclusive = false)
                            },
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
