package com.gios.lightremote.companion

import com.gios.lightremote.proto.RtiPayloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Buttons the TV understands, as HID usage numbers on the Companion link. */
enum class HidCommand(val value: Long) {
    Up(1), Down(2), Left(3), Right(4), Menu(5), Select(6), Home(7),
    VolumeUp(8), VolumeDown(9), Siri(10), Screensaver(11), Sleep(12), Wake(13),
    PlayPause(14), ChannelIncrement(15), ChannelDecrement(16), Guide(17),
    PageUp(18), PageDown(19),
}

enum class MediaControlCommand(val value: Long) {
    Play(1), Pause(2), NextTrack(3), PreviousTrack(4), GetVolume(5), SetVolume(6),
    SkipBy(7), FastForwardBegin(8), FastForwardEnd(9), RewindBegin(10), RewindEnd(11),
}

/** Touch phases for the trackpad. */
enum class TouchPhase(val value: Long) { Press(1), Hold(3), Release(4), Click(5) }

/** What the TV is doing right now, from `FetchAttentionState` and `SystemStatus` events. */
enum class PowerState { Unknown, Off, On, Screensaver }

/**
 * Which media controls the TV currently offers. It publishes this as a bitfield in the
 * `_iMC` event, and it changes with the foreground app — Netflix playing exposes different
 * controls than the home screen, so the buttons should follow.
 */
data class MediaControlFlags(val raw: Long) {
    val play get() = raw and 0x0001L != 0L
    val pause get() = raw and 0x0002L != 0L
    val nextTrack get() = raw and 0x0004L != 0L
    val previousTrack get() = raw and 0x0008L != 0L
    val fastForward get() = raw and 0x0010L != 0L
    val rewind get() = raw and 0x0020L != 0L
    val volume get() = raw and 0x0100L != 0L
    val skipForward get() = raw and 0x0200L != 0L
    val skipBackward get() = raw and 0x0400L != 0L

    val anyPlayback get() = play || pause || nextTrack || previousTrack

    companion object {
        val None = MediaControlFlags(0)
    }
}

data class InstalledApp(val bundleId: String, val name: String)

/** How this phone introduces itself to the TV. */
data class ClientIdentity(
    val name: String = "Light Phone",
    val model: String = "iPhone14,3",
    val deviceId: String,
) {
    /** `_systemInfo` wants the id without separators and lowercased. */
    val plainId: String get() = deviceId.replace(":", "").lowercase()
}

/**
 * High-level Companion client: connect, then press buttons.
 *
 * The connect sequence is not arbitrary. `_systemInfo` has to land before anything else or
 * the device will not push status events, `_sessionStart` is what makes button presses
 * take effect, and `TVRCSessionStart` is what makes newer tvOS answer power queries at
 * all. Reordering these produces a connection that looks healthy and does nothing.
 */
