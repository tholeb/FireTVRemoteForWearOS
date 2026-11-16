package dev.firetvonwatch.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.model.RemoteSettings
import dev.firetvonwatch.domain.model.RotaryMode
import dev.firetvonwatch.domain.repository.DeviceListCodec
import dev.firetvonwatch.domain.repository.DeviceRepository
import dev.firetvonwatch.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object PreferenceStore {
    fun create(context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("firetv_remote") }
}

private object Keys {
    val devices = stringPreferencesKey("devices")
    val activeId = stringPreferencesKey("active_device_id")
    val rotaryMode = stringPreferencesKey("rotary_mode")
    val haptics = booleanPreferencesKey("haptics")
    val idleTimeout = intPreferencesKey("idle_timeout_s")
    val startInTouchpad = booleanPreferencesKey("start_touchpad")
    val favorites = stringPreferencesKey("favorite_apps")
    val volumeControl = booleanPreferencesKey("volume_control")
    val swipeBackCloses = booleanPreferencesKey("swipe_back_closes")
    val wakeOnLaunch = booleanPreferencesKey("wake_on_launch")
}

class DataStoreDeviceRepository(private val store: DataStore<Preferences>) : DeviceRepository {

    override val devices: Flow<List<FireTvDevice>> =
        store.data.map { DeviceListCodec.decode(it[Keys.devices]) }.distinctUntilChanged()

    override val activeDevice: Flow<FireTvDevice?> = store.data.map { prefs ->
        val list = DeviceListCodec.decode(prefs[Keys.devices])
        list.firstOrNull { it.id == prefs[Keys.activeId] } ?: list.firstOrNull()
    }.distinctUntilChanged()

    override suspend fun upsert(device: FireTvDevice) {
        store.edit { prefs ->
            val list = DeviceListCodec.decode(prefs[Keys.devices])
            val existing = list.firstOrNull { it.id == device.id }
            val updated = if (existing == null) list + device
            else list.map { if (it.id == device.id) device.copy(authorized = device.authorized || existing.authorized) else it }
            prefs[Keys.devices] = DeviceListCodec.encode(updated)
        }
    }

    override suspend fun remove(id: String) {
        store.edit { prefs ->
            val list = DeviceListCodec.decode(prefs[Keys.devices]).filterNot { it.id == id }
            prefs[Keys.devices] = DeviceListCodec.encode(list)
            if (prefs[Keys.activeId] == id) {
                val next = list.firstOrNull()
                if (next == null) prefs.remove(Keys.activeId) else prefs[Keys.activeId] = next.id
            }
        }
    }

    override suspend fun setActive(id: String) {
        store.edit { it[Keys.activeId] = id }
    }

    override suspend fun markAuthorized(id: String) {
        store.edit { prefs ->
            val list = DeviceListCodec.decode(prefs[Keys.devices]).map { if (it.id == id) it.copy(authorized = true) else it }
            prefs[Keys.devices] = DeviceListCodec.encode(list)
        }
    }
}

class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {

    override val settings: Flow<RemoteSettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    override suspend fun update(transform: (RemoteSettings) -> RemoteSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.rotaryMode] = next.rotaryMode.name
            prefs[Keys.haptics] = next.hapticsEnabled
            prefs[Keys.idleTimeout] = next.idleTimeoutSeconds
            prefs[Keys.startInTouchpad] = next.startInTouchpad
            prefs[Keys.favorites] = next.favoriteApps.joinToString(",")
            prefs[Keys.volumeControl] = next.volumeControlEnabled
            prefs[Keys.swipeBackCloses] = next.swipeBackCloses
            prefs[Keys.wakeOnLaunch] = next.wakeOnLaunch
        }
    }

    private fun Preferences.toSettings(): RemoteSettings {
        val defaults = RemoteSettings()
        return RemoteSettings(
            rotaryMode = this[Keys.rotaryMode]?.let { runCatching { RotaryMode.valueOf(it) }.getOrNull() } ?: defaults.rotaryMode,
            hapticsEnabled = this[Keys.haptics] ?: defaults.hapticsEnabled,
            idleTimeoutSeconds = this[Keys.idleTimeout] ?: defaults.idleTimeoutSeconds,
            startInTouchpad = this[Keys.startInTouchpad] ?: defaults.startInTouchpad,
            favoriteApps = this[Keys.favorites]?.split(',')?.filter { it.isNotBlank() } ?: defaults.favoriteApps,
            volumeControlEnabled = this[Keys.volumeControl] ?: defaults.volumeControlEnabled,
            swipeBackCloses = this[Keys.swipeBackCloses] ?: defaults.swipeBackCloses,
            wakeOnLaunch = this[Keys.wakeOnLaunch] ?: defaults.wakeOnLaunch,
        )
    }
}
