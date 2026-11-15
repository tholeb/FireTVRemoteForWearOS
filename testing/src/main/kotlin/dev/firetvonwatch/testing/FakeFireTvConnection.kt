package dev.firetvonwatch.testing

import dev.firetvonwatch.domain.FireTvConnection
import dev.firetvonwatch.domain.NotConnectedException
import dev.firetvonwatch.domain.model.AppDetails
import dev.firetvonwatch.domain.model.ConnectionError
import dev.firetvonwatch.domain.model.ConnectionState
import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.model.InputDialect
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.model.TvInfo
import dev.firetvonwatch.domain.protocol.TransportException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory Fire TV used by session and UI tests. Records everything that is "sent". */
class FakeFireTvConnection(
    var info: TvInfo = TvInfo("AFTKRT", 30, InputDialect.CMD_INPUT),
) : FireTvConnection {

    sealed interface Sent {
        data class Keys(val keys: List<RemoteKey>) : Sent
        data class Text(val text: String, val pressEnter: Boolean) : Sent
        data class Launch(val packageName: String) : Sent
    }

    val sent = mutableListOf<Sent>()
    val connectAttempts = mutableListOf<FireTvDevice>()
    var disconnectCount = 0

    /** Error returned by upcoming connect() calls; null means success. */
    var connectError: ConnectionError? = null
    /** Number of upcoming send calls that fail with a dropped connection. */
    var failNextSends = 0
    var launchablePackages: List<String> = listOf("com.netflix.ninja", "com.amazon.firebat")

    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val lastLatencyMs: StateFlow<Long?> = MutableStateFlow(12L)

    override suspend fun connect(device: FireTvDevice): Result<TvInfo> {
        connectAttempts += device
        val error = connectError
        return if (error == null) {
            state.value = ConnectionState.Connected(device, info)
            Result.success(info)
        } else {
            state.value = ConnectionState.Failed(device, error)
            Result.failure(TransportException(error))
        }
    }

    override suspend fun sendKeys(keys: List<RemoteKey>) = record(Sent.Keys(keys))
    override suspend fun sendText(text: String, pressEnter: Boolean) = record(Sent.Text(text, pressEnter))
    override suspend fun launchApp(packageName: String) = record(Sent.Launch(packageName))

    override suspend fun listLaunchablePackages(): Result<List<String>> =
        if (state.value is ConnectionState.Connected) Result.success(launchablePackages)
        else Result.failure(NotConnectedException())

    /** Details returned by appDetails(); missing packages fail like an "ERR" reply. */
    var details: Map<String, AppDetails> = emptyMap()
    var appDetailsCalls = 0

    override suspend fun appDetails(packageName: String): Result<AppDetails> {
        appDetailsCalls++
        if (state.value !is ConnectionState.Connected) return Result.failure(NotConnectedException())
        return details[packageName]?.let { Result.success(it) } ?: Result.failure(IllegalStateException("ERR info"))
    }

    override suspend fun disconnect() {
        disconnectCount++
        state.value = ConnectionState.Disconnected
    }

    private fun record(item: Sent): Result<Unit> {
        val current = state.value as? ConnectionState.Connected ?: return Result.failure(NotConnectedException())
        if (failNextSends > 0) {
            failNextSends--
            state.value = ConnectionState.Failed(current.device, ConnectionError.IO)
            return Result.failure(TransportException(ConnectionError.IO, "dropped"))
        }
        sent += item
        return Result.success(Unit)
    }

    val sentKeys: List<RemoteKey> get() = sent.filterIsInstance<Sent.Keys>().flatMap { it.keys }
}
