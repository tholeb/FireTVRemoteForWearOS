package dev.firetvonwatch.domain.repository

import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.model.RemoteSettings
import kotlinx.coroutines.flow.Flow

interface DeviceRepository {
    val devices: Flow<List<FireTvDevice>>
    val activeDevice: Flow<FireTvDevice?>

    /** Adds the device, or updates the stored one with the same id. */
    suspend fun upsert(device: FireTvDevice)
    suspend fun remove(id: String)
    suspend fun setActive(id: String)
    suspend fun markAuthorized(id: String)
}

interface SettingsRepository {
    val settings: Flow<RemoteSettings>
    suspend fun update(transform: (RemoteSettings) -> RemoteSettings)
}
