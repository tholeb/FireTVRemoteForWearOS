package dev.firetvonwatch.domain.protocol

import dev.firetvonwatch.domain.model.ConnectionError

data class ShellResult(val exitCode: Int, val output: String)

/**
 * An authenticated ADB connection that can run shell commands.
 * Implementations are blocking and must be called off the main thread.
 */
interface ShellTransport : AutoCloseable {
    /** Runs [command] through `sh -c` and waits for it to exit. Throws [TransportException]. */
    fun exec(command: String): ShellResult

    /** Writes [bytes] to [remotePath] on the TV (mode 0644). Throws [TransportException]. */
    fun push(bytes: ByteArray, remotePath: String)

    /** Starts a long-running [command] whose stdin/stdout stay open. Throws [TransportException]. */
    fun openInteractive(command: String): InteractiveShell

    /** Closes the connection. Must unblock any [exec] in progress. Idempotent. */
    override fun close()
}

/** A process on the TV with a line-based stdin/stdout. Blocking; closing unblocks [readLine]. */
interface InteractiveShell : AutoCloseable {
    fun writeLine(line: String)

    /** Next stdout/stderr line, or null when the process ended. */
    fun readLine(): String?

    override fun close()
}

fun interface ShellTransportFactory {
    /**
     * Opens and authenticates a connection. Blocks while the TV shows its authorization
     * prompt. Throws [TransportException].
     */
    fun open(host: String, port: Int): ShellTransport
}

class TransportException(
    val error: ConnectionError,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: error.name, cause)
