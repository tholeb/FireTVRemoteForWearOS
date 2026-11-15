package dev.firetvonwatch.wear.input

import dev.firetvonwatch.domain.model.RemoteKey
import kotlin.math.abs
import kotlin.math.hypot

/** Maps a finished one-finger touch on the touchpad to a key. */
object GestureClassifier {
    const val LONG_PRESS_MS = 500L

    /**
     * @param dx,dy total movement in px from down to up
     * @param tapSlopPx movement below this counts as a tap or hold
     * @param minSwipePx movement needed for a swipe (between the two thresholds: ignored)
     */
    fun classify(dx: Float, dy: Float, durationMs: Long, tapSlopPx: Float, minSwipePx: Float): RemoteKey? {
        val distance = hypot(dx, dy)
        if (distance < tapSlopPx) return if (durationMs >= LONG_PRESS_MS) RemoteKey.BACK else RemoteKey.SELECT
        if (distance < minSwipePx) return null
        return if (abs(dx) >= abs(dy)) {
            if (dx > 0) RemoteKey.DPAD_RIGHT else RemoteKey.DPAD_LEFT
        } else {
            if (dy > 0) RemoteKey.DPAD_DOWN else RemoteKey.DPAD_UP
        }
    }
}