class CompanionClient(
    private val identity: ClientIdentity,
    private val scope: CoroutineScope,
) {
    companion object {
        const val TOUCHPAD_SIZE = 1000
        private const val TOUCH_INTERVAL_MS = 16L
        private const val DEFAULT_SKIP_SECONDS = 15.0
    }

    private var connection: CompanionConnection? = null
    private var session: CompanionSession? = null
    private var touchBaseNanos: Long = 0

    /**
     * The combined session id from `_sessionStart`, kept for debugging.
     *
     * pyatv sends a matching `_sessionStop` on the way out. This does not: teardown happens
     * from a non-suspending [disconnect], often because the socket already died, and the
     * device drops the session when the connection closes anyway.
     */
    private var sessionId: Long = 0

    var mediaControlFlags: MediaControlFlags = MediaControlFlags.None
        private set

    var powerState: PowerState = PowerState.Unknown
        private set

    var volume: Double = 0.0
        private set

    /** Fired whenever [mediaControlFlags], [powerState] or [volume] changes. */
    var onStateChanged: (() -> Unit)? = null
    var onDisconnected: ((Throwable?) -> Unit)? = null

    val isConnected: Boolean get() = connection?.isConnected == true

    // ------------------------------------------------------------------ pairing

    /**
     * Open a connection with no credentials and ask the TV to show a PIN. The returned
     * handle is passed back to [finishPairing]; the connection stays open in between.
     */
    suspend fun startPairing(host: String, port: Int): CompanionAuth.PairSetup {
        openConnection(host, port)
        val auth = CompanionAuth(session!!)
        pendingAuth = auth
        return auth.beginPairSetup()
    }

    private var pendingAuth: CompanionAuth? = null

    suspend fun finishPairing(setup: CompanionAuth.PairSetup, pin: String): Credentials {
        val auth = pendingAuth ?: throw AuthenticationException("pairing was not started")
        val credentials = auth.completePairSetup(setup, pin, identity.name)
        // The device rebuilds its session state after pairing, so drop this socket and let
        // the caller connect cleanly with the new credentials.
        disconnect()
        return credentials
    }

    // ------------------------------------------------------------------ connecting

    /**
     * Bumped for every socket. Closing one wakes its reader on another thread, and that
     * reader's teardown used to clear [connection] and [session] unconditionally — so a
     * reader from the *previous* socket could finish just after a new one was installed and
     * wipe it out. Pairing then connecting does exactly that, back to back.
     */
    private var generation: Int = 0

    private suspend fun openConnection(host: String, port: Int) {
        disconnect()
        val myGeneration = ++generation
        Trace.step("connecting to $host:$port (gen $myGeneration)")
        val conn = CompanionConnection(host, port)
        withContext(Dispatchers.IO) { conn.connect() }
        val sess = CompanionSession(conn)
        sess.onEvent = ::handleEvent
        sess.onDisconnect = { cause ->
            if (myGeneration == generation) {
                connection = null
                session = null
                powerState = PowerState.Unknown
                mediaControlFlags = MediaControlFlags.None
                onStateChanged?.invoke()
                onDisconnected?.invoke(cause)
            } else {
                Trace.step("ignoring teardown from stale connection $myGeneration")
            }
        }
        sess.startReader(scope)
        connection = conn
        session = sess
    }

    /**
     * Names of connect steps that failed but were survivable, for diagnostics.
     */
    val connectWarnings = mutableListOf<String>()

    suspend fun connect(host: String, port: Int, credentials: Credentials) {
        openConnection(host, port)
        connectWarnings.clear()
        val sess = session!!

        // Pair-verify and the session are the two things that genuinely have to work: one
        // brings up encryption, the other makes button presses take effect. Everything after
        // is best-effort — a tvOS version without a handler for one of them should cost that
        // feature, not the whole connection.
        Trace.step("pair-verify")
        val keys = CompanionAuth(sess).verify(credentials)
        // Read straight off the local, not the field: a stale teardown could otherwise have
        // nulled it between opening the socket and getting here.
        val conn = connection ?: throw ProtocolException("connection dropped during pair-verify")
        conn.enableEncryption(keys.outputKey, keys.inputKey)
        Trace.step("encryption up")

        // The first encrypted request doubles as the canary. If it goes unanswered the
        // pairing or the session keys are wrong and nothing later will work either, so fail
        // here with something legible rather than grinding through six more timeouts.
        Trace.step("system info")
        try {
            sendSystemInfo(credentials)
        } catch (e: Exception) {
            throw ProtocolException(
                "The Apple TV accepted the pairing but ignored our first request " +
                    "(${e.message}). Try forgetting the device and pairing again.",
            )
        }

        optional("touch surface") { touchStart() }
        Trace.step("session start")
        sessionStart()
        optional("tv remote session") { tvRemoteSessionStart() }
        optional("text input") { textInputStart() }
        optional("subscriptions") {
            subscribe("_iMC")
            subscribe("SystemStatus")
            subscribe("TVSystemStatus")
        }
        optional("power state") { refreshPowerState() }
        Trace.step("connected")
    }

    private suspend fun optional(step: String, block: suspend () -> Unit) {
        runCatching { block() }.onFailure { error ->
            connectWarnings.add("$step: ${error.message ?: error::class.simpleName}")
        }
    }

    fun disconnect() {
        session?.stop()
        connection?.close()
        session = null
        connection = null
        pendingAuth = null
    }

    private fun requireSession(): CompanionSession =
        session ?: throw ProtocolException("not connected to an Apple TV")

    private suspend fun sendSystemInfo(credentials: Credentials) {
        requireSession().request(
            "_systemInfo",
            linkedMapOf(
                "_bf" to 0L,
                "_cf" to 512L,
                "_clFl" to 128L,
                // A null here stops the device pushing power-state events, so send a
                // stable identifier even though the field is nominally optional.
                "_i" to identity.plainId,
                // This has to be the *pairing* identifier from the credentials, not the
                // client's own device id. The device matches it against its paired
                // controller list; send anything else and it answers the handshake and then
                // rejects this first request, which reads as "paired but cannot connect".
                "_idsID" to credentials.clientId,
                "_pubID" to identity.deviceId,
                "_sf" to 256L,
                "_sv" to "170.18",
                "model" to identity.model,
                "name" to identity.name,
            ),
        )
    }

    private suspend fun sessionStart() {
        val localId = Random.nextLong(0, 1L shl 32)
        val response = requireSession().request(
            "_sessionStart",
            linkedMapOf("_srvT" to "com.apple.tvremoteservices", "_sid" to localId),
        )
        @Suppress("UNCHECKED_CAST")
        val content = response["_c"] as? Map<String, Any?>
        val remoteId = content?.get("_sid") as? Long ?: 0L
        sessionId = (remoteId shl 32) or localId
    }

    /**
     * Newer tvOS keeps power queries unanswered until a TV Remote Client session is
     * registered. Older versions have no handler for this at all, hence the tolerance.
     */
    private suspend fun tvRemoteSessionStart() {
        runCatching { requireSession().request("TVRCSessionStart", linkedMapOf("ProtocolVersionKey" to "1.2")) }
    }

    private suspend fun touchStart() {
        touchBaseNanos = System.nanoTime()
        startTouchPump()
        requireSession().request(
            "_touchStart",
            linkedMapOf(
                "_height" to TOUCHPAD_SIZE.toDouble(),
                "_tFl" to 0L,
                "_width" to TOUCHPAD_SIZE.toDouble(),
            ),
        )
    }

    private suspend fun subscribe(event: String) {
        runCatching { requireSession().sendEvent("_interest", linkedMapOf("_regEvents" to listOf(event))) }
    }

    // ------------------------------------------------------------------ events

    private fun handleEvent(name: String, content: Map<String, Any?>) {
        when (name) {
            "_iMC" -> {
                mediaControlFlags = MediaControlFlags(content["_mcF"] as? Long ?: 0L)
                onStateChanged?.invoke()
            }
            "SystemStatus", "TVSystemStatus" -> {
                powerState = statusToPowerState(content["state"] as? Long)
                onStateChanged?.invoke()
            }
        }
    }

    private fun statusToPowerState(state: Long?): PowerState = when (state?.toInt()) {
        0x01 -> PowerState.Off
        0x02 -> PowerState.Screensaver
        0x03, 0x04 -> PowerState.On
        else -> PowerState.Unknown
    }

    suspend fun refreshPowerState() {
        val response = requireSession().request("FetchAttentionState", emptyMap())
        @Suppress("UNCHECKED_CAST")
        val content = response["_c"] as? Map<String, Any?> ?: return
        powerState = statusToPowerState(content["state"] as? Long)
        onStateChanged?.invoke()
    }

    // ------------------------------------------------------------------ buttons

    /**
     * One button at a time.
     *
     * A press is not one message, it is a DOWN and an UP that mean nothing apart, and each of
     * them is a request that suspends waiting for its answer. Every button in the UI is its own
     * coroutine, so without this two presses that overlap put DOWN, DOWN, UP, UP on the wire:
     * the television sees the first key still held while the second arrives, treats it as a
     * repeat, and the focus travels further than the two rows you asked for. Spinning the wheel
     * does this on purpose — a step every 110 ms against a round trip that can take longer than
     * that — which is why it read as the focus jumping around.
     *
     * A mutex rather than a queue, because unlike touch samples a button press is worth waiting
     * for and there is never a reason to drop one.
     */
    private val buttonLock = Mutex()

    private suspend fun hid(down: Boolean, command: HidCommand) {
        requireSession().request(
            "_hidC",
            linkedMapOf("_hBtS" to (if (down) 1L else 2L), "_hidC" to command.value),
        )
    }

    suspend fun press(command: HidCommand) {
        // Nudging the volume by hand is an implicit unmute, so the remembered level goes.
        if (command == HidCommand.VolumeUp || command == HidCommand.VolumeDown) clearMute()
        buttonLock.withLock {
            hid(true, command)
            hid(false, command)
        }
    }

    suspend fun hold(command: HidCommand, durationMs: Long = 1000) {
        buttonLock.withLock {
            hid(true, command)
            delay(durationMs)
            hid(false, command)
        }
    }

    suspend fun doublePress(command: HidCommand) {
        press(command)
        press(command)
    }

    suspend fun turnOn() = hid(false, HidCommand.Wake)

    suspend fun turnOff() = hid(false, HidCommand.Sleep)

    /** The control-centre overlay is PageDown, oddly enough. */
    suspend fun controlCenter() = press(HidCommand.PageDown)

    // ------------------------------------------------------------------ media control

    private suspend fun mediaControl(
        command: MediaControlCommand,
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val content = linkedMapOf<String, Any?>("_mcc" to command.value)
        content.putAll(extra)
        return requireSession().request("_mcc", content)
    }

    suspend fun play() = mediaControl(MediaControlCommand.Play)
    suspend fun pause() = mediaControl(MediaControlCommand.Pause)
    suspend fun nextTrack() = mediaControl(MediaControlCommand.NextTrack)
    suspend fun previousTrack() = mediaControl(MediaControlCommand.PreviousTrack)

    /**
     * Skip by a number of seconds, negative to go back.
     *
     * The value has to be a Double even when it is a whole number of seconds: OPACK has no
     * negative integer encoding at all, so a Long here would fail to serialise.
     */
    suspend fun skipBy(seconds: Double = DEFAULT_SKIP_SECONDS) =
        mediaControl(MediaControlCommand.SkipBy, mapOf("_skpS" to seconds))

    suspend fun refreshVolume() {
        val response = mediaControl(MediaControlCommand.GetVolume)
        @Suppress("UNCHECKED_CAST")
        val content = response["_c"] as? Map<String, Any?> ?: return
        val level = content["_vol"] as? Double ?: return
        volume = level
        onStateChanged?.invoke()
    }

    suspend fun setVolume(level: Double) {
        mediaControl(MediaControlCommand.SetVolume, mapOf("_vol" to level.coerceIn(0.0, 1.0)))
        volume = level.coerceIn(0.0, 1.0)
        onStateChanged?.invoke()
    }

    /**
     * Level to restore to, or null when not muted.
     *
     * Companion has no mute command — the HID table has volume up and down and nothing else —
     * so mute is volume zero with the previous level remembered here. That means it only works
     * on a TV that reports volume control at all: where sound goes out over HDMI to a
     * receiver, the set often refuses SetVolume and the failure surfaces as an error rather
     * than as silence.
     */
    private var levelBeforeMute: Double? = null

    val isMuted: Boolean get() = levelBeforeMute != null

    suspend fun toggleMute() {
        val restore = levelBeforeMute
        if (restore != null) {
            setVolume(restore)
            levelBeforeMute = null
            return
        }
        // Read the level first: the last value we saw may be stale, or we may never have
        // asked, and muting to zero without knowing where to come back to is a trap.
        runCatching { refreshVolume() }
        // Already silent, so unmuting later needs somewhere plausible to go.
        levelBeforeMute = if (volume > 0.0) volume else 0.25
        setVolume(0.0)
    }

    /** A volume change by any other route means the user is no longer muted. */
    private fun clearMute() {
        levelBeforeMute = null
    }

    // ------------------------------------------------------------------ apps

    suspend fun appList(): List<InstalledApp> {
        val response = requireSession().request("FetchLaunchableApplicationsEvent", emptyMap())
        @Suppress("UNCHECKED_CAST")
        val content = response["_c"] as? Map<String, Any?> ?: return emptyList()
        return content.mapNotNull { (bundleId, name) ->
            val label = name as? String ?: return@mapNotNull null
            InstalledApp(bundleId, label)
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun launchApp(bundleIdOrUrl: String) {
        val key = if (bundleIdOrUrl.contains("://")) "_urlS" else "_bundleID"
        requireSession().request("_launchApp", linkedMapOf(key to bundleIdOrUrl))
    }

    // ------------------------------------------------------------------ touchpad

    private class TouchSample(
        val x: Int,
        val y: Int,
        val phase: TouchPhase,
        /** Captured when the finger was there, not when the frame goes out. */
        val nanos: Long,
    )

    /**
     * Touch samples, in order, on one consumer.
     *
     * This queue is the whole reason the trackpad behaves. Sending each sample from its own
     * coroutine looks harmless — they are dispatched in order — but every one of them then
     * suspends on its way to the socket, so they arrive in whatever order they happen to
     * finish. Out-of-order samples make the finger appear to jump backwards, and the
     * television reads that as a flick: the selection shoots up and down at random while you
     * drag steadily. One consumer means the stream on the wire is the stream your finger made.
     */
    private val touchQueue = Channel<TouchSample>(capacity = 128)

    private var touchPump: Job? = null

    private fun startTouchPump() {
        if (touchPump?.isActive == true) return
        touchPump = scope.launch {
            for (sample in touchQueue) {
                runCatching {
                    session?.sendEvent(
                        "_hidT",
                        linkedMapOf(
                            "_ns" to sample.nanos,
                            "_tFg" to 1L,
                            "_cx" to sample.x.coerceIn(0, TOUCHPAD_SIZE).toLong(),
                            "_tPh" to sample.phase.value,
                            "_cy" to sample.y.coerceIn(0, TOUCHPAD_SIZE).toLong(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Queue a touch sample. Never suspends, never fails loudly.
     *
     * If the queue is full the link is behind, and an intermediate [TouchPhase.Hold] is the
     * right thing to drop — the next one supersedes it. Press, Release and Click are not
     * droppable: losing a Release leaves the television believing a finger is still down.
     */
    fun touch(x: Int, y: Int, phase: TouchPhase) {
        val sample = TouchSample(x, y, phase, System.nanoTime() - touchBaseNanos)
        if (touchQueue.trySend(sample).isSuccess) return
        if (phase == TouchPhase.Hold) return
        // Worth a coroutine of its own to make sure it lands.
        scope.launch { runCatching { touchQueue.send(sample) } }
    }

    /**
     * A single swipe from start to end over [durationMs].
     *
     * The interpolation deliberately eases: the TV treats a burst of samples as a flick and
     * scrolls further than the gesture, so the sample rate is what tunes how far one swipe
     * travels.
     */
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long) {
        val endTime = System.nanoTime() + durationMs * 1_000_000
        var x = startX.toDouble()
        var y = startY.toDouble()
        touch(x.toInt(), y.toInt(), TouchPhase.Press)
        var now = System.nanoTime()
        while (now < endTime) {
            val remaining = (endTime - now).toDouble()
            val step = TOUCH_INTERVAL_MS * 1_000_000.0
            x += (endX - x) * step / remaining
            y += (endY - y) * step / remaining
            touch(
                x.coerceIn(0.0, TOUCHPAD_SIZE.toDouble()).toInt(),
                y.coerceIn(0.0, TOUCHPAD_SIZE.toDouble()).toInt(),
                TouchPhase.Hold,
            )
            delay(TOUCH_INTERVAL_MS)
            now = System.nanoTime()
        }
        touch(endX, endY, TouchPhase.Release)
    }

    /** A tap on the trackpad, which the TV wants as a Select press plus a click sample. */
    suspend fun click() {
        buttonLock.withLock {
            hid(true, HidCommand.Select)
            delay(20)
            hid(false, HidCommand.Select)
        }
        touch(TOUCHPAD_SIZE, TOUCHPAD_SIZE, TouchPhase.Click)
    }

    // ------------------------------------------------------------------ text input

    private var textSessionUuid: ByteArray? = null

    private suspend fun textInputStart(): String {
        val response = requireSession().request("_tiStart", emptyMap())
        @Suppress("UNCHECKED_CAST")
        val content = response["_c"] as? Map<String, Any?>
        val archive = content?.get("_tiD") as? ByteArray ?: return ""
        val state = RtiPayloads.readSessionState(archive) ?: return ""
        textSessionUuid = state.first
        return state.second
    }

    private suspend fun textInputStop() {
        runCatching { requireSession().request("_tiStop", emptyMap()) }
    }

    /**
     * Read whatever is in the focused text field on the TV, or null if nothing is focused.
     *
     * The session has to be restarted to get a fresh answer — the archive handed back by
     * `_tiStart` is a snapshot, and reusing an old session UUID silently targets a field
     * that may no longer exist.
     */
    suspend fun currentText(): String? {
        textInputStop()
        val text = textInputStart()
        return if (textSessionUuid == null) null else text
    }

    /** Append [text] to the focused field. Returns the field's new contents. */
    suspend fun typeText(text: String, clearFirst: Boolean = false): String? {
        textInputStop()
        var current = textInputStart()
        val uuid = textSessionUuid ?: return null

        if (clearFirst) {
            requireSession().sendEvent(
                "_tiC",
                linkedMapOf("_tiV" to 1L, "_tiD" to RtiPayloads.clearText(uuid)),
            )
            current = ""
        }
        if (text.isNotEmpty()) {
            requireSession().sendEvent(
                "_tiC",
                linkedMapOf("_tiV" to 1L, "_tiD" to RtiPayloads.insertText(uuid, text)),
            )
            current += text
        }
        return current
    }
}
