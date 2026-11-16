package dev.firetvonwatch.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.firetvonwatch.domain.model.AppDetails
import dev.firetvonwatch.domain.model.ConnectionState
import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.model.RemoteCommand
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.model.RemoteSettings
import dev.firetvonwatch.domain.model.TvApp
import dev.firetvonwatch.domain.session.SessionEvent
import dev.firetvonwatch.wear.AppDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

data class RemoteUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val activeDevice: FireTvDevice? = null,
    /** null until the device list has loaded from storage. */
    val devices: List<FireTvDevice>? = null,
    val settings: RemoteSettings = RemoteSettings(),
    val latencyMs: Long? = null,
)

sealed interface AppsState {
    data object Idle : AppsState
    data object Loading : AppsState
    data class Loaded(val apps: List<TvApp>) : AppsState
    data class Error(val message: String) : AppsState
}

class RemoteViewModel(private val deps: AppDependencies) : ViewModel() {
    private val session = deps.session

    val uiState: StateFlow<RemoteUiState> = combine(
        session.state,
        deps.deviceRepository.activeDevice,
        deps.deviceRepository.devices,
        deps.settingsRepository.settings,
        session.lastLatencyMs,
    ) { connection, active, devices, settings, latency ->
        RemoteUiState(connection, active, devices, settings, latency)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RemoteUiState())

    val events: SharedFlow<SessionEvent> = session.events

    private val _apps = MutableStateFlow<AppsState>(AppsState.Idle)
    val apps: StateFlow<AppsState> = _apps.asStateFlow()

    /** Labels and icons read from the TV, by package. Filled in the background. */
    private val _appDetails = MutableStateFlow<Map<String, AppDetails>>(emptyMap())
    val appDetails: StateFlow<Map<String, AppDetails>> = _appDetails.asStateFlow()
    private var detailsJob: Job? = null

    private val _fingerprint = MutableStateFlow<String?>(null)
    val fingerprint: StateFlow<String?> = _fingerprint.asStateFlow()

    fun press(key: RemoteKey) = session.press(key)

    fun pressAll(keys: List<RemoteKey>) = keys.forEach(session::press)

    fun sendText(text: String, pressEnter: Boolean) = session.submit(RemoteCommand.Text(text, pressEnter))

    fun launchApp(packageName: String) = session.submit(RemoteCommand.LaunchApp(packageName))

    fun retry() = session.retryNow()

    fun wakeTv() = session.wakeTv()

    fun loadApps() {
        if (_apps.value is AppsState.Loading) return
        _apps.value = AppsState.Loading
        viewModelScope.launch {
            val result = session.listApps()
            _apps.value = result.fold(
                onSuccess = { AppsState.Loaded(it) },
                onFailure = { AppsState.Error(it.message ?: it.javaClass.simpleName) },
            )
            result.getOrNull()?.let { apps -> loadDetails(uiState.value.settings.favoriteApps + apps.map { it.packageName }) }
        }
    }

    /** Fetches missing labels/icons one by one (cache first), favorites first. */
    fun loadDetails(packages: List<String>) {
        val missing = packages.distinct().filterNot { it in _appDetails.value }
        if (missing.isEmpty()) return
        val previous = detailsJob
        detailsJob = viewModelScope.launch {
            previous?.join()
            for (pkg in missing) {
                if (pkg in _appDetails.value) continue
                val details = session.appDetails(pkg) ?: continue
                _appDetails.update { it + (pkg to details) }
            }
        }
    }

    fun toggleFavorite(packageName: String) = updateSettings { s ->
        val favorites = if (packageName in s.favoriteApps) s.favoriteApps - packageName else s.favoriteApps + packageName
        s.copy(favoriteApps = favorites)
    }

    fun updateSettings(transform: (RemoteSettings) -> RemoteSettings) {
        viewModelScope.launch { deps.settingsRepository.update(transform) }
    }

    fun selectDevice(id: String) {
        viewModelScope.launch { deps.deviceRepository.setActive(id) }
    }

    fun forgetDevice(id: String) {
        viewModelScope.launch { deps.deviceRepository.remove(id) }
    }

    fun loadFingerprint() {
        viewModelScope.launch {
            _fingerprint.value = runCatching { withContext(Dispatchers.Default) { deps.keyFingerprint() } }.getOrNull()
        }
    }

    fun resetKey() {
        viewModelScope.launch {
            session.release()
            withContext(Dispatchers.Default) { deps.resetKey() }
            loadFingerprint()
        }
    }
}
