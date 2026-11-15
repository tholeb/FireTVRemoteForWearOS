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
) {
    companion object {
        const val DEFAULT_IDLE_TIMEOUT_SECONDS = 60
        val IDLE_TIMEOUT_CHOICES = listOf(30, 60, 120, 300)
    }
}
