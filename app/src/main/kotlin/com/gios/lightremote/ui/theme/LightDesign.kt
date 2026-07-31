package com.gios.lightremote.ui.theme

import android.content.Context
import android.graphics.fonts.SystemFonts
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The LightOS design tokens, ported from `lightphone/light-sdk` (MIT).
 *
 * Everything is expressed relative to the screen instead of in fixed dp: LightOS lays out
 * on a 27-by-31 grid and scales type against a 600px vertical baseline. Hardcoding dp
 * values is what makes a sideloaded app look almost-but-not-quite native, so the grid
 * helpers below are used for every bar height, inset and icon size.
 */
object LightGrid {
    const val WIDTH = 27
    const val HEIGHT = 31

    /** Top bar is 3 units tall, bottom bar 4, horizontal inset 1, bar icons 2. */
    const val TOP_BAR_UNITS = 3f
    const val BOTTOM_BAR_UNITS = 4f
    const val INSET_UNITS = 1f
    const val BAR_ICON_UNITS = 2f
}

@Composable
fun Float.gridDp(): Dp {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return (screenWidthDp.toFloat() / LightGrid.WIDTH * this).dp
}

@Composable
fun Float.verticalGridDp(): Dp {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    return (screenHeightDp.toFloat() / LightGrid.HEIGHT * this).dp
}

private const val TYPE_BASELINE_PX = 600f

@Composable
fun Float.designSp(): TextUnit {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    return (this * screenHeightDp / TYPE_BASELINE_PX).sp
}

@Composable
fun Float.designDp(): Dp {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    return (this * screenHeightDp / TYPE_BASELINE_PX).dp
}

/** The three colours LightOS actually uses. No accents, no dividers of its own. */
object LightColors {
    val Background = Color.Black
    val Content = Color.White
    val ContentSecondary = Color(0xFFBBBBBB)

    /** Only for structure this app adds — rules and the trackpad outline. */
    val Rule = Color(0xFF2A2A2A)
    val Faint = Color(0xFF5E5E5E)
}

/** LightOS phones ship Akkurat; pull it from the system so the app matches the chrome. */
fun akkuratFamilyOrDefault(): FontFamily = runCatching {
    val fonts = SystemFonts.getAvailableFonts()
        .filter { it.file?.name?.startsWith("Akkurat", ignoreCase = true) == true }
        .mapNotNull { font ->
            val file = font.file ?: return@mapNotNull null
            val style = if (font.style.slant != 0) FontStyle.Italic else FontStyle.Normal
            Font(file = file, weight = FontWeight(font.style.weight), style = style)
        }
    if (fonts.isNotEmpty()) FontFamily(fonts) else FontFamily.Default
}.getOrDefault(FontFamily.Default)

/**
 * The LP3 type scale, by name, in design pixels.
 *
 * Mapped onto Material's slots so plain `Text` picks the right one up: title -> displayLarge,
 * subtitle -> displayMedium, heading -> headlineMedium, subheading -> titleMedium,
 * copy -> bodyLarge, button -> labelLarge, paragraph -> bodyMedium, detail -> bodySmall,
 * fine -> labelMedium, superfine -> labelSmall.
 */
/** One entry in the scale. Separate function because the size helpers are composable. */
@Composable
private fun scaledStyle(
    family: FontFamily,
    px: Float,
    weight: FontWeight,
    trackingPercent: Float = 0f,
) = TextStyle(
    fontFamily = family,
    fontSize = px.designSp(),
    fontWeight = weight,
    lineHeight = (px * 1.10f).designSp(),
    letterSpacing = (px * trackingPercent).designSp(),
)

@Composable
private fun lightTypography(family: FontFamily): Typography {
    @Composable
    fun style(px: Float, weight: FontWeight, trackingPercent: Float = 0f) =
        scaledStyle(family, px, weight, trackingPercent)

    return Typography(
        displayLarge = style(115f, FontWeight.Light),
        displayMedium = style(52f, FontWeight.Light),
        headlineMedium = style(38f, FontWeight.Light),
        titleMedium = style(30f, FontWeight.Normal, trackingPercent = 0.03f),
        bodyLarge = style(30f, FontWeight.Normal),
        // Bar labels are tracked out 15%, which is the single most recognisable thing
        // about LightOS typography.
        labelLarge = style(30f, FontWeight.Normal, trackingPercent = 0.15f),
        bodyMedium = style(24.5f, FontWeight.Normal),
        bodySmall = style(20f, FontWeight.Normal),
        labelMedium = style(25f, FontWeight.Normal),
        labelSmall = style(16f, FontWeight.Normal),
    )
}

@Composable
fun LightRemoteTheme(content: @Composable () -> Unit) {
    val family = remember { akkuratFamilyOrDefault() }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = LightColors.Content,
            onPrimary = LightColors.Background,
            background = LightColors.Background,
            onBackground = LightColors.Content,
            surface = LightColors.Background,
            onSurface = LightColors.Content,
            surfaceVariant = Color(0xFF141414),
            onSurfaceVariant = LightColors.ContentSecondary,
        ),
        typography = lightTypography(family),
        content = content,
    )
}

private object LightHaptics {

