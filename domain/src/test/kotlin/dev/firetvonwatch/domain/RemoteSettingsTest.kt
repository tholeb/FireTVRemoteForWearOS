package dev.firetvonwatch.domain

import dev.firetvonwatch.domain.model.RemoteSettings
import dev.firetvonwatch.domain.model.RotaryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSettingsTest {
    @Test fun `defaults keep volume on and back inside the app`() {
        val s = RemoteSettings()
        assertTrue(s.volumeControlEnabled)
        assertFalse(s.swipeBackCloses)
    }

    @Test fun `bezel mode depends on page and volume setting`() {
        val nav = RemoteSettings(rotaryMode = RotaryMode.NAVIGATE)
        val vol = RemoteSettings(rotaryMode = RotaryMode.VOLUME)
        assertEquals(RotaryMode.NAVIGATE, nav.rotaryModeFor(mediaPage = false))
        assertEquals(RotaryMode.VOLUME, nav.rotaryModeFor(mediaPage = true))
        assertEquals(RotaryMode.VOLUME, vol.rotaryModeFor(mediaPage = false))
        // Volume control off: the bezel never touches the volume.
        assertEquals(RotaryMode.NAVIGATE, vol.copy(volumeControlEnabled = false).rotaryModeFor(mediaPage = false))
        assertEquals(RotaryMode.NAVIGATE, nav.copy(volumeControlEnabled = false).rotaryModeFor(mediaPage = true))
    }
}
