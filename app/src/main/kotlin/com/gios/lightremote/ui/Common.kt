package com.gios.lightremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightremote.R
import com.gios.lightremote.ui.theme.LightColors
import com.gios.lightremote.ui.theme.LightGrid
import com.gios.lightremote.ui.theme.gridDp
import com.gios.lightremote.ui.theme.lightClickable

/**
 * LightOS's top bar: 3 grid units tall, title in the `fine` style, optional back chevron.
 */
@Composable
fun LightTopBar(
    /** Null on the remote itself, where the screen is short and the title says nothing. */
    title: String?,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(LightGrid.TOP_BAR_UNITS.gridDp()),
        ) {
            if (onBack != null) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .lightClickable(onClick = onBack)
                        .padding(horizontal = LightGrid.INSET_UNITS.gridDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_back_white),
                        contentDescription = "Back",
                        tint = LightColors.Content,
                        modifier = Modifier.size(LightGrid.BAR_ICON_UNITS.gridDp()),
                    )
                }
            }
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = LightColors.Content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 3f.gridDp()),
                )
            }
            if (action != null) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(horizontal = LightGrid.INSET_UNITS.gridDp()),
                    contentAlignment = Alignment.Center,
                ) { action() }
            }
        }
        Rule()
    }
}

@Composable
fun Rule(modifier: Modifier = Modifier) =
    HorizontalDivider(modifier = modifier, color = LightColors.Rule, thickness = 1.dp)

data class BarAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * LightOS's ActionBar: 4 grid units, word buttons in the tracked-out `button` style.
 *
 * Capped at three items on purpose — that is the SDK's own limit once any item is text,
 * and beyond three the labels start truncating on a 27-unit-wide screen.
 */
@Composable
fun LightBottomBar(actions: List<BarAction>) {
    require(actions.size <= 3) { "LightOS allows at most three text items in a bottom bar" }
    Column {
        Rule()
        Row(
            Modifier
                .fillMaxWidth()
                .height(LightGrid.BOTTOM_BAR_UNITS.gridDp()),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEachIndexed { index, action ->
                if (index > 0) {
                    Box(
                        Modifier
                            .padding(vertical = 0.6f.gridDp())
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(LightColors.Rule),
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .lightClickable(enabled = action.enabled, onClick = action.onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (action.enabled) LightColors.Content else LightColors.Faint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

data class BarIcon(
    @androidx.annotation.DrawableRes val icon: Int,
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The icon form of the bottom bar.
 *
 * LightOS allows up to five icon items but only three once any item is text, which is why the
 * remote's bar is icons: it needs more than three places to go. [label] is the accessibility
 * description only — nothing is drawn.
 *
 * Not an overload of [LightBottomBar]: generics erase, so both would compile to the same JVM
 * signature.
 */
@Composable
fun LightIconBar(icons: List<BarIcon>) {
    require(icons.size <= 5) { "LightOS allows at most five icon items in a bottom bar" }
    Column {
        Rule()
        Row(
            Modifier
                .fillMaxWidth()
                .height(LightGrid.BOTTOM_BAR_UNITS.gridDp()),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icons.forEach { item ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .lightClickable(enabled = item.enabled, onClick = item.onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(item.icon),
                        contentDescription = item.label,
                        tint = if (item.enabled) LightColors.Content else LightColors.Faint,
                        modifier = Modifier.size(LightGrid.BAR_ICON_UNITS.gridDp()),
                    )
                }
            }
        }
    }
}

/** A full-width list row: label in `copy`, sub-label in `detail` underneath. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LightRow(
    label: String,
    sub: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { base ->
                when {
                    // combinedClickable is the only way to get a long press, and it brings
                    // back the ripple unless indication is nulled out explicitly.
                    onClick != null && onLongClick != null -> base.combinedClickable(
                        interactionSource = null,
                        indication = null,
                        enabled = enabled,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                    onClick != null -> base.lightClickable(enabled = enabled, onClick = onClick)
                    else -> base
                }
            }
            .padding(horizontal = LightGrid.INSET_UNITS.gridDp(), vertical = 0.7f.gridDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) LightColors.Content else LightColors.Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = LightColors.ContentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun CenteredMessage(text: String, sub: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 2f.gridDp()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = LightColors.Content,
            textAlign = TextAlign.Center,
        )
        if (sub != null) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = LightColors.ContentSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 0.5f.gridDp()),
            )
        }
    }
}

/**
 * An inline banner rather than a dialog.
 *
 * A Material dialog would draw a scrim over pure black and end up with no visible edge, and
 * on a screen this size a modal covering the remote is worse than a line of text above it.
 */
@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .lightClickable(onClick = onDismiss)
            .padding(horizontal = LightGrid.INSET_UNITS.gridDp(), vertical = 0.5f.gridDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = LightColors.Content,
            modifier = Modifier.weight(1f),
        )
        Text("✕", style = MaterialTheme.typography.bodySmall, color = LightColors.ContentSecondary)
    }
}
