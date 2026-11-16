package dev.firetvonwatch.wear.ui.common

import android.content.Context
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.firetvonwatch.domain.model.RemoteSettings
import kotlinx.coroutines.delay

/**
 * How strongly to darken the dimmed remote: alpha of a black layer over the UI, so
 * 30 % brightness = 0.7. On the watch's AMOLED screen darker pixels really are dimmer and use
 * less power. (A window-brightness override is ignored by some watches, e.g. Galaxy Watch.)
 */
fun dimAlpha(settings: RemoteSettings): Float =
    1f - settings.remoteBrightnessPercent.coerceIn(RemoteSettings.REMOTE_BRIGHTNESS_RANGE) / 100f

/** The watch's own "screen timeout" setting, which the remote reuses as its dimming delay. */
fun systemScreenTimeoutMs(context: Context): Long =
    Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_SCREEN_TIMEOUT_MS)
        .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

private const val DEFAULT_SCREEN_TIMEOUT_MS = 15_000L
private const val MIN_TIMEOUT_MS = 5_000L
private const val MAX_TIMEOUT_MS = 10 * 60_000L

/**
 * Wraps the app. While [active] (remote or touchpad showing) and enabled in [settings]:
 * - the screen never times out (FLAG_KEEP_SCREEN_ON),
 * - after the watch's screen-timeout delay without any touch, bezel or key, the UI is dimmed
 *   to the chosen brightness,
 * - the first touch or bezel turn only brightens it again (it isn't passed on), then the
 *   delay starts over.
 * Elsewhere the watch's normal timeout applies.
 */
@Composable
fun RemoteScreenAwake(
    active: Boolean,
    settings: RemoteSettings,
    /** Dimming delay; null = the watch's screen timeout, re-read each time the app resumes. */
    timeoutOverrideMs: Long? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    var systemTimeoutMs by remember { mutableLongStateOf(systemScreenTimeoutMs(context)) }
    LifecycleResumeEffect(context) {
        systemTimeoutMs = systemScreenTimeoutMs(context)
        onPauseOrDispose { }
    }
    val timeoutMs = timeoutOverrideMs ?: systemTimeoutMs

    val enabled = active && settings.keepScreenOn
    val window = LocalActivity.current?.window
    DisposableEffect(window, enabled) {
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var interactions by remember { mutableIntStateOf(0) }
    var dimmed by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, interactions, timeoutMs) {
        dimmed = false
        if (enabled) {
            delay(timeoutMs)
            dimmed = true
        }
    }
    val wake = { interactions++ }

    Box(
        Modifier
            .fillMaxSize()
            // Watch every touch before the UI gets it, without consuming it.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        wake()
                    }
                }
            }
            // Bezel: when dimmed, the first turn only wakes the screen.
            .onPreRotaryScrollEvent {
                val wasDimmed = dimmed
                wake()
                wasDimmed
            }
            .onPreviewKeyEvent {
                wake()
                false
            },
    ) {
        content()
        AnimatedVisibility(visible = dimmed, enter = fadeIn(), exit = fadeOut()) {
            // On top of everything: swallows the waking touch so it doesn't press a button.
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("dim")
                    .background(Color.Black.copy(alpha = dimAlpha(settings)))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            )
        }
    }
}
