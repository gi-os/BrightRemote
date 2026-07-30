package com.gios.lightremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightremote.ui.theme.LightColors
import com.gios.lightremote.ui.theme.LightGrid
import com.gios.lightremote.ui.theme.gridDp

/**
 * Type into whatever field is focused on the TV.
 *
 * The LP3's own keyboard does the typing — this screen is just a buffer plus a Send. Text
 * is sent in one go rather than character by character: each `_tiC` event restarts the
 * remote-text session to get a current session UUID, and doing that per keystroke would
 * mean three round trips per letter.
 */
@Composable
fun KeyboardScreen(vm: RemoteViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        vm.loadFieldText()
        focus.requestFocus()
        keyboard?.show()
    }

    Scaffold(
        containerColor = LightColors.Background,
        topBar = { LightTopBar("Type", onBack = onBack) },
        bottomBar = {
            LightBottomBar(
                listOf(
                    BarAction("Back") { onBack() },
                    BarAction("Replace", enabled = draft.isNotEmpty()) {
                        vm.sendText(draft, replace = true)
                        draft = ""
                    },
                    BarAction("Send", enabled = draft.isNotEmpty()) {
                        vm.sendText(draft, replace = false)
                        draft = ""
                    },
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { ErrorBanner(it) { vm.dismissError() } }

            Text(
                when (val current = state.fieldText) {
                    null -> "No text field focused on the TV"
                    "" -> "Field is empty"
                    else -> "Field: $current"
                },
                style = MaterialTheme.typography.bodySmall,
                color = LightColors.ContentSecondary,
                maxLines = 2,
                modifier = Modifier.padding(
                    horizontal = LightGrid.INSET_UNITS.gridDp(),
                    vertical = 0.5f.gridDp(),
                ),
            )

            // LightOS underlines its text fields at 3 design px across 80% of the width; no
            // filled container, no floating label.
            Column(Modifier.padding(horizontal = LightGrid.INSET_UNITS.gridDp())) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = TextStyle(
                        fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        color = LightColors.Content,
                    ),
                    cursorBrush = SolidColor(LightColors.Content),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .padding(vertical = 0.4f.gridDp()),
                )
                Box(
                    Modifier
                        .fillMaxWidth(0.8f)
                        .height(2.dp)
                        .background(LightColors.Content),
                )
            }

            Text(
                "Sends to the focused search or text field on the Apple TV.",
                style = MaterialTheme.typography.labelSmall,
                color = LightColors.Faint,
                modifier = Modifier.padding(
                    horizontal = LightGrid.INSET_UNITS.gridDp(),
                    vertical = 0.6f.gridDp(),
                ),
            )
        }
    }
}
