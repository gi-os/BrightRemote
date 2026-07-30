package com.gios.lightremote.ui.theme

import android.content.Context
import android.graphics.fonts.SystemFonts
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
    /** 45ms — tuned for the LP3's slow motor, per the SDK. */
    fun click(context: Context) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
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

/** Haptic tick without a click, for the repeating parts of a gesture. */
fun tick(context: Context) = LightHaptics.click(context)
