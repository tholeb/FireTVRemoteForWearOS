package dev.firetvonwatch.wear.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import dev.firetvonwatch.domain.model.ConnectionState
import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.model.RemoteSettings
import dev.firetvonwatch.domain.model.RotaryMode
import dev.firetvonwatch.wear.R
import dev.firetvonwatch.wear.ui.RemoteUiState
import dev.firetvonwatch.wear.ui.RemoteViewModel

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyExtraSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
}

/** Short explanation under a setting's name. */
@Composable
private fun Explanation(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 7)
}

@Composable
private fun SettingSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: Int, explanation: Int) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        secondaryLabel = { Explanation(stringResource(explanation)) },
        label = { Text(stringResource(label), maxLines = 2) },
    )
}

@Composable
fun SettingsScreen(vm: RemoteViewModel, ui: RemoteUiState, onDevices: () -> Unit) {
    val settings = ui.settings
    val fingerprint by vm.fingerprint.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    val listState = rememberTransformingLazyColumnState()
    LaunchedEffect(Unit) { vm.loadFingerprint() }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.settings_title)) } }
            item {
                Button(
                    onClick = onDevices,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(painterResource(R.drawable.ic_tv), contentDescription = null) },
                    secondaryLabel = {
                        Explanation(listOfNotNull(ui.activeDevice?.address, stringResource(R.string.settings_device_desc)).joinToString("\n"))
                    },
                    label = { Text(ui.activeDevice?.name ?: stringResource(R.string.settings_no_device), maxLines = 1) },
                )
            }
            (ui.connection as? ConnectionState.Connected)?.info?.let { info ->
                item {
                    val method = if (info.fastInput) stringResource(R.string.settings_fast_input) else info.inputDialect.prefix
                    Note(stringResource(R.string.settings_tv_info, info.model, info.sdkInt, method))
                }
            }
            item {
                SettingSwitch(
                    checked = settings.volumeControlEnabled,
                    onChange = { on -> vm.updateSettings { it.copy(volumeControlEnabled = on) } },
                    label = R.string.settings_volume_control,
                    explanation = R.string.settings_volume_control_desc,
                )
            }
            if (settings.volumeControlEnabled) {
                item {
                    SettingSwitch(
                        checked = settings.rotaryMode == RotaryMode.VOLUME,
                        onChange = { volume ->
                            vm.updateSettings { it.copy(rotaryMode = if (volume) RotaryMode.VOLUME else RotaryMode.NAVIGATE) }
                        },
                        label = R.string.settings_rotary_volume,
                        explanation = R.string.settings_rotary_volume_desc,
                    )
                }
            }
            item {
                SettingSwitch(
                    checked = settings.hapticsEnabled,
                    onChange = { on -> vm.updateSettings { it.copy(hapticsEnabled = on) } },
                    label = R.string.settings_haptics,
                    explanation = R.string.settings_haptics_desc,
                )
            }
            item {
                SettingSwitch(
                    checked = settings.startInTouchpad,
                    onChange = { on -> vm.updateSettings { it.copy(startInTouchpad = on) } },
                    label = R.string.settings_touchpad,
                    explanation = R.string.settings_touchpad_desc,
                )
            }
            item {
                SettingSwitch(
                    checked = settings.swipeBackCloses,
                    onChange = { on -> vm.updateSettings { it.copy(swipeBackCloses = on) } },
                    label = R.string.settings_swipe_back,
                    explanation = R.string.settings_swipe_back_desc,
                )
            }
            item {
                Button(
                    onClick = { vm.updateSettings { it.copy(idleTimeoutSeconds = nextIdleTimeout(it.idleTimeoutSeconds)) } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = { Explanation(stringResource(R.string.settings_idle_desc)) },
                    label = { Text(stringResource(R.string.settings_idle_label, settings.idleTimeoutSeconds), maxLines = 2) },
                )
            }
            item {
                Button(
                    onClick = { vm.press(RemoteKey.SLEEP) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(painterResource(R.drawable.ic_power), contentDescription = null) },
                    secondaryLabel = { Explanation(stringResource(R.string.settings_sleep_desc)) },
                    label = { Text(stringResource(R.string.settings_sleep)) },
                )
            }
            item {
                Button(
                    onClick = { vm.press(RemoteKey.WAKE_UP) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(painterResource(R.drawable.ic_power), contentDescription = null) },
                    secondaryLabel = { Explanation(stringResource(R.string.settings_wake_desc)) },
                    label = { Text(stringResource(R.string.settings_wake)) },
                )
            }
            item {
                Note(
                    ui.latencyMs?.let { stringResource(R.string.settings_latency, it.toInt()) }
                        ?: stringResource(R.string.settings_latency_none),
                )
            }
            fingerprint?.let { fp -> item { Note(stringResource(R.string.key_fingerprint, fp)) } }
            item {
                Button(
                    onClick = { confirmReset = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledVariantButtonColors(),
                    secondaryLabel = { Explanation(stringResource(R.string.settings_reset_key_desc)) },
                    label = { Text(stringResource(R.string.settings_reset_key)) },
                )
            }
        }
    }

    AlertDialog(
        visible = confirmReset,
        onDismissRequest = { confirmReset = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                confirmReset = false
                vm.resetKey()
            })
        },
        title = { Text(stringResource(R.string.settings_reset_key)) },
        text = { Text(stringResource(R.string.settings_reset_key_confirm)) },
    )
}

internal fun nextIdleTimeout(current: Int): Int {
    val choices = RemoteSettings.IDLE_TIMEOUT_CHOICES
    val index = choices.indexOf(current)
    return choices[(index + 1).mod(choices.size)]
}

@Composable
fun DevicesScreen(vm: RemoteViewModel, ui: RemoteUiState, onAdd: () -> Unit, onSelected: () -> Unit) {
    var toForget by remember { mutableStateOf<FireTvDevice?>(null) }
    val listState = rememberTransformingLazyColumnState()
    val activeLabel = stringResource(R.string.active)

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.devices)) } }
            items(ui.devices.orEmpty()) { device ->
                val active = device.id == ui.activeDevice?.id
                Button(
                    onClick = {
                        vm.selectDevice(device.id)
                        onSelected()
                    },
                    onLongClick = { toForget = device },
                    onLongClickLabel = stringResource(R.string.forget_device, device.name),
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (active) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    icon = if (active) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = activeLabel) }
                    } else {
                        null
                    },
                    secondaryLabel = { Text(device.address) },
                    label = { Text(device.name, maxLines = 1) },
                )
            }
            item {
                Button(
                    onClick = onAdd,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledVariantButtonColors(),
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    label = { Text(stringResource(R.string.add_device)) },
                )
            }
        }
    }

    val pending = toForget
    AlertDialog(
        visible = pending != null,
        onDismissRequest = { toForget = null },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                pending?.let { vm.forgetDevice(it.id) }
                toForget = null
            })
        },
        title = { Text(stringResource(R.string.forget_device, pending?.name ?: "")) },
    )
}
