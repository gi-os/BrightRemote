package com.gios.lightremote.hw

import com.gios.light.common.hw.WheelBus
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Volume key presses on their way from the activity to the remote.
 *
 * Same shape as [WheelBus] and for the same reason: only the activity can see the key early
 * enough to swallow it, and only the screen knows what it should mean.
 *
 * `+1` for up, `-1` for down. Key repeats come through as further presses, so holding the
 * rocker walks the television's volume the way holding it walks the phone's.
 */
class VolumeBus {
    private val _presses = MutableSharedFlow<Int>(extraBufferCapacity = 32)
    val presses: SharedFlow<Int> = _presses.asSharedFlow()

    /**
     * Whether the activity should swallow the volume keys instead of letting Android have
     * them.
     *
     * Set from composition, and only while a television is actually connected. Grabbing the
     * keys unconditionally would leave the phone's own volume unreachable for as long as the
     * app is open, which is a poor trade for a remote you might have left on screen.
     */
    @Volatile
    var intercept: Boolean = false

    fun send(delta: Int) {
        _presses.tryEmit(delta)
    }
}

val LocalVolumeBus = staticCompositionLocalOf<VolumeBus?> { null }
