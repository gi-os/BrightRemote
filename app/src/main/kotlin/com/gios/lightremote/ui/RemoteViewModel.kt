package com.gios.lightremote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.lightremote.companion.AuthenticationException
import com.gios.lightremote.companion.CompanionAuth
import com.gios.lightremote.companion.CompanionClient
import com.gios.lightremote.companion.HidCommand
import com.gios.lightremote.companion.InstalledApp
import com.gios.lightremote.companion.MediaControlFlags
import com.gios.lightremote.companion.PowerState
import com.gios.lightremote.companion.TouchPhase
import com.gios.lightremote.data.PairedDevice
import com.gios.lightremote.data.Prefs
import com.gios.lightremote.discovery.DiscoveredDevice
import com.gios.lightremote.discovery.Discovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

enum class ConnectionState { Disconnected, Connecting, Connected }

/** One attempt plus two automatic retries, per connect. */
private const val CONNECT_ATTEMPTS = 3

/** How long to let the TV finish tearing down its side before trying again. */
private const val RETRY_DELAY_MS = 1_200L

/** Automatic reconnects after a link drops on its own, before Retry becomes manual. */
private const val LOST_RECONNECTS = 2

data class RemoteUiState(
    val discovered: List<DiscoveredDevice> = emptyList(),
    val paired: List<PairedDevice> = emptyList(),
    val scanning: Boolean = false,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val activeName: String? = null,
    val power: PowerState = PowerState.Unknown,
    val controls: MediaControlFlags = MediaControlFlags.None,
    val volume: Double = 0.0,
    val muted: Boolean = false,
    val apps: List<InstalledApp> = emptyList(),
    val appsLoading: Boolean = false,
    val pinned: Set<String> = emptySet(),
    val pairingPin: String = "",
    val pairingDeviceName: String? = null,
    val pairingBusy: Boolean = false,
    val error: String? = null,
    val fieldText: String? = null,
)

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val discovery = Discovery(app)
    private val client = CompanionClient(prefs.identity, viewModelScope)

    private val _state = MutableStateFlow(
        RemoteUiState(paired = prefs.devices(), pinned = prefs.pinnedApps()),
    )
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    private var discoveryJob: Job? = null
    private var pendingSetup: CompanionAuth.PairSetup? = null
    private var pendingDevice: DiscoveredDevice? = null
    private var activeDeviceId: String? = null

    /** The connect attempt in flight, so a second one cannot start beside it. */
    private var connectJob: Job? = null

    /**
     * Automatic reconnects since the last successful connect.
     *
     * Bounded, and reset on success. An earlier attempt at this keyed a Compose effect on the
     * connection state, which retried forever against a television that was simply switched
     * off — a failed connect goes Connecting to Disconnected, and that is a state *change*.
     * Counting the attempts here instead is what makes "give up eventually" expressible.
     */
    private var lostReconnects = 0

    init {
        client.onStateChanged = {
            _state.value = _state.value.copy(
                power = client.powerState,
                controls = client.mediaControlFlags,
                volume = client.volume,
                muted = client.isMuted,
            )
        }
        client.onDisconnected = { cause ->
            _state.value = _state.value.copy(
                connection = ConnectionState.Disconnected,
                // A clean close is the app's own doing; only surface real failures.
                error = cause?.let { "Lost the connection: ${it.friendlyMessage()}" },
            )
            // A link that drops on its own gets picked back up, twice, before the Retry row
            // becomes the user's problem. Wi-Fi hiccups and a TV waking from sleep both look
            // like this, and both fix themselves.
            if (cause != null && lostReconnects < LOST_RECONNECTS && activeDeviceId != null) {
                lostReconnects++
                prefs.devices().firstOrNull { it.id == activeDeviceId }?.let { connect(it) }
            }
        }
    }

    // ------------------------------------------------------------------ discovery

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        _state.value = _state.value.copy(scanning = true, discovered = emptyList())
        discoveryJob = viewModelScope.launch {
            discovery.devices()
                .catch { error ->
                    _state.value = _state.value.copy(
                        scanning = false,
                        error = error.friendlyMessage(),
                    )
                }
                .collect { devices ->
                    // A device already paired shows up in the paired list instead, but its
                    // address may have moved, so refresh that quietly.
                    val paired = prefs.devices()
                    devices.forEach { found ->
                        paired.firstOrNull { it.name == found.name }?.let {
                            if (it.host != found.host || it.port != found.port) {
                                prefs.updateAddress(it.id, found.host, found.port)
                            }
                        }
                    }
                    _state.value = _state.value.copy(
                        discovered = devices.filter { found -> paired.none { it.name == found.name } },
                        paired = prefs.devices(),
                    )
                }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _state.value = _state.value.copy(scanning = false)
    }

    // ------------------------------------------------------------------ pairing

    fun beginPairing(device: DiscoveredDevice) {
        stopDiscovery()
        pendingDevice = device
        _state.value = _state.value.copy(
            pairingDeviceName = device.name,
            pairingPin = "",
            pairingBusy = true,
            error = null,
        )
        viewModelScope.launch {
            runCatching { client.startPairing(device.host, device.port) }
                .onSuccess { setup ->
                    pendingSetup = setup
                    _state.value = _state.value.copy(pairingBusy = false)
                }
                .onFailure { error ->
                    pendingDevice = null
                    _state.value = _state.value.copy(
                        pairingBusy = false,
                        pairingDeviceName = null,
                        error = error.friendlyMessage(),
                    )
                }
        }
    }

    fun appendPin(digit: String) {
        val current = _state.value.pairingPin
        if (current.length >= 4) return
        _state.value = _state.value.copy(pairingPin = current + digit)
    }

    fun deletePinDigit() {
        val current = _state.value.pairingPin
        if (current.isEmpty()) return
        _state.value = _state.value.copy(pairingPin = current.dropLast(1))
    }

    fun cancelPairing() {
        pendingSetup = null
        pendingDevice = null
        client.disconnect()
        _state.value = _state.value.copy(pairingDeviceName = null, pairingPin = "", pairingBusy = false)
    }

    fun submitPin(onPaired: () -> Unit) {
        val setup = pendingSetup ?: return
        val device = pendingDevice ?: return
        val pin = _state.value.pairingPin
        if (pin.length < 4) return

        _state.value = _state.value.copy(pairingBusy = true, error = null)
        viewModelScope.launch {
            runCatching { client.finishPairing(setup, pin) }
                .onSuccess { credentials ->
                    prefs.save(
                        PairedDevice(
                            id = device.name,
                            name = device.name,
                            host = device.host,
                            port = device.port,
                            credentials = credentials,
                        ),
                    )
                    pendingSetup = null
                    pendingDevice = null
                    _state.value = _state.value.copy(
                        pairingDeviceName = null,
                        pairingPin = "",
                        pairingBusy = false,
                        paired = prefs.devices(),
                        // Drop it from "Found" in the same update. Leaving it in both lists
                        // put the same key in the device list twice, which LazyColumn treats
                        // as a fatal error rather than a duplicate row.
                        discovered = _state.value.discovered.filter { it.name != device.name },
                    )
                    onPaired()
                }
                .onFailure { error ->
                    // A wrong PIN invalidates the whole exchange; the device wants a fresh
                    // pair-setup with a newly displayed code.
                    pendingSetup = null
                    _state.value = _state.value.copy(
                        pairingBusy = false,
                        pairingPin = "",
                        pairingDeviceName = null,
                        error = error.friendlyMessage(),
                    )
                }
        }
    }

    // ------------------------------------------------------------------ connection

    fun connect(device: PairedDevice) {
        // One attempt at a time. Stacking them means two pair-verifies racing over two
        // sockets, and the loser's teardown landing on the winner's state.
        if (connectJob?.isActive == true) return
        activeDeviceId = device.id
        prefs.lastDeviceId = device.id
        _state.value = _state.value.copy(
            connection = ConnectionState.Connecting,
            activeName = device.name,
            error = null,
            apps = emptyList(),
        )
        connectJob = viewModelScope.launch {
            var lastError: Throwable? = null
            // The first go plus two automatic retries. A television that has just dropped the
            // link often refuses the next pair-verify for a moment while it tears down its own
            // session, and one failed attempt is not evidence of anything.
            repeat(CONNECT_ATTEMPTS) { attempt ->
                if (attempt > 0) {
                    _state.value = _state.value.copy(
                        connection = ConnectionState.Connecting,
                        error = null,
                    )
                    delay(RETRY_DELAY_MS)
                }
                val result = runCatching {
                    client.connect(device.host, device.port, device.credentials)
                }
                if (result.isSuccess) {
                    lostReconnects = 0
                    _state.value = _state.value.copy(
                        connection = ConnectionState.Connected,
                        power = client.powerState,
                        controls = client.mediaControlFlags,
                        // Surfaced rather than swallowed: a step that failed but did not stop
                        // the connection is exactly the kind of thing that is invisible until
                        // some button quietly does nothing.
                        error = client.connectWarnings.takeIf { it.isNotEmpty() }
                            ?.joinToString("; ", prefix = "Connected, but "),
                    )
                    return@launch
                }
                lastError = result.exceptionOrNull()
            }
            _state.value = _state.value.copy(
                connection = ConnectionState.Disconnected,
                error = lastError?.friendlyMessage(),
            )
        }
    }

    /**
     * Retry by hand.
     *
     * Cancels whatever attempt is in flight first. Without that, tapping Retry while a
     * connect was still grinding through its TCP timeout did nothing at all — which is
     * exactly what "the reconnect button doesn't work" looks like from the outside.
     */
    fun reconnect() {
        connectJob?.cancel()
        connectJob = null
        lostReconnects = 0
        val id = activeDeviceId ?: prefs.lastDeviceId
        val device = prefs.devices().firstOrNull { it.id == id }
            ?: prefs.devices().firstOrNull()
        if (device == null) {
            _state.value = _state.value.copy(error = "No paired Apple TV to reconnect to")
            return
        }
        connect(device)
    }

    /**
     * Connect to the device used last, or the only paired one.
     *
     * @return false when there is nothing paired yet, so the caller can send the user to the
     *   device list instead of showing an empty remote.
     */
    fun autoConnect(): Boolean {
        val devices = prefs.devices()
        if (devices.isEmpty()) return false
        val device = devices.firstOrNull { it.id == prefs.lastDeviceId } ?: devices.first()
        connect(device)
        return true
    }

    fun hasPairedDevices(): Boolean = prefs.devices().isNotEmpty()

    fun disconnect() {
        // Order matters: drop the device first, or the client's own teardown callback reads
        // activeDeviceId as still set and immediately reconnects what was just closed.
        connectJob?.cancel()
        connectJob = null
        activeDeviceId = null
        lostReconnects = 0
        client.disconnect()
        _state.value = _state.value.copy(connection = ConnectionState.Disconnected, activeName = null)
    }

    fun forget(device: PairedDevice) {
        if (activeDeviceId == device.id) disconnect()
        prefs.forget(device.id)
        _state.value = _state.value.copy(paired = prefs.devices())
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    // ------------------------------------------------------------------ commands

    /**
     * Every command runs through here so a dropped connection surfaces once, in the banner,
     * rather than as a crash from a coroutine nobody is watching.
     */
    private fun command(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { error ->
                _state.value = _state.value.copy(error = error.friendlyMessage())
            }
        }
    }

    fun press(button: HidCommand) = command { client.press(button) }
    fun hold(button: HidCommand) = command { client.hold(button) }
    fun playPause() = command { client.press(HidCommand.PlayPause) }
    fun skipForward() = command { client.skipBy(15.0) }
    fun skipBackward() = command { client.skipBy(-15.0) }
    fun volumeUp() = command { client.press(HidCommand.VolumeUp) }
    fun volumeDown() = command { client.press(HidCommand.VolumeDown) }
    fun toggleMute() = command { client.toggleMute() }
    fun controlCenter() = command { client.controlCenter() }
    fun click() = command { client.click() }

    fun togglePower() = command {
        if (client.powerState == PowerState.Off) client.turnOn() else client.turnOff()
    }

    /**
     * Not routed through [command]: that starts a coroutine per call, and a coroutine per
     * touch sample is what scrambled their order on the way to the socket. The client queues
     * these itself, in order, on one consumer.
     */
    fun touch(x: Int, y: Int, phase: TouchPhase) = client.touch(x, y, phase)

    fun loadApps() {
        if (_state.value.appsLoading) return
        _state.value = _state.value.copy(appsLoading = true)
        viewModelScope.launch {
            runCatching { client.appList() }
                .onSuccess { _state.value = _state.value.copy(apps = it, appsLoading = false) }
                .onFailure {
                    _state.value = _state.value.copy(
                        appsLoading = false,
                        error = it.friendlyMessage(),
                    )
                }
        }
    }

    fun launchApp(app: InstalledApp) = command { client.launchApp(app.bundleId) }

    fun togglePin(app: InstalledApp) {
        prefs.togglePin(app.bundleId)
        _state.value = _state.value.copy(pinned = prefs.pinnedApps())
    }

    fun loadFieldText() {
        viewModelScope.launch {
            runCatching { client.currentText() }
                .onSuccess { _state.value = _state.value.copy(fieldText = it) }
                .onFailure { _state.value = _state.value.copy(fieldText = null) }
        }
    }

    fun sendText(text: String, replace: Boolean) {
        _state.value = _state.value.copy(error = null)
        viewModelScope.launch {
            runCatching { client.typeText(text, clearFirst = replace) }
                .onSuccess { updated ->
                    _state.value = _state.value.copy(
                        fieldText = updated,
                        error = if (updated == null) {
                            "No text field is focused on the Apple TV"
                        } else {
                            null
                        },
                    )
                }
                .onFailure { _state.value = _state.value.copy(error = it.friendlyMessage()) }
        }
    }

    var preferTouchpad: Boolean
        get() = prefs.preferTouchpad
        set(value) { prefs.preferTouchpad = value }

    override fun onCleared() {
        super.onCleared()
        client.disconnect()
    }
}

/** Protocol errors already read well; anything else gets its class name stripped. */
internal fun Throwable.friendlyMessage(): String = when (this) {
    is AuthenticationException -> message ?: "Authentication failed"
    is java.net.SocketTimeoutException -> "The Apple TV did not respond"
    is java.net.ConnectException -> "Could not reach the Apple TV — is it on this network?"
    is java.net.UnknownHostException -> "Could not find the Apple TV on the network"
    else -> message?.takeIf { it.isNotBlank() } ?: (this::class.simpleName ?: "Something went wrong")
}
