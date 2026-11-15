package dev.firetvonwatch.wear.ui.remote

import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.firetvonwatch.domain.model.ConnectionState
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.model.RotaryMode
import dev.firetvonwatch.wear.R
import dev.firetvonwatch.wear.input.RotaryAccumulator
import dev.firetvonwatch.wear.input.rememberHaptics
import dev.firetvonwatch.wear.ui.RemoteUiState
import dev.firetvonwatch.wear.ui.RemoteViewModel
import dev.firetvonwatch.wear.ui.common.ConnectionRing
import dev.firetvonwatch.wear.ui.common.KeyButton
import dev.firetvonwatch.wear.ui.common.StatusPanel
import dev.firetvonwatch.wear.ui.common.errorText
import kotlinx.coroutines.launch

private const val PAGE_DPAD = 0
private const val PAGE_MEDIA = 1
private const val PAGE_APPS = 2

@Composable
fun RemoteScreen(
    vm: RemoteViewModel,
    ui: RemoteUiState,
    onTouchpad: () -> Unit,
    onKeyboard: () -> Unit,
    onSettings: () -> Unit,
    onDevices: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val haptics = rememberHaptics(ui.settings.hapticsEnabled)
    val press: (RemoteKey) -> Unit = { key ->
        haptics.tap()
        vm.press(key)
    }
    val focus = remember { FocusRequester() }
    val rotary = rememberRotaryHandler(
        mode = { ui.settings.rotaryModeFor(mediaPage = pagerState.currentPage == PAGE_MEDIA) },
        enabled = { pagerState.currentPage != PAGE_APPS },
        onKeys = { keys ->
            haptics.tick()
            vm.pressAll(keys)
        },
    )

    // Back (edge swipe or the watch's Back button) goes to the previous page and, unless the user
    // allowed it in Settings, never closes the remote from the first page. Home always exits.
    val scope = rememberCoroutineScope()
    BackHandler(enabled = pagerState.currentPage > 0 || !ui.settings.swipeBackCloses) {
        if (pagerState.currentPage > 0) scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    // The Apps list takes the rotary focus while it's showing; take it back elsewhere.
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != PAGE_APPS) runCatching { focus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { rotary(it.verticalScrollPixels) }
            .focusRequester(focus)
            .focusable(),
    ) {
        HorizontalPagerScaffold(pagerState = pagerState) {
            HorizontalPager(state = pagerState, modifier = Modifier.testTag("pager")) { page ->
                when (page) {
                    PAGE_DPAD -> DpadPage(ui, press, onTouchpad, onRetry = vm::retry, onDevices = onDevices)
                    PAGE_MEDIA -> MediaPage(press, onKeyboard, onSettings, volumeEnabled = ui.settings.volumeControlEnabled)
                    else -> AppsPage(vm, ui, isCurrent = pagerState.currentPage == PAGE_APPS, haptics = haptics)
                }
            }
        }
        ConnectionRing(ui.connection)
    }
}

/** Returns a rotary callback that sends one key per detent and reports whether it handled the event. */
@Composable
fun rememberRotaryHandler(
    mode: () -> RotaryMode,
    enabled: () -> Boolean = { true },
    onKeys: (List<RemoteKey>) -> Unit,
): (Float) -> Boolean {
    val context = LocalContext.current
    val accumulator = remember {
        RotaryAccumulator(pxPerStep = ViewConfiguration.get(context).scaledVerticalScrollFactor * 0.9f)
    }
    val currentMode = rememberUpdatedState(mode)
    val currentEnabled = rememberUpdatedState(enabled)
    val currentOnKeys = rememberUpdatedState(onKeys)
    return remember {
        { delta ->
            if (!currentEnabled.value()) {
                false
            } else {
                val keys = RotaryAccumulator.keysFor(accumulator.onScroll(delta), currentMode.value())
                if (keys.isNotEmpty()) currentOnKeys.value(keys)
                true
            }
        }
    }
}

@Composable
private fun DpadPage(
    ui: RemoteUiState,
    press: (RemoteKey) -> Unit,
    onTouchpad: () -> Unit,
    onRetry: () -> Unit,
    onDevices: () -> Unit,
) {
    ScreenScaffold(timeText = {}) { _ ->
        when (val connection = ui.connection) {
            is ConnectionState.AwaitingAuthorization -> StatusPanel(
                title = stringResource(R.string.state_awaiting_auth_title),
                body = stringResource(R.string.state_awaiting_auth_body),
            )
            is ConnectionState.Failed -> StatusPanel(title = connection.device.name, body = errorText(connection.error)) {
                Button(onClick = onRetry, modifier = Modifier.testTag("retry")) { Text(stringResource(R.string.retry)) }
                Button(onClick = onDevices, colors = ButtonDefaults.filledTonalButtonColors()) {
                    Text(stringResource(R.string.devices))
                }
            }
            else -> DpadGrid(press, onTouchpad)
        }
    }
}

