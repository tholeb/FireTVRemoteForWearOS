package dev.firetvonwatch.wear.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.wear.R
import dev.firetvonwatch.wear.input.GestureClassifier
import dev.firetvonwatch.wear.input.rememberHaptics
import dev.firetvonwatch.wear.ui.RemoteUiState
import dev.firetvonwatch.wear.ui.RemoteViewModel
import dev.firetvonwatch.wear.ui.common.ConnectionRing
import dev.firetvonwatch.wear.ui.common.KeyButton

/** Full-screen touchpad: swipe = D-pad direction, tap = OK, hold = Back. */
@Composable
fun TouchpadScreen(vm: RemoteViewModel, ui: RemoteUiState, onClose: () -> Unit) {
    val haptics = rememberHaptics(ui.settings.hapticsEnabled)
    val density = LocalDensity.current
    val tapSlop = with(density) { 12.dp.toPx() }
    val minSwipe = with(density) { 28.dp.toPx() }
    val onKey = rememberUpdatedState<(RemoteKey) -> Unit> { key ->
        haptics.tap()
        vm.press(key)
    }
    val focus = remember { FocusRequester() }
    val rotary = rememberRotaryHandler(mode = { ui.settings.rotaryMode }) { keys ->
        haptics.tick()
        vm.pressAll(keys)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val description = stringResource(R.string.touchpad_hint)
    val okLabel = stringResource(R.string.key_select)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { rotary(it.verticalScrollPixels) }
            .focusRequester(focus)
            .focusable()
            .testTag("touchpad")
            .semantics {
                contentDescription = description
                onClick(label = okLabel) { onKey.value(RemoteKey.SELECT); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var last = down
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change
                        if (!change.pressed) break
                    }
                    GestureClassifier.classify(
                        dx = last.position.x - down.position.x,
                        dy = last.position.y - down.position.y,
                        durationMs = last.uptimeMillis - down.uptimeMillis,
                        tapSlopPx = tapSlop,
                        minSwipePx = minSwipe,
                    )?.let { onKey.value(it) }
                }
            },
    ) {
        Text(
            text = description,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 28.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        KeyButton(
            R.drawable.ic_close,
            stringResource(R.string.close),
            onClose,
            size = 36.dp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
        )
        KeyButton(
            R.drawable.ic_home,
            stringResource(R.string.key_home),
            { onKey.value(RemoteKey.HOME) },
            size = 36.dp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        )
        ConnectionRing(ui.connection)
    }
}
