package dev.firetvonwatch.wear.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.protocol.ShellCommandBuilder
import dev.firetvonwatch.wear.R
import dev.firetvonwatch.wear.input.rememberHaptics
import dev.firetvonwatch.wear.input.rememberTextInputLauncher
import dev.firetvonwatch.wear.ui.RemoteUiState
import dev.firetvonwatch.wear.ui.RemoteViewModel
import dev.firetvonwatch.wear.ui.common.KeyButton

@Composable
fun KeyboardScreen(vm: RemoteViewModel, ui: RemoteUiState) {
    var text by rememberSaveable { mutableStateOf("") }
    val haptics = rememberHaptics(ui.settings.hapticsEnabled)
    val supported = text.isEmpty() || ShellCommandBuilder.isSupportedText(text)
    val openInput = rememberTextInputLauncher(stringResource(R.string.keyboard_input)) { text = it }
    val listState = rememberTransformingLazyColumnState()

    val send: (Boolean) -> Unit = { enter ->
        haptics.confirm()
        vm.sendText(text, enter)
        text = ""
    }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.keyboard_title)) } }
            item {
                Button(
                    onClick = openInput,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(painterResource(R.drawable.ic_keyboard), contentDescription = null) },
                    secondaryLabel = if (text.isNotEmpty()) {
                        { Text(text, maxLines = 2) }
                    } else {
                        null
                    },
                    label = { Text(stringResource(R.string.keyboard_input)) },
                )
            }
            if (!supported) {
                item {
                    Text(
                        stringResource(R.string.keyboard_ascii_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                Button(
                    onClick = { send(false) },
                    enabled = text.isNotEmpty() && supported,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(painterResource(R.drawable.ic_send), contentDescription = null) },
                    label = { Text(stringResource(R.string.keyboard_send)) },
                )
            }
            item {
                Button(
                    onClick = { send(true) },
                    enabled = text.isNotEmpty() && supported,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(stringResource(R.string.keyboard_send_enter)) },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyButton(R.drawable.ic_backspace, stringResource(R.string.key_delete), {
                        haptics.tap(); vm.press(RemoteKey.DELETE)
                    }, size = 44.dp)
                    KeyButton(R.drawable.ic_check, stringResource(R.string.key_enter), {
                        haptics.tap(); vm.press(RemoteKey.ENTER)
                    }, size = 44.dp)
                }
            }
            item {
                Text(
                    stringResource(R.string.keyboard_hint),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
