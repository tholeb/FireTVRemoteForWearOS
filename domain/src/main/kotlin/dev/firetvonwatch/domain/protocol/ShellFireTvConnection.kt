package dev.firetvonwatch.domain.protocol

import dev.firetvonwatch.domain.CommandRejectedException
import dev.firetvonwatch.domain.FireTvConnection
import dev.firetvonwatch.domain.NotConnectedException
import dev.firetvonwatch.domain.model.AppDetails
import dev.firetvonwatch.domain.model.ConnectionError
import dev.firetvonwatch.domain.model.ConnectionState
import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.model.InputDialect
import dev.firetvonwatch.domain.model.KnownApps
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.model.TvInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [FireTvConnection] over an ADB shell [ShellTransport].
 *
 * One authenticated ADB connection is kept open. Key presses go through the fastest path:
 * 1. **Injector** (when [injectorPayload] is given): a small dex pushed to the TV and kept
 *    running in the background after connecting; each key is one line on its stdin.
 *    Takes a few ms instead of a new JVM per key.
 * 2. **Fallback**: a short-lived shell running `cmd input keyevent …` / `input keyevent …`.
 *    Used until the injector is ready, or for good if it fails.
 * Text and app launches always use path 2. If a command hangs, the transport is closed and
 * the failure is reported, so the caller can reconnect.
 */
