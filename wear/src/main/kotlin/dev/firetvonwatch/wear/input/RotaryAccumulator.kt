package dev.firetvonwatch.wear.input

import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.model.RotaryMode
import kotlin.math.abs
import kotlin.math.sign

/**
 * Turns raw rotary scroll pixels into discrete steps. One bezel detent produces about
 * [pxPerStep] pixels; smooth crowns produce fractions that add up across events.
 */
class RotaryAccumulator(private val pxPerStep: Float, private val maxStepsPerEvent: Int = 3) {
    private var accumulated = 0f

    init {
        require(pxPerStep > 0f)
    }

    /** Adds one event's [deltaPx]. Returns the signed number of steps to send (often 0). */
    fun onScroll(deltaPx: Float): Int {
        if (accumulated != 0f && sign(deltaPx) != sign(accumulated)) accumulated = 0f // direction change
        accumulated += deltaPx
        val steps = (accumulated / pxPerStep).toInt()
        if (steps == 0) return 0
        accumulated -= steps * pxPerStep
        return steps.coerceIn(-maxStepsPerEvent, maxStepsPerEvent)
    }

    fun reset() {
        accumulated = 0f
    }

    companion object {
        /** Clockwise (positive) moves down or raises the volume. */
        fun keyFor(steps: Int, mode: RotaryMode): RemoteKey? = when {
            steps == 0 -> null
            mode == RotaryMode.NAVIGATE -> if (steps > 0) RemoteKey.DPAD_DOWN else RemoteKey.DPAD_UP
            else -> if (steps > 0) RemoteKey.VOLUME_UP else RemoteKey.VOLUME_DOWN
        }

        fun keysFor(steps: Int, mode: RotaryMode): List<RemoteKey> {
            val key = keyFor(steps, mode) ?: return emptyList()
            return List(abs(steps)) { key }
        }
    }
}
