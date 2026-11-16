package dev.firetvonwatch.domain.model

enum class RotaryMode { NAVIGATE, VOLUME }

data class RemoteSettings(
    val rotaryMode: RotaryMode = RotaryMode.NAVIGATE,
    val hapticsEnabled: Boolean = true,
    /** Connection is released after this many seconds without input. */
    val idleTimeoutSeconds: Int = DEFAULT_IDLE_TIMEOUT_SECONDS,
    /** Open the full-screen touchpad instead of the button grid on launch. */
    val startInTouchpad: Boolean = false,
    /** Package names pinned on the Apps page, in display order. */
    val favoriteApps: List<String> = emptyList(),
    /**
     * Show volume/mute keys and allow the bezel to change the volume. Off for TVs whose sound
     * isn't controllable through HDMI-CEC (IR-only TVs or soundbars).
     */
    val volumeControlEnabled: Boolean = true,
    /** When false (default), back on the remote goes to the previous page and never closes the app. */
    val swipeBackCloses: Boolean = false,
    /** Wake the Fire TV each time the remote is opened. */
    val wakeOnLaunch: Boolean = true,
    /** Keep the watch screen on while the remote (or touchpad) is showing. */
    val keepScreenOn: Boolean = true,
    /** Screen brightness while the remote keeps the screen on, in percent (10-100). */
    val remoteBrightnessPercent: Int = DEFAULT_REMOTE_BRIGHTNESS,
) {
    /** What the bezel does: volume on the media page (if enabled), otherwise the user's choice. */
    fun rotaryModeFor(mediaPage: Boolean): RotaryMode = when {
        !volumeControlEnabled -> RotaryMode.NAVIGATE
        mediaPage -> RotaryMode.VOLUME
        else -> rotaryMode
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_SECONDS = 60
        const val DEFAULT_REMOTE_BRIGHTNESS = 30
        val REMOTE_BRIGHTNESS_RANGE = 10..100
        val IDLE_TIMEOUT_CHOICES = listOf(30, 60, 120, 300)
    }
}