class ShellFireTvConnection(
    private val factory: ShellTransportFactory,
    private val ioDispatcher: CoroutineDispatcher,
    private val injectorPayload: (() -> InjectorPayload?)? = null,
    private val authHintDelayMs: Long = 1_500,
    private val commandTimeoutMs: Long = 3_000,
    private val injectorTimeoutMs: Long = 1_500,
    private val injectorStartTimeoutMs: Long = 8_000,
    private val appInfoTimeoutMs: Long = 5_000,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : FireTvConnection {

    private val mutex = Mutex()
    private val blockingScope = CoroutineScope(ioDispatcher + SupervisorJob())
    private var transport: ShellTransport? = null
    private var dialect: InputDialect = InputDialect.CMD_INPUT
    private var injector: InteractiveShell? = null
    private var injectorJob: Job? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _lastLatencyMs = MutableStateFlow<Long?>(null)
    override val lastLatencyMs: StateFlow<Long?> = _lastLatencyMs.asStateFlow()

    override suspend fun connect(device: FireTvDevice): Result<TvInfo> = mutex.withLock {
        val current = _state.value
        if (transport != null && current is ConnectionState.Connected && current.device.id == device.id) {
            return@withLock Result.success(current.info)
        }
        closeTransportLocked()
        _state.value = ConnectionState.Connecting(device)
        coroutineScope {
            val authHint = launch {
                delay(authHintDelayMs)
                if (_state.value is ConnectionState.Connecting) {
                    _state.value = ConnectionState.AwaitingAuthorization(device)
                }
            }
            try {
                val opened = withContext(ioDispatcher) { factory.open(device.host, device.port) }
                authHint.cancel()
                val info = try {
                    ShellCommandBuilder.parseProbe(execWithTimeout(opened, ShellCommandBuilder.PROBE).output)
                } catch (e: TransportException) {
                    opened.close()
                    throw e
                }
                transport = opened
                dialect = info.inputDialect
                _state.value = ConnectionState.Connected(device, info)
                startInjector(opened)
                Result.success(info)
            } catch (e: TransportException) {
                _state.value = ConnectionState.Failed(device, e.error, e.message)
                Result.failure(e)
            } finally {
                authHint.cancel()
            }
        }
    }

    override suspend fun sendKeys(keys: List<RemoteKey>): Result<Unit> {
        if (keys.isEmpty()) return Result.success(Unit)
        if (sendViaInjector(keys)) return Result.success(Unit)
        return run { ShellCommandBuilder.keys(keys, it) }.map { }
    }

    override suspend fun sendText(text: String, pressEnter: Boolean): Result<Unit> {
        if (!ShellCommandBuilder.isSupportedText(text)) {
            return Result.failure(IllegalArgumentException("Only plain ASCII text is supported"))
        }
        return run { ShellCommandBuilder.text(text, pressEnter, it) }.map { }
    }

    override suspend fun launchApp(packageName: String): Result<Unit> {
        if (!ShellCommandBuilder.isValidPackage(packageName)) {
            return Result.failure(IllegalArgumentException("Invalid package: $packageName"))
        }
        return run { ShellCommandBuilder.launchApp(packageName) }.map { }
    }

    override suspend fun listLaunchablePackages(): Result<List<String>> =
        run(measureLatency = false) { ShellCommandBuilder.LIST_LAUNCHABLE_APPS }
            .map { ShellCommandBuilder.parseLaunchablePackages(it.output, KnownApps::isKnown) }

    override suspend fun disconnect() = mutex.withLock {
        closeTransportLocked()
        _state.value = ConnectionState.Disconnected
    }

    /** Starts the injector in the background; keys use the fallback until it's ready. */
    private fun startInjector(t: ShellTransport) {
        val provider = injectorPayload ?: return
        injectorJob = blockingScope.launch {
            val shell = try {
                val payload = provider() ?: return@launch
                launchInjector(t, payload)
            } catch (e: Exception) {
                null
            } ?: return@launch
            mutex.withLock {
                val current = _state.value
                if (transport === t && current is ConnectionState.Connected) {
                    injector = shell
                    _state.value = current.copy(info = current.info.copy(fastInput = true))
                } else {
                    shell.close()
                }
            }
        }
    }

    /** Pushes the dex if needed and starts it. Returns null if it didn't report READY. */
    private suspend fun launchInjector(t: ShellTransport, payload: InjectorPayload): InteractiveShell? {
        val check = execWithTimeout(t, InjectorProtocol.presenceCheck(payload))
        if (!check.output.contains("present")) {
            runCatching { execWithTimeout(t, InjectorProtocol.cleanupOthers(payload)) }
            blocking(injectorStartTimeoutMs, onTimeout = { }) { t.push(payload.bytes, payload.remotePath) }
                ?: return null
        }
        val shell = blocking(injectorStartTimeoutMs, onTimeout = { }) { t.openInteractive(InjectorProtocol.startCommand(payload)) }
            ?.value ?: return null
        val first = blocking(injectorStartTimeoutMs, onTimeout = shell::close) { shell.readLine() }?.value
        if (!InjectorProtocol.isReady(first)) {
            shell.close()
            return null
        }
        return shell
    }

    /** True if the injector took the keys. On any failure the injector is dropped for this connection. */
    private suspend fun sendViaInjector(keys: List<RemoteKey>): Boolean {
        val start = nowMs()
        val reply = injectorRequest(InjectorProtocol.keys(keys), injectorTimeoutMs) ?: return false
        if (reply == InjectorProtocol.OK) {
            _lastLatencyMs.value = nowMs() - start
            return true
        }
        mutex.withLock { dropInjectorLocked() }
        return false
    }

    override suspend fun appDetails(packageName: String): Result<AppDetails> {
        if (!ShellCommandBuilder.isValidPackage(packageName)) {
            return Result.failure(IllegalArgumentException("Invalid package: $packageName"))
        }
        val reply = injectorRequest(InjectorProtocol.appInfo(packageName), appInfoTimeoutMs)
            ?: return Result.failure(IllegalStateException("Fast input helper not running"))
        return InjectorProtocol.parseAppInfo(packageName, reply)?.let { Result.success(it) }
            ?: Result.failure(CommandRejectedException(reply))
    }

    /**
     * Sends one line to the injector and returns its one-line reply, or null when the injector
     * isn't running. A timeout or I/O error drops the injector (keys then use the fallback).
     */
    private suspend fun injectorRequest(line: String, timeoutMs: Long): String? = mutex.withLock {
        val shell = injector ?: return@withLock null
        val reply = try {
            blocking(timeoutMs, onTimeout = shell::close) {
                shell.writeLine(line)
                shell.readLine()
            }?.value
        } catch (e: Exception) {
            null
        }
        if (reply == null) dropInjectorLocked()
        reply
    }

    private fun dropInjectorLocked() {
        injector?.let { runCatching { it.close() } }
        injector = null
        val current = _state.value
        if (current is ConnectionState.Connected && current.info.fastInput) {
            _state.value = current.copy(info = current.info.copy(fastInput = false))
        }
    }

    private suspend fun run(
        measureLatency: Boolean = true,
        command: (InputDialect) -> String,
    ): Result<ShellResult> = mutex.withLock {
        val t = transport ?: return@withLock Result.failure(NotConnectedException())
        val connected = _state.value as? ConnectionState.Connected
            ?: return@withLock Result.failure(NotConnectedException())
        val start = nowMs()
        try {
            val result = execWithTimeout(t, command(dialect))
            if (measureLatency) _lastLatencyMs.value = nowMs() - start
            if (result.exitCode != 0) {
                Result.failure(CommandRejectedException(result.output.trim().ifEmpty { "exit code ${result.exitCode}" }))
            } else {
                Result.success(result)
            }
        } catch (e: TransportException) {
            closeTransportLocked()
            _state.value = ConnectionState.Failed(connected.device, e.error, e.message)
            Result.failure(e)
        }
    }

    /** Runs a blocking exec. On timeout, closes the transport so the blocked thread finishes. */
    private suspend fun execWithTimeout(t: ShellTransport, command: String): ShellResult =
        blocking(commandTimeoutMs, onTimeout = t::close) { t.exec(command) }?.value
            ?: throw TransportException(ConnectionError.IO, "Command timed out after $commandTimeoutMs ms")

    private class Box<T>(val value: T)

    /**
     * Runs [block] on the IO dispatcher. Returns null on timeout, after calling [onTimeout],
     * which must unblock [block]. Failures become [TransportException].
     */
    private suspend fun <T> blocking(timeoutMs: Long, onTimeout: () -> Unit, block: () -> T): Box<T>? {
        val call = blockingScope.async { Box(block()) }
        val result = try {
            withTimeoutOrNull(timeoutMs) { call.await() }
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            throw TransportException(ConnectionError.IO, e.message, e)
        }
        if (result == null) {
            onTimeout()
            call.cancel()
        }
        return result
    }

    private fun closeTransportLocked() {
        injectorJob?.cancel()
        injectorJob = null
        injector?.let { runCatching { it.close() } }
        injector = null
        transport?.let { runCatching { it.close() } }
        transport = null
    }
}
