package dev.firetvonwatch.domain

import dev.firetvonwatch.domain.model.AppDetails
import dev.firetvonwatch.domain.model.ConnectionState
import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.model.TvInfo
import kotlinx.coroutines.flow.StateFlow

/** The link to one Fire TV. Methods return failures; they don't throw. */
interface FireTvConnection {
    val state: StateFlow<ConnectionState>

    /** How long (ms) the last command took from send to the TV's reply. */
    val lastLatencyMs: StateFlow<Long?>

    suspend fun connect(device: FireTvDevice): Result<TvInfo>
    suspend fun sendKeys(keys: List<RemoteKey>): Result<Unit>
    suspend fun sendText(text: String, pressEnter: Boolean): Result<Unit>
    suspend fun launchApp(packageName: String): Result<Unit>
    suspend fun listLaunchablePackages(): Result<List<String>>

    /** Label and icon of an installed app. Needs the on-TV injector; fails without it. */
    suspend fun appDetails(packageName: String): Result<AppDetails>
    suspend fun disconnect()
}

/** The TV ran the command but it failed (non-zero exit). The connection is still usable. */
class CommandRejectedException(message: String) : Exception(message)

class NotConnectedException : Exception("Not connected")