    /** 45ms — tuned for the LP3's slow motor, per the SDK. The ordinary button tick. */
    fun click(context: Context) = pulse(context, 45, VibrationEffect.DEFAULT_AMPLITUDE)

    /** A hint rather than a confirmation: something is counting, nothing has happened yet. */
    fun light(context: Context) = pulse(context, 20, 70)

    /** It fired. Deliberately unmistakable through a pocket. */
    fun heavy(context: Context) = pulse(context, 90, 255)

    /**
     * A continuous buzz that climbs from noticeable to insistent across [durationMs].
     *
     * One waveform rather than a loop of pulses: the motor is handed the whole ramp up front,
     * so it starts the instant the finger lands and needs nothing scheduled per frame. Call
     * [stop] when the finger lifts, which cuts it wherever it had got to.
     */
    fun ramp(context: Context, durationMs: Long) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        runCatching {
            val steps = 24
            val slice = (durationMs / steps).coerceAtLeast(8)
            val timings = LongArray(steps) { slice }
            if (vibrator.hasAmplitudeControl()) {
                // Starts at 55 rather than near zero: it has to be felt immediately, or the
                // button reads as unresponsive for the first third of a second.
                val amplitudes = IntArray(steps) { i ->
                    (55 + (200.0 * i / (steps - 1))).toInt().coerceIn(1, 255)
                }
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                // No amplitude control, so climb by lengthening the on-phase instead: the
                // gaps shrink until it reads as continuous. Built from durationMs rather than
                // written out, or it would stop buzzing early whenever the hold gets longer.
                //
                // createWaveform without amplitudes reads the timings as OFF, ON, OFF, ON…,
                // so every pair is one pulse and the leading zero is what makes it start on.
                val pulses = 12
                val slice = (durationMs / pulses).coerceAtLeast(20)
                val timings = ArrayList<Long>(pulses * 2)
                for (i in 0 until pulses) {
                    val fraction = 0.3 + 0.6 * i / (pulses - 1).toDouble()
                    val on = (slice * fraction).toLong().coerceIn(6L, slice)
                    timings.add(if (i == 0) 0L else (slice - on).coerceAtLeast(4L))
                    timings.add(on)
                }
                vibrator.vibrate(VibrationEffect.createWaveform(timings.toLongArray(), -1))
            }
        }
    }

    fun stop(context: Context) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        runCatching { vibrator.cancel() }
    }

    private fun pulse(context: Context, milliseconds: Long, amplitude: Int) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        runCatching {
            // Motors without amplitude control reject a specific level, so fall back to the
            // default strength. The durations still differ, which keeps light and heavy
            // distinguishable even then.
            val level = if (
                amplitude == VibrationEffect.DEFAULT_AMPLITUDE || vibrator.hasAmplitudeControl()
            ) {
                amplitude
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, level))
        }
    }
}

/**
 * Clickable in the LightOS idiom: no ripple, no state layer, and the haptic fires on
 * finger-*down* rather than on click.
 *
 * That timing is the whole point for a remote control. The press has to be confirmed in the
 * hand the instant the finger lands, because the eyes are on the television, not the phone.
 */
fun Modifier.lightClickable(
    enabled: Boolean = true,
    haptics: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    val useHaptics = enabled && haptics
    pointerInput(useHaptics) {
        if (!useHaptics) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            LightHaptics.click(context)
        }
    }.clickable(
        interactionSource = null,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * The same, with a long press.
 *
 * Exists because `combinedClickable` is the only way to get a hold, and reaching for it
 * directly loses the haptic that [lightClickable] adds — which is exactly what happened to
 * the bottom bar: the three buttons that carry a hold were the three that did not tick.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.lightCombinedClickable(
    enabled: Boolean = true,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            LightHaptics.click(context)
        }
    }.combinedClickable(
        interactionSource = null,
        indication = null,
        enabled = enabled,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}

/**
 * Fires only after the finger has been held for [durationMs]. A shorter press does nothing.
 *
 * For destructive-ish buttons that sit in a bar you brush past — power being the one.
 *
 * The vibration *is* the progress bar. It starts the instant the finger lands, climbs for as
 * long as the finger stays put, and stops dead when it lifts — so letting go early feels like
 * abandoning something rather than like nothing having happened. A heavy thump marks the
 * moment it fires. That matters more here than anywhere else in the app: the usual way to
 * check whether a remote did anything is to look at the television, and the television is the
 * thing being switched off.
 */
fun Modifier.lightHoldable(
    durationMs: Long = 3_000,
    enabled: Boolean = true,
    onHold: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    pointerInput(enabled, durationMs) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            LightHaptics.ramp(context, durationMs)
            // Null means the timeout won rather than the finger lifting, so it was held.
            val liftedEarly = withTimeoutOrNull(durationMs) { waitForUpOrCancellation() }
            LightHaptics.stop(context)
            if (liftedEarly == null) {
                LightHaptics.heavy(context)
                onHold()
                // Swallow the release so it cannot read as a second gesture.
                waitForUpOrCancellation()
            }
        }
    }
}

/** Haptic tick without a click, for the repeating parts of a gesture. */
fun tick(context: Context) = LightHaptics.click(context)
