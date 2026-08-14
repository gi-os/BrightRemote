package com.gios.lightremote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.lightremote.companion.AuthenticationException
import com.gios.lightremote.companion.CompanionAuth
import com.gios.lightremote.companion.CompanionClient
import com.gios.lightremote.companion.Trace
import com.gios.lightremote.companion.HidCommand
import com.gios.lightremote.companion.InstalledApp
import com.gios.lightremote.companion.MediaControlFlags
import com.gios.lightremote.companion.PowerState
import com.gios.lightremote.companion.TouchPhase
import com.gios.lightremote.data.PairedDevice
import com.gios.lightremote.data.Prefs
import com.gios.lightremote.discovery.DiscoveredDevice
import com.gios.lightremote.discovery.Discovery
import com.gios.light.common.report.Failure
import com.gios.light.common.report.Reports
import com.gios.light.common.report.Symptom
import com.gios.light.common.report.Trouble
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

/**
 * Where the Companion service listens when nothing has told us otherwise.
 *
 * Only for a hand-typed address. Discovered devices carry their real port, and a television does
 * move off this one — it is the first free port from 49152 upwards, so a set that has been
 * running a while can be several higher. Which is the one thing a typed address cannot know, and
 * the reason this path is the fallback rather than the front door.
 */
private const val DEFAULT_COMPANION_PORT = 49152

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
    /**
     * What there is to send about the last drop or failed connect: the cause plus the recent
     * wire trace. Non-null is what makes the "Send error" row appear on the disconnected
     * screen — every disconnect, not just the ones the hourly report chip deigns to mention.
     */
    val reportable: String? = null,
    val reportSent: Boolean = false,
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
        // The socket is fine and the television has stopped listening. Nothing else notices this
        // — the frames go out, no reply comes back, and every button silently does nothing — so
        // it is worth one banner rather than a remote that has quietly become an ornament.
        client.onPressesIgnored = {
            _state.value = _state.value.copy(
                error = "The Apple TV is not answering. Tap Retry, or wake it with the remote.",
            )
            Trouble.record("the Apple TV stopped acknowledging buttons")
        }
        client.onDisconnected = { cause ->
            val detail = cause?.let { dropDetail(it) }
            _state.value = _state.value.copy(
                connection = ConnectionState.Disconnected,
                // A clean close is the app's own doing; only surface real failures.
                error = cause?.let { "Lost the connection: ${it.friendlyMessage()}" },
                reportable = detail ?: _state.value.reportable,
                reportSent = if (detail != null) false else _state.value.reportSent,
            )
            if (cause != null) {
                // Every unexpected drop is worth offering, not just the ones left standing
                // after the reconnects. The old rule called a self-healing drop noise — and
                // then the link died on every play press for weeks and not one report went
                // out, because each drop reconnected and disqualified itself. The chip still
                // rations itself to once an hour; the Send error row on the disconnected
                // screen is the always-available path.
                Trouble.record("the Apple TV dropped the connection", detail)
                // A link that drops on its own gets picked back up, twice, before the Retry
                // row becomes the user's problem. Wi-Fi hiccups and a TV waking from sleep
                // both look like this, and both fix themselves.
                if (lostReconnects < LOST_RECONNECTS && activeDeviceId != null) {
                    lostReconnects++
                    prefs.devices().firstOrNull { it.id == activeDeviceId }?.let { connect(it) }
                }
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
                        // By name, or by address for one paired by hand — that one has no service
                        // name to match on, so without the second clause its stored address is
                        // never refreshed even once mDNS starts working.
                        paired.firstOrNull { it.name == found.name || it.name == found.host }?.let {
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

    /**
     * Pair with a television at an address typed in by hand.
     *
     * Named after the address because that is all there is to go on — the service name arrives
     * over mDNS, and this path exists precisely because mDNS produced nothing. The name is only
     * ever an identity for the pairing and a label in the list, and if the browse starts working
     * later the device will simply appear a second time under its real name, which is honest: a
     * second pairing is what it would be.
     */
    fun beginPairingAt(host: String, port: Int? = null) =
        beginPairing(
            DiscoveredDevice(name = host, host = host, port = port ?: DEFAULT_COMPANION_PORT),
        )

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

    /**
     * Connect to [device].
     *
     * [byHand] is a tap — a device row, or Retry — and a tap always wins. An automatic attempt
     * already in flight is cancelled to make room for it, because an attempt can be grinding
     * through three TCP timeouts and a pair-verify that will never answer, and during those
     * twenty-odd seconds the old rule ("one attempt at a time, first one wins") made tapping the
     * television do *nothing at all*. Which is the whole of "it fails to reconnect": the app was
     * busy failing, silently, and refusing to be told otherwise. Ending with the user forgetting
     * the device and pairing again, because pairing is the one button that was never blocked.
     *
     * Automatic attempts still defer to each other, so a dropped link cannot stack sockets.
     */
    fun connect(device: PairedDevice, byHand: Boolean = false) {
        if (connectJob?.isActive == true) {
            if (!byHand) return
            connectJob?.cancel()
            connectJob = null
        }
        // Already on this television and healthy: the tap was navigation, not a request to tear
        // down a working session and spend two seconds rebuilding it.
        if (byHand &&
            activeDeviceId == device.id &&
            _state.value.connection == ConnectionState.Connected &&
            client.isConnected
        ) {
            return
        }
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
            var host = device.host
            var port = device.port

            suspend fun attemptConnect(): Boolean {
                val result = runCatching { client.connect(host, port, device.credentials) }
                result.exceptionOrNull()?.let { error ->
                    // A tap cancels the attempt in flight and starts its own. Letting that
                    // cancellation fall through as a failure meant the dying job wrote
                    // "StandaloneCoroutine was cancelled" into the banner, dropped the screen to
                    // Retry, and filed a bug report — all on top of the connect that replaced it,
                    // which was at that moment half way through a handshake.
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    lastError = error
                    return false
                }
                lostReconnects = 0
                _state.value = _state.value.copy(
                    connection = ConnectionState.Connected,
                    power = client.powerState,
                    controls = client.mediaControlFlags,
                    // A fresh session makes the old drop history, not evidence. Left in
                    // place, a deliberate disconnect a day later would offer to report it.
                    reportable = null,
                    reportSent = false,
                    // Surfaced rather than swallowed: a step that failed but did not stop the
                    // connection is exactly the kind of thing that is invisible until some
                    // button quietly does nothing.
                    error = client.connectWarnings.takeIf { it.isNotEmpty() }
                        ?.joinToString("; ", prefix = "Connected, but "),
                )
                return true
            }

            // The first go plus two automatic retries. A television that has just dropped the
            // link often refuses the next pair-verify for a moment while it tears down its own
            // session, and one failed attempt is not evidence of anything.
            repeat(CONNECT_ATTEMPTS) { attempt ->
                if (attempt > 0) {
                    _state.value = _state.value.copy(
                        connection = ConnectionState.Connecting,
                        error = null,
                    )
                    // Doubling, not fixed. A television tearing down its own session refuses
                    // pair-verify for as long as that takes, and three attempts 1.2 s apart all
                    // land inside the same refusal — three failures that are really one.
                    delay(RETRY_DELAY_MS shl (attempt - 1))
                }
                if (attemptConnect()) return@launch
            }

            // Nobody home at the stored address. The obvious reason is that it is no longer the
            // television's address — a DHCP lease expires while the set is unplugged and the
            // router hands it to a laptop, and the pairing is still perfectly good three metres
            // away at a number nothing has told this app about. So look the name up again and,
            // if it has moved, remember the new address and try once more there.
            //
            // Only after the retries, never instead of them: a browse is seconds of radio, and
            // the common failure is a television still finishing with the last session.
            // Not for a television paired by hand: its name *is* an address, so there is no
            // service name to look up and the browse can only ever spend six seconds finding
            // nothing. That is six seconds added to every failed connect, on every resume.
            if (lastError.movedAddressIsPlausible() && !isPlausibleIpv4(device.name)) {
                _state.value = _state.value.copy(
                    connection = ConnectionState.Connecting,
                    error = "Looking for ${device.name} on the network…",
                )
                val found = discovery.addressOf(device.name)
                if (found != null && (found.host != host || found.port != port)) {
                    Trace.step("${device.name} moved from $host to ${found.host}")
                    prefs.updateAddress(device.id, found.host, found.port)
                    host = found.host
                    port = found.port
                    // Published now rather than on success: the address in prefs has already
                    // changed, and a device row still showing the old one after a failure is the
                    // list disagreeing with what the app will do next time.
                    _state.value = _state.value.copy(error = null, paired = prefs.devices())
                    if (attemptConnect()) return@launch
                }
            }

            // Worth offering a report for. Every attempt is exhausted at this point, so this is
            // not a hiccup — and the banner it also writes is gone the moment the screen changes,
            // which is how a reconnect that never works goes unreported for a fortnight.
            val detail = lastError?.let { dropDetail(it) }
            detail?.let { Trouble.record("could not connect to the Apple TV", it) }
            _state.value = _state.value.copy(
                connection = ConnectionState.Disconnected,
                error = lastError?.friendlyMessage(),
                reportable = detail ?: _state.value.reportable,
                reportSent = if (detail != null) false else _state.value.reportSent,
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
        connect(device, byHand = true)
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

    /**
     * The app came back to the foreground.
     *
     * Nothing kept the link alive while it was away, and nothing could have: a socket does not
     * survive the phone sleeping, and the process is frozen, so there is no moment at which the
     * app could have noticed. What it *can* do is pick the connection back up the instant it is
     * looked at again — which is the difference between a remote that works when you pull the
     * phone out and one that shows a Retry row and waits to be asked.
     *
     * Only from Disconnected, so this cannot interfere with an attempt already under way, and
     * only when a television is remembered.
     */
    fun onForeground() {
        if (_state.value.connection != ConnectionState.Disconnected) return
        if (connectJob?.isActive == true) return
        // No "or the first one paired" fallback here, unlike [reconnect]. Forgetting the active
        // television clears activeDeviceId, and picking some other remembered set to connect to
        // on the next return to the app is not a thing anyone asked for.
        val id = activeDeviceId ?: prefs.lastDeviceId ?: return
        val device = prefs.devices().firstOrNull { it.id == id } ?: return
        lostReconnects = 0
        connect(device)
    }

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

    /**
     * The cause plus the recent wire narrative, ready to put in a report.
     *
     * The exception alone says "closed" and nothing else; the trace says what the last thing
     * on the wire was, which is the difference between "it disconnects when Plex starts
     * playing" being fixable and being a shrug.
     */
    private fun dropDetail(cause: Throwable): String =
        "${cause::class.java.simpleName}: ${cause.message}\n\nWire trace (newest last):\n${Trace.tail()}"

    /**
     * File the last drop as a bug report, straight from the disconnected screen.
     *
     * Deliberately not routed through [Trouble]: that raises a chip once an hour per failure,
     * which is the right manners for a feed that breaks every refresh and the wrong ones for
     * a link that needs diagnosing — the second drop in five minutes is exactly the one worth
     * sending. Queued on disk first, like every report, so it survives being offline.
     */
    fun sendDropReport() {
        val detail = _state.value.reportable ?: return
        if (_state.value.reportSent) return
        _state.value = _state.value.copy(reportSent = true)
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                Reports.submit(
                    context,
                    Reports.compose(
                        context = context,
                        symptom = Symptom.Other,
                        note = "the connection to the Apple TV dropped",
                        screen = "remote",
                        crash = null,
                        failure = Failure("keep the connection to the Apple TV", detail),
                    ),
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    reportSent = false,
                    error = error.friendlyMessage(),
                )
            }
        }
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

    /**
     * A press the caller waits for.
     *
     * The wheel needs this: it paces itself off how long a step actually takes, and it cannot do
     * that against a function that returns the instant a coroutine is launched.
     */
    suspend fun pressAwait(button: HidCommand) {
        runCatching { client.press(button) }.onFailure { error ->
            _state.value = _state.value.copy(error = error.friendlyMessage())
        }
    }
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

    var wheelHorizontal: Boolean
        get() = prefs.wheelHorizontal
        set(value) { prefs.wheelHorizontal = value }

    override fun onCleared() {
        super.onCleared()
        client.disconnect()
    }
}

/**
 * Whether this failure is the kind an address change would explain.
 *
 * Nothing answering at all, or something answering too slowly to be a set on the same LAN. A
 * pairing the television refused, or a handshake that got a real reply and then went wrong, is
 * not a moved address — the set is right there and re-browsing for it would only add six seconds
 * to a failure that has already been diagnosed.
 */
private fun Throwable?.movedAddressIsPlausible(): Boolean = when (this) {
    is java.net.ConnectException,
    is java.net.UnknownHostException,
    is java.net.NoRouteToHostException,
    is java.net.SocketTimeoutException,
    -> true
    else -> false
}

/** Protocol errors already read well; anything else gets its class name stripped. */
internal fun Throwable.friendlyMessage(): String = when (this) {
    is AuthenticationException -> message ?: "Authentication failed"
    is java.net.SocketTimeoutException -> "The Apple TV did not respond"
    is java.net.ConnectException -> "Could not reach the Apple TV — is it on this network?"
    is java.net.UnknownHostException -> "Could not find the Apple TV on the network"
    else -> message?.takeIf { it.isNotBlank() } ?: (this::class.simpleName ?: "Something went wrong")
}
