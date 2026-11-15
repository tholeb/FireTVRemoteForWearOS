package dev.firetvonwatch.wear.ui.setup

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.firetvonwatch.domain.model.ConnectionState
import dev.firetvonwatch.domain.model.DiscoveredDevice
import dev.firetvonwatch.wear.R
import dev.firetvonwatch.wear.input.PermissionState
import dev.firetvonwatch.wear.input.rememberTextInputLauncher
import dev.firetvonwatch.wear.ui.RemoteUiState
import dev.firetvonwatch.wear.ui.RemoteViewModel
import dev.firetvonwatch.wear.ui.SetupViewModel
import dev.firetvonwatch.wear.ui.common.errorText

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
fun OnboardingScreen(
    permission: PermissionState,
    hasDevices: Boolean,
    onFindTv: () -> Unit,
    onOpenRemote: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.onboarding_title)) } }
            item { Body("1. " + stringResource(R.string.onboarding_step1)) }
            item { Body("2. " + stringResource(R.string.onboarding_step2)) }
            item { Body("3. " + stringResource(R.string.onboarding_step3)) }
            if (!permission.granted) {
                item { Body(stringResource(R.string.permission_rationale)) }
                item {
                    Button(onClick = permission::launchRequest, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.grant_permission))
                    }
                }
            } else {
                item {
                    Button(
                        onClick = onFindTv,
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                        label = { Text(stringResource(R.string.find_tv)) },
                    )
                }
                if (hasDevices) {
                    item {
                        Button(onClick = onOpenRemote, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.filledTonalButtonColors()) {
                            Text(stringResource(R.string.open_remote))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoverScreen(vm: SetupViewModel, permission: PermissionState, onSelected: () -> Unit) {
    val state by vm.discover.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(permission.granted) { if (permission.granted) vm.startDiscovery() }
    DisposableEffect(Unit) { onDispose { vm.stopDiscovery() } }

    val enterIp = rememberTextInputLauncher(stringResource(R.string.enter_ip_label)) { input ->
        val ok = vm.selectManual(input) { host -> resources.getString(R.string.manual_device_name, host) }
        if (ok) onSelected() else Toast.makeText(context, resources.getString(R.string.invalid_ip, input), Toast.LENGTH_SHORT).show()
    }
    // Subnet-scan hits have no name of their own; store a translated generic one.
    val genericName = stringResource(R.string.adb_device)
    val select: (DiscoveredDevice) -> Unit = {
        vm.select(it.copy(name = it.name.ifBlank { genericName }))
        onSelected()
    }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.discover_title)) } }
            if (!permission.granted) {
                item { Body(stringResource(R.string.permission_rationale)) }
                item { Button(onClick = permission::launchRequest) { Text(stringResource(R.string.grant_permission)) } }
            }
            if (state.scanning) {
                item { CircularProgressIndicator(Modifier.size(28.dp)) }
                item { Body(stringResource(R.string.scanning)) }
            }
            items(state.found) { device ->
                Button(
                    onClick = { select(device) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(painterResource(R.drawable.ic_tv), contentDescription = null) },
                    secondaryLabel = { Text(device.host) },
                    label = { Text(device.name.ifBlank { genericName }, maxLines = 1) },
                )
            }
            if (state.noWifi) item { Body(stringResource(R.string.error_no_wifi)) }
            if (!state.scanning && state.found.isEmpty() && !state.noWifi && permission.granted) {
                item { Body(stringResource(R.string.nothing_found)) }
            }
            item {
                Button(
                    onClick = enterIp,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(painterResource(R.drawable.ic_keyboard), contentDescription = null) },
                    label = { Text(stringResource(R.string.enter_ip)) },
                )
            }
            if (!state.scanning && permission.granted) {
                item {
                    Button(
                        onClick = vm::startDiscovery,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledVariantButtonColors(),
                        icon = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null) },
                        label = { Text(stringResource(R.string.rescan)) },
                    )
                }
            }
        }
    }
}

@Composable
fun PairScreen(setupVm: SetupViewModel, remoteVm: RemoteViewModel, ui: RemoteUiState, onDone: () -> Unit) {
    val target by setupVm.pairing.collectAsStateWithLifecycle()
    val fingerprint by remoteVm.fingerprint.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(Unit) {
        remoteVm.loadFingerprint()
        setupVm.pair()
    }

    val connection = ui.connection
    val relevant = when (connection) {
        is ConnectionState.Connected -> connection.device.id == target?.id
        is ConnectionState.Connecting -> connection.device.id == target?.id
        is ConnectionState.AwaitingAuthorization -> connection.device.id == target?.id
        is ConnectionState.Failed -> connection.device.id == target?.id
        ConnectionState.Disconnected -> false
    }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text(target?.name ?: stringResource(R.string.pair_title), maxLines = 1) } }
            when {
                relevant && connection is ConnectionState.Connected -> {
                    item { Body(stringResource(R.string.paired)) }
                    item { Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.start)) } }
                }
                relevant && connection is ConnectionState.AwaitingAuthorization -> {
                    item { CircularProgressIndicator(Modifier.size(28.dp)) }
                    item { Text(stringResource(R.string.state_awaiting_auth_title), style = MaterialTheme.typography.titleMedium) }
                    item { Body(stringResource(R.string.state_awaiting_auth_body)) }
                    fingerprint?.let { fp ->
                        item {
                            // Extra side padding: this sits near the bottom of a round screen.
                            Text(
                                stringResource(R.string.key_fingerprint, fp),
                                style = MaterialTheme.typography.bodyExtraSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                            )
                        }
                    }
                }
                relevant && connection is ConnectionState.Failed -> {
                    item { Body(errorText(connection.error)) }
                    item { Button(onClick = setupVm::pair, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry)) } }
                }
                else -> {
                    item { CircularProgressIndicator(Modifier.size(28.dp)) }
                    item { Body(stringResource(R.string.state_connecting) + " " + (target?.address ?: "")) }
                }
            }
        }
    }
}
