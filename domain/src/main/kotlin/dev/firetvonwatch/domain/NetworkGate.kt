package dev.firetvonwatch.domain

/**
 * Makes sure the process talks to the LAN over Wi-Fi rather than through the paired phone's
 * Bluetooth proxy. Calls are reference-counted.
 */
interface NetworkGate {
    /** Asks for Wi-Fi and waits until it's usable. Returns false on timeout. */
    suspend fun acquire(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean

    /** Gives back one [acquire]. Wi-Fi is released after the last one. */
    fun release()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
