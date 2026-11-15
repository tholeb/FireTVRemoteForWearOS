package dev.firetvonwatch.domain.model

/** Information read from the TV right after connecting. */
data class TvInfo(
    val model: String,
    val sdkInt: Int,
    val inputDialect: InputDialect,
    /** True once the persistent on-TV injector is running (keys then take a few ms). */
    val fastInput: Boolean = false,
)

/**
 * How key events are injected.
 * - [CMD_INPUT]: `cmd input …` talks to the running input service directly (fast, Android 10+).
 * - [INPUT]: legacy `input …` wrapper, which starts a JVM per call on older Fire OS (slow).
 */
enum class InputDialect(val prefix: String) {
    CMD_INPUT("cmd input"),
    INPUT("input"),
}

enum class ConnectionError {
    /** Wi-Fi could not be brought up on the watch. */
    NO_WIFI,
    /** Host did not answer (wrong IP, TV asleep/off, different network). */
    UNREACHABLE,
    /** TCP connection refused: ADB debugging is probably disabled on the TV. */
    REFUSED,
    /** The TV did not answer the authorization prompt in time. */
    AUTH_TIMEOUT,
    /** The TV closed the connection during authorization (prompt denied or ADB disabled). */
    AUTH_REJECTED,
    /** Connection dropped or a command timed out. */
    IO,
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val device: FireTvDevice) : ConnectionState
    /** Connecting is taking long: the TV is most likely showing the "Allow USB debugging?" prompt. */
    data class AwaitingAuthorization(val device: FireTvDevice) : ConnectionState
    data class Connected(val device: FireTvDevice, val info: TvInfo) : ConnectionState
    data class Failed(val device: FireTvDevice, val error: ConnectionError, val detail: String? = null) : ConnectionState
}