/**
 * Cross-shaped D-pad with small shortcut keys in the corners, sized to fit a round screen:
 *
 *   [Back]  [ ▲ ]  [Home]
 *   [ ◀ ]   [OK]   [ ▶ ]
 *   [Menu]  [ ▼ ]  [Pad]
 */
@Composable
private fun DpadGrid(press: (RemoteKey) -> Unit, onTouchpad: () -> Unit) {
    val cell = 54.dp
    val corner = 38.dp
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                CornerCell(cell, Alignment.BottomEnd) {
                    KeyButton(R.drawable.ic_back, stringResource(R.string.key_back), { press(RemoteKey.BACK) }, size = corner)
                }
                KeyButton(R.drawable.ic_up, stringResource(R.string.key_up), { press(RemoteKey.DPAD_UP) }, size = cell)
                CornerCell(cell, Alignment.BottomStart) {
                    KeyButton(R.drawable.ic_home, stringResource(R.string.key_home), { press(RemoteKey.HOME) }, size = corner)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                KeyButton(R.drawable.ic_left, stringResource(R.string.key_left), { press(RemoteKey.DPAD_LEFT) }, size = cell)
                KeyButton(
                    R.drawable.ic_check,
                    stringResource(R.string.key_select),
                    { press(RemoteKey.SELECT) },
                    size = cell,
                    primary = true,
                    modifier = Modifier.testTag("ok"),
                )
                KeyButton(R.drawable.ic_right, stringResource(R.string.key_right), { press(RemoteKey.DPAD_RIGHT) }, size = cell)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                CornerCell(cell, Alignment.TopEnd) {
                    KeyButton(R.drawable.ic_menu, stringResource(R.string.key_menu), { press(RemoteKey.MENU) }, size = corner)
                }
                KeyButton(R.drawable.ic_down, stringResource(R.string.key_down), { press(RemoteKey.DPAD_DOWN) }, size = cell)
                CornerCell(cell, Alignment.TopStart) {
                    KeyButton(R.drawable.ic_touchpad, stringResource(R.string.open_touchpad), onTouchpad, size = corner)
                }
            }
        }
    }
}

@Composable
private fun CornerCell(size: Dp, alignment: Alignment, content: @Composable () -> Unit) {
    Box(Modifier.size(size), contentAlignment = alignment) { content() }
}

/**
 *   [⏪] [ ⏯ ] [⏩]
 *   [−]  [🔇]  [+]     (hidden when volume control is off; the transport keys get bigger)
 *     [⌨]  [⚙]
 */
@Composable
private fun MediaPage(press: (RemoteKey) -> Unit, onKeyboard: () -> Unit, onSettings: () -> Unit, volumeEnabled: Boolean) {
    val side = if (volumeEnabled) 44.dp else 52.dp
    val center = if (volumeEnabled) 58.dp else 68.dp
    ScreenScaffold(timeText = {}) { _ ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    KeyButton(R.drawable.ic_rewind, stringResource(R.string.key_rewind), { press(RemoteKey.REWIND) }, size = side)
                    KeyButton(
                        R.drawable.ic_play_pause,
                        stringResource(R.string.key_play_pause),
                        { press(RemoteKey.PLAY_PAUSE) },
                        size = center,
                        primary = true,
                    )
                    KeyButton(R.drawable.ic_fast_forward, stringResource(R.string.key_fast_forward), { press(RemoteKey.FAST_FORWARD) }, size = side)
                }
                if (volumeEnabled) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    KeyButton(R.drawable.ic_volume_down, stringResource(R.string.key_volume_down), { press(RemoteKey.VOLUME_DOWN) }, size = 48.dp)
                    KeyButton(R.drawable.ic_mute, stringResource(R.string.key_mute), { press(RemoteKey.MUTE) }, size = 44.dp)
                    KeyButton(R.drawable.ic_volume_up, stringResource(R.string.key_volume_up), { press(RemoteKey.VOLUME_UP) }, size = 48.dp)
                }
                Spacer(Modifier.height(if (volumeEnabled) 2.dp else 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KeyButton(R.drawable.ic_keyboard, stringResource(R.string.open_keyboard), onKeyboard, size = 40.dp)
                    KeyButton(R.drawable.ic_settings, stringResource(R.string.open_settings), onSettings, size = 40.dp)
                }
            }
        }
    }
}
