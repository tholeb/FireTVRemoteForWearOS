package dev.firetvonwatch.data.adb

import dadb.AdbKeyPair
import dadb.Dadb
import dev.firetvonwatch.domain.model.ConnectionError
import dev.firetvonwatch.domain.protocol.InteractiveShell
import dev.firetvonwatch.domain.protocol.ShellResult
import dev.firetvonwatch.domain.protocol.ShellTransport
import dev.firetvonwatch.domain.protocol.ShellTransportFactory
import dev.firetvonwatch.domain.protocol.TransportException
import okio.Buffer
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Opens ADB-over-TCP connections with dadb.
 *
 * It first tries a plain TCP connect, which separates "unreachable" from "refused (ADB off)".
 * The ADB handshake then gets a long read timeout ([authTimeoutMs]), because adbd stops
 * answering until the user responds to the "Allow USB debugging?" prompt on the TV.
 */
class DadbShellTransportFactory(
    private val keyPair: () -> AdbKeyPair,
    private val connectTimeoutMs: Int = 3_000,
    private val authTimeoutMs: Int = 45_000,
) : ShellTransportFactory {

    override fun open(host: String, port: Int): ShellTransport {
        probeTcp(host, port)
        val key = try {
            keyPair()
        } catch (e: Exception) {
            throw TransportException(ConnectionError.IO, "Could not load the watch's ADB key: ${e.message}", e)
        }
        val dadb = Dadb.create(host, port, key, connectTimeoutMs, authTimeoutMs, true)
        try {
            // The first call does the TCP + RSA handshake (blocking while the TV shows its prompt)
            // and reads the device's feature list.
            val shellV2 = dadb.supportsFeature("shell_v2")
            val transport = DadbShellTransport(dadb, shellV2)
            val reply = transport.exec("echo ok")
            if (!reply.output.contains("ok")) {
                throw TransportException(ConnectionError.IO, "Unexpected handshake reply: ${reply.output.take(80)}")
            }
            return transport
        } catch (e: TransportException) {
            runCatching { dadb.close() }
            throw e
        } catch (e: Exception) {
            runCatching { dadb.close() }
            throw mapHandshakeFailure(e)
        }
    }

    private fun probeTcp(host: String, port: Int) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), connectTimeoutMs) }
        } catch (e: ConnectException) {
            throw TransportException(ConnectionError.REFUSED, "Connection refused on $host:$port", e)
        } catch (e: IOException) {
            throw TransportException(ConnectionError.UNREACHABLE, "$host:$port is unreachable (${e.javaClass.simpleName})", e)
        } catch (e: IllegalArgumentException) {
            throw TransportException(ConnectionError.UNREACHABLE, "Invalid address $host:$port", e)
        }
    }

    internal companion object {
        fun mapHandshakeFailure(e: Throwable): TransportException {
            val chain = generateSequence(e) { it.cause }.toList()
            return when {
                chain.any { it is SocketTimeoutException } ->
                    TransportException(ConnectionError.AUTH_TIMEOUT, "No answer to the authorization prompt", e)
                chain.any { it is ConnectException } ->
                    TransportException(ConnectionError.REFUSED, e.message, e)
                else ->
                    TransportException(ConnectionError.AUTH_REJECTED, "The TV closed the connection (${e.javaClass.simpleName})", e)
            }
        }
    }
}

/**
 * Shell commands over dadb.
 *
 * dadb only speaks the "shell v2" protocol (Android 7+), which carries exit codes and keeps
 * stdout and stderr apart. Older Fire OS (5.x = Android 5.1) only has the legacy `shell:`
 * service: a pty, no exit code. There the command is wrapped to print an exit-code marker,
 * and long-running processes go through [LegacyInteractiveShell].
 */
class DadbShellTransport(private val dadb: Dadb, private val shellV2: Boolean = true) : ShellTransport {
    override fun push(bytes: ByteArray, remotePath: String) {
        try {
            dadb.push(Buffer().write(bytes), remotePath, 0b110_100_100, System.currentTimeMillis())
        } catch (e: Exception) {
            throw TransportException(ConnectionError.IO, "Push failed: ${e.message}", e)
        }
    }

    override fun openInteractive(command: String): InteractiveShell = try {
        if (shellV2) DadbInteractiveShell(dadb.openShell(command)) else LegacyInteractiveShell(dadb.open("shell:$command"))
    } catch (e: Exception) {
        throw TransportException(ConnectionError.IO, "Could not start '$command': ${e.message}", e)
    }

    override fun exec(command: String): ShellResult = try {
        if (shellV2) {
            val response = dadb.shell(command)
            ShellResult(response.exitCode, response.allOutput)
        } else {
            dadb.open("shell:" + LegacyShell.wrap(command)).use { stream ->
                LegacyShell.parse(stream.source.readUtf8())
            }
        }
    } catch (e: Exception) {
        throw TransportException(ConnectionError.IO, e.message ?: e.javaClass.simpleName, e)
    }

    override fun close() {
        runCatching { dadb.close() }
    }
}

/** Exit codes for the legacy (pre-Android 7) `shell:` service, which has none of its own. */
internal object LegacyShell {
    const val MARKER = "__FTVW_RC="

    fun wrap(command: String): String = "$command; echo \"$MARKER\$?\""

    /** Splits the output from the marker line; a missing marker means the shell died (exit 255). */
    fun parse(raw: String): ShellResult {
        val text = raw.replace("\r\n", "\n").replace("\r", "")
        val index = text.lastIndexOf(MARKER)
        if (index < 0) return ShellResult(255, text)
        val code = text.substring(index + MARKER.length).trim().toIntOrNull() ?: 255
        return ShellResult(code, text.substring(0, index))
    }
}
