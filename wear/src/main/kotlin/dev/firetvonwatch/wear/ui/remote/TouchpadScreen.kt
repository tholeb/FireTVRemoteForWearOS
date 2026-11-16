package dev.firetvonwatch.wear.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.wear.R
import dev.firetvonwatch.wear.input.GestureClassifier
import dev.firetvonwatch.wear.input.Haptics
import dev.firetvonwatch.wear.input.rememberHaptics
import dev.firetvonwatch.wear.ui.RemoteUiState
import dev.firetvonwatch.wear.ui.RemoteViewModel
import dev.firetvonwatch.wear.ui.common.ConnectionRing
import dev.firetvonwatch.wear.ui.common.KeyButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the exit button must be held to leave the touchpad. */
const val TOUCHPAD_EXIT_HOLD_MS = 3_000

/**
 * Full-screen touchpad: swipe = D-pad direction, tap = OK. Back, Home and Menu
 * buttons along the bottom. The watch's Back sends Back to the TV; leaving the touchpad takes
 * a 3 s hold on the exit button at the top, so it can't happen by accident.
 */
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
    // The nav host's own back handling is off on this screen (its swipe-to-dismiss would steal
    // the touchpad's swipes). The watch's Back is forwarded to the TV instead of leaving.
    BackHandler { onKey.value(RemoteKey.BACK) }
    val description = stringResource(R.string.touchpad_hint)
    val okLabel = stringResource(R.string.key_select)

    // No clock here: it would sit on top of the exit button.
    ScreenScaffold(timeText = {}) { _ ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // The pad is a sibling of the buttons, not their parent: a touch on a button then
            // never also reaches the pad (which would send an extra OK).
            Box(
                Modifier
                    .fillMaxSize()
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
            }
            HoldToExitButton(
                onExit = onClose,
                haptics = haptics,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
            )
            // Back · Home · Menu along the bottom; the outer two sit higher to follow the round edge.
            KeyButton(
                R.drawable.ic_back,
                stringResource(R.string.key_back),
                { onKey.value(RemoteKey.BACK) },
                size = 36.dp,
                modifier = Modifier.align(Alignment.BottomCenter).offset(x = (-46).dp, y = (-26).dp),
            )
            KeyButton(
                R.drawable.ic_home,
                stringResource(R.string.key_home),
                { onKey.value(RemoteKey.HOME) },
                size = 36.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            )
            KeyButton(
                R.drawable.ic_menu,
                stringResource(R.string.key_menu),
                { onKey.value(RemoteKey.MENU) },
                size = 36.dp,
                modifier = Modifier.align(Alignment.BottomCenter).offset(x = 46.dp, y = (-26).dp),
            )
            ConnectionRing(ui.connection)
        }
    }
}

/**
 * Close button that only acts after being held for [TOUCHPAD_EXIT_HOLD_MS]: a ring fills around
 * it while held and drains back if let go early, with a "Hold 3 s to exit" hint underneath.
 */
@Composable
private fun HoldToExitButton(onExit: () -> Unit, haptics: Haptics, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val exit by rememberUpdatedState(onExit)
    var hintVisible by remember { mutableStateOf(false) }
    var hintJob by remember { mutableStateOf<Job?>(null) }
    val hintAlpha by animateFloatAsState(if (hintVisible) 1f else 0f, tween(200), label = "hint")
    val label = stringResource(R.string.touchpad_exit_label)
    val ring = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(46.dp)
                .testTag("touchpad_exit")
                .semantics {
                    contentDescription = label
                    // A screen reader can't time a 3 s hold: its long-press action exits directly.
                    onLongClick(label = label) { exit(); true }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        hintJob?.cancel()
                        hintVisible = true
                        haptics.tap()
                        val hold = scope.launch {
                            val remaining = (TOUCHPAD_EXIT_HOLD_MS * (1f - progress.value)).toInt()
                            progress.animateTo(1f, tween(remaining, easing = LinearEasing))
                            haptics.confirm()
                            exit()
                        }
                        waitForUpOrCancellation()
                        if (hold.isActive) {
                            hold.cancel()
                            scope.launch { progress.animateTo(0f, tween(250)) }
                            hintJob = scope.launch {
                                delay(1_500)
                                hintVisible = false
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val p = progress.value
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(inset, inset)
                if (p > 0f) {
                    drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                    drawArc(ring, -90f, 360f * p, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Box(
                Modifier
                    .size(34.dp)
                    .scale(1f - 0.15f * p)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            stringResource(R.string.touchpad_exit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.alpha(hintAlpha),
        )
    }
}
