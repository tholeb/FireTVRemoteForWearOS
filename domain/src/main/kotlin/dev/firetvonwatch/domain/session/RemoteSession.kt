package dev.firetvonwatch.domain.session

import dev.firetvonwatch.domain.NotConnectedException
import dev.firetvonwatch.domain.FireTvConnection
import dev.firetvonwatch.domain.NetworkGate
import dev.firetvonwatch.domain.model.ConnectionError
import dev.firetvonwatch.domain.model.ConnectionState
import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.model.KnownApps
import dev.firetvonwatch.domain.model.RemoteCommand
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.model.TvApp
import dev.firetvonwatch.domain.protocol.TransportException
import dev.firetvonwatch.domain.model.AppDetails
import dev.firetvonwatch.domain.repository.AppDetailsCache
import dev.firetvonwatch.domain.repository.DeviceRepository
import dev.firetvonwatch.domain.repository.InMemoryAppDetailsCache
import dev.firetvonwatch.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionEvent {
    data class CommandFailed(val reason: String) : SessionEvent
    data object NoDeviceConfigured : SessionEvent
    data object TvStillAsleep : SessionEvent
}

/**
 * Keeps the remote's connection alive. It:
 * - queues commands and sends bursts of key presses (fast rotary turns) in one call,
 * - connects on demand and retries once if the connection dropped,
 * - releases the connection and Wi-Fi after the idle timeout, or when the app leaves the foreground,
 * - reconnects automatically with backoff while in the foreground, for errors that may clear on their own.
 */
