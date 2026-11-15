package dev.firetvonwatch.testing

import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.model.RemoteSettings
import dev.firetvonwatch.domain.repository.DeviceRepository
import dev.firetvonwatch.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

class InMemoryDeviceRepository(initial: List<FireTvDevice> = emptyList(), activeId: String? = initial.firstOrNull()?.id) :
    DeviceRepository {
    val list = MutableStateFlow(initial)
    val active = MutableStateFlow(activeId)

    override val devices: Flow<List<FireTvDevice>> = list
    override val activeDevice: Flow<FireTvDevice?> = combine(list, active) { l, id -> l.firstOrNull { it.id == id } }

    override suspend fun upsert(device: FireTvDevice) = list.update { current ->
        val existing = current.firstOrNull { it.id == device.id }
        if (existing == null) current + device
        else current.map { if (it.id == device.id) device.copy(authorized = device.authorized || existing.authorized) else it }
    }

    override suspend fun remove(id: String) {
        list.update { l -> l.filterNot { it.id == id } }
        if (active.value == id) active.value = list.value.firstOrNull()?.id
    }

    override suspend fun setActive(id: String) { active.value = id }

    override suspend fun markAuthorized(id: String) = list.update { l ->
        l.map { if (it.id == id) it.copy(authorized = true) else it }
    }
}

class InMemorySettingsRepository(initial: RemoteSettings = RemoteSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<RemoteSettings> = state
    override suspend fun update(transform: (RemoteSettings) -> RemoteSettings) = state.update(transform)
}
