package dev.firetvonwatch.wear

import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.model.RotaryMode
import dev.firetvonwatch.wear.input.GestureClassifier
import dev.firetvonwatch.wear.input.RotaryAccumulator
import dev.firetvonwatch.wear.tile.TileAction
import dev.firetvonwatch.wear.ui.settings.nextIdleTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputLogicTest {
    @Test fun `one detent is one step`() {
        val acc = RotaryAccumulator(pxPerStep = 50f)
        assertEquals(1, acc.onScroll(55f))
        assertEquals(0, acc.onScroll(20f))
        assertEquals(1, acc.onScroll(40f))
    }

    @Test fun `smooth crown fractions accumulate`() {
        val acc = RotaryAccumulator(pxPerStep = 50f)
        val steps = (1..10).sumOf { acc.onScroll(10f) }
        assertEquals(2, steps)
    }

    @Test fun `direction change discards leftover and big flings are capped`() {
        val acc = RotaryAccumulator(pxPerStep = 50f, maxStepsPerEvent = 3)
        assertEquals(0, acc.onScroll(40f))
        assertEquals(0, acc.onScroll(-40f)) // does not cancel out to a step either way
        assertEquals(-1, acc.onScroll(-20f))
        assertEquals(3, acc.onScroll(1000f))
    }

    @Test fun `rotary keys follow mode`() {
        assertEquals(listOf(RemoteKey.DPAD_DOWN, RemoteKey.DPAD_DOWN), RotaryAccumulator.keysFor(2, RotaryMode.NAVIGATE))
        assertEquals(listOf(RemoteKey.DPAD_UP), RotaryAccumulator.keysFor(-1, RotaryMode.NAVIGATE))
        assertEquals(listOf(RemoteKey.VOLUME_UP), RotaryAccumulator.keysFor(1, RotaryMode.VOLUME))
        assertEquals(listOf(RemoteKey.VOLUME_DOWN), RotaryAccumulator.keysFor(-1, RotaryMode.VOLUME))
        assertEquals(emptyList<RemoteKey>(), RotaryAccumulator.keysFor(0, RotaryMode.VOLUME))
    }

    @Test fun `gestures classify into keys`() {
        fun c(dx: Float, dy: Float) = GestureClassifier.classify(dx, dy, tapSlopPx = 20f, minSwipePx = 60f)
        assertEquals(RemoteKey.SELECT, c(3f, 4f))
        assertEquals(RemoteKey.DPAD_RIGHT, c(120f, 30f))
        assertEquals(RemoteKey.DPAD_LEFT, c(-120f, 30f))
        assertEquals(RemoteKey.DPAD_UP, c(10f, -90f))
        assertEquals(RemoteKey.DPAD_DOWN, c(10f, 90f))
        assertNull(c(30f, 30f)) // ambiguous short drag
    }

    @Test fun `tile actions map to keys`() {
        assertEquals(RemoteKey.PLAY_PAUSE, TileAction.keyFor("play_pause"))
        assertNull(TileAction.keyFor("open"))
        assertNull(TileAction.keyFor(""))
        assertNull(TileAction.keyFor(null))
    }

    @Test fun `idle timeout cycles through choices`() {
        assertEquals(60, nextIdleTimeout(30))
        assertEquals(30, nextIdleTimeout(300))
        assertEquals(30, nextIdleTimeout(17))
    }

    @Test fun `dim layer strength follows the brightness setting`() {
        val s = dev.firetvonwatch.domain.model.RemoteSettings(remoteBrightnessPercent = 30)
        assertEquals(0.7f, dev.firetvonwatch.wear.ui.common.dimAlpha(s), 0.001f)
        assertEquals(0f, dev.firetvonwatch.wear.ui.common.dimAlpha(s.copy(remoteBrightnessPercent = 100)), 0.001f)
        assertEquals(0.9f, dev.firetvonwatch.wear.ui.common.dimAlpha(s.copy(remoteBrightnessPercent = 1)), 0.001f)
    }

    @Test fun `apps are sorted by displayed name, ignoring case and accents`() {
        val labels = mapOf(
            "com.netflix.ninja" to "Netflix",
            "org.example.e" to "éditions TV",
            "org.example.a" to "apple TV",
            "com.amazon.firebat" to "Prime Video",
            "org.example.z" to "Zattoo",
            "org.example.e2" to "Editions",
        )
        val sorted = dev.firetvonwatch.wear.ui.remote.sortedByName(labels.keys.toList()) { labels.getValue(it) }
        assertEquals(listOf("apple TV", "Editions", "éditions TV", "Netflix", "Prime Video", "Zattoo"), sorted.map { labels.getValue(it) })
    }

    @Test fun `displayed name prefers the TV label`() {
        assertEquals("Netflix", dev.firetvonwatch.wear.ui.remote.appLabel("com.netflix.ninja", null))
        assertEquals(
            "Netflix FR",
            dev.firetvonwatch.wear.ui.remote.appLabel("com.netflix.ninja", dev.firetvonwatch.domain.model.AppDetails("com.netflix.ninja", "Netflix FR", null)),
        )
    }
}