class RemoteSession(
    private val connection: FireTvConnection,
    private val network: NetworkGate,
    private val devices: DeviceRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val appDetailsCache: AppDetailsCache = InMemoryAppDetailsCache(),
    private val reconnectDelaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000),
) {
    private val lifecycle = Mutex()
    private val queue = Channel<RemoteCommand>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val networkFailure = MutableStateFlow<FireTvDevice?>(null)
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    @Volatile private var foreground = false
    private var networkHeld = false
    private var idleJob: Job? = null
    private var reconnectJob: Job? = null

    /** The connection state, or a NO_WIFI failure if Wi-Fi could not be obtained. */
    val state: StateFlow<ConnectionState> =
        combine(connection.state, networkFailure) { s, noWifi ->
            if (noWifi != null) ConnectionState.Failed(noWifi, ConnectionError.NO_WIFI) else s
        }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    val lastLatencyMs: StateFlow<Long?> = connection.lastLatencyMs

    init {
        scope.launch { processQueue() }
        scope.launch {
            devices.activeDevice.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { onActiveDeviceChanged(it) }
        }
        scope.launch { connection.state.collect { onConnectionState(it) } }
    }

    fun press(key: RemoteKey) = submit(RemoteCommand.Key(key))

    fun submit(command: RemoteCommand) {
        queue.trySend(command)
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            scope.launch {
                if (!ensureConnected()) return@launch
                scheduleIdleRelease()
                if (settings.settings.first().wakeOnLaunch) wake(reportFailure = false)
            }
        } else {
            scope.launch { release() }
        }
    }

    /** Wakes the Fire TV (see [FireTvConnection.wakeUp]). */
    fun wakeTv() {
        scope.launch { if (ensureConnected()) wake(reportFailure = true) }
    }

    private suspend fun wake(reportFailure: Boolean) {
        val awake = connection.wakeUp()
        scheduleIdleRelease()
        if (reportFailure && awake.getOrNull() == false) _events.tryEmit(SessionEvent.TvStillAsleep)
        awake.exceptionOrNull()?.let { if (reportFailure) _events.tryEmit(SessionEvent.CommandFailed(it.message ?: "wake")) }
    }

    /** Saves [device], makes it active and connects. Used by the pairing screen. */
    suspend fun pair(device: FireTvDevice): Boolean {
        devices.upsert(device)
        devices.setActive(device.id)
        val ok = ensureConnected(device)
        if (ok) scheduleIdleRelease()
        return ok
    }

    /** Retries right away after a failure the user may have fixed (e.g. accepted the prompt). */
    fun retryNow() {
        scope.launch { if (ensureConnected()) scheduleIdleRelease() }
    }

    suspend fun listApps(): Result<List<TvApp>> {
        if (!ensureConnected()) return Result.failure(IllegalStateException("Not connected"))
        scheduleIdleRelease()
        return connection.listLaunchablePackages().map { packages ->
            packages.map(KnownApps::toApp)
                .sortedWith(compareByDescending<TvApp> { KnownApps.isKnown(it.packageName) }.thenBy { it.label.lowercase() })
        }
    }

    /**
     * Label and icon for [packageName]: from the cache, or from the TV when connected (no
     * reconnect just for icons). [refresh] skips the cache.
     */
    suspend fun appDetails(packageName: String, refresh: Boolean = false): AppDetails? {
        if (!refresh) appDetailsCache.get(packageName)?.let { return it }
        if (connection.state.value !is ConnectionState.Connected) return null
        return connection.appDetails(packageName).getOrNull()?.also {
            appDetailsCache.put(it)
            scheduleIdleRelease()
        }
    }

    suspend fun release() = lifecycle.withLock { releaseLocked() }

    private suspend fun processQueue() {
        while (true) {
            val first = queue.receive()
            val pending = mutableListOf(first)
            while (pending.size < MAX_DRAIN) {
                pending += queue.tryReceive().getOrNull() ?: break
            }
            for (group in groupConsecutiveKeys(pending)) execute(group)
        }
    }

    private suspend fun execute(group: List<RemoteCommand>) {
        repeat(2) { attempt ->
            // A failed connect is already visible in [state]; nothing more to report here.
            if (!ensureConnected()) return
            val result = send(group)
            scheduleIdleRelease()
            val error = result.exceptionOrNull() ?: return
            val connectionLost = error is TransportException || error is NotConnectedException
            if (!connectionLost || attempt == 1) {
                _events.tryEmit(SessionEvent.CommandFailed(error.message ?: error.javaClass.simpleName))
                return
            }
            // Connection dropped (e.g. Wi-Fi blip): reconnect once and resend.
        }
    }

    private suspend fun send(group: List<RemoteCommand>): Result<Unit> = when (val head = group.first()) {
        is RemoteCommand.Key -> connection.sendKeys(group.map { (it as RemoteCommand.Key).key })
        is RemoteCommand.Text -> connection.sendText(head.text, head.pressEnter)
        is RemoteCommand.LaunchApp -> connection.launchApp(head.packageName)
    }

    private suspend fun ensureConnected(target: FireTvDevice? = null): Boolean = lifecycle.withLock {
        val device = target ?: devices.activeDevice.first()
        if (device == null) {
            _events.tryEmit(SessionEvent.NoDeviceConfigured)
            return@withLock false
        }
        val current = connection.state.value
        if (current is ConnectionState.Connected && current.device.id == device.id) return@withLock true
        if (!networkHeld) {
            networkFailure.value = null
            if (!network.acquire()) {
                network.release()
                networkFailure.value = device
                return@withLock false
            }
            networkHeld = true
        }
        networkFailure.value = null
        val result = connection.connect(device)
        if (result.isSuccess && !device.authorized) devices.markAuthorized(device.id)
        result.isSuccess
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            val seconds = settings.settings.first().idleTimeoutSeconds
            delay(seconds * 1_000L)
            lifecycle.withLock {
                idleJob = null
                releaseLocked(cancelIdle = false)
            }
        }
    }

    private suspend fun releaseLocked(cancelIdle: Boolean = true) {
        if (cancelIdle) {
            idleJob?.cancel()
            idleJob = null
        }
        reconnectJob?.cancel()
        reconnectJob = null
        connection.disconnect()
        if (networkHeld) {
            network.release()
            networkHeld = false
        }
        networkFailure.value = null
    }

    private suspend fun onActiveDeviceChanged(device: FireTvDevice?) {
        val switched = lifecycle.withLock {
            val connectedTo = connection.state.value.device()
            if (connectedTo != null && connectedTo.id != device?.id) {
                releaseLocked()
                true
            } else {
                false
            }
        }
        if (switched && foreground && device != null) {
            if (ensureConnected()) scheduleIdleRelease()
        }
    }

    private fun onConnectionState(state: ConnectionState) {
        if (state !is ConnectionState.Failed || state.error !in AUTO_RETRY_ERRORS) return
        if (!foreground || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            for (delayMs in reconnectDelaysMs) {
                delay(delayMs)
                if (!foreground) return@launch
                // Stop when the session was released (idle/background) in the meantime.
                if (connection.state.value is ConnectionState.Disconnected) return@launch
                if (ensureConnected()) {
                    scheduleIdleRelease()
                    return@launch
                }
            }
        }
    }

    companion object {
        private const val MAX_DRAIN = 32
        private val AUTO_RETRY_ERRORS = setOf(ConnectionError.IO, ConnectionError.UNREACHABLE)

        /** Groups runs of key presses so each run becomes one shell call. Order is kept. */
        fun groupConsecutiveKeys(commands: List<RemoteCommand>): List<List<RemoteCommand>> {
            val groups = mutableListOf<MutableList<RemoteCommand>>()
            for (c in commands) {
                val last = groups.lastOrNull()
                if (c is RemoteCommand.Key && last != null && last.first() is RemoteCommand.Key) last += c
                else groups += mutableListOf(c)
            }
            return groups
        }
    }
}

fun ConnectionState.device(): FireTvDevice? = when (this) {
    ConnectionState.Disconnected -> null
    is ConnectionState.Connecting -> device
    is ConnectionState.AwaitingAuthorization -> device
    is ConnectionState.Connected -> device
    is ConnectionState.Failed -> device
}
