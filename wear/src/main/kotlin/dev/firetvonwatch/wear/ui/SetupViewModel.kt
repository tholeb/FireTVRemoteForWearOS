package dev.firetvonwatch.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.firetvonwatch.domain.NoWifiException
import dev.firetvonwatch.domain.model.DiscoveredDevice
import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.model.HostValidator
import dev.firetvonwatch.wear.AppDependencies
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverState(
    val scanning: Boolean = false,
    val found: List<DiscoveredDevice> = emptyList(),
    val noWifi: Boolean = false,
)

class SetupViewModel(private val deps: AppDependencies) : ViewModel() {
    private val _discover = MutableStateFlow(DiscoverState())
    val discover: StateFlow<DiscoverState> = _discover.asStateFlow()

    /** The device being paired on the Pair screen. */
    private val _pairing = MutableStateFlow<FireTvDevice?>(null)
    val pairing: StateFlow<FireTvDevice?> = _pairing.asStateFlow()

    private var scanJob: Job? = null

    fun startDiscovery() {
        scanJob?.cancel()
        _discover.value = DiscoverState(scanning = true)
        scanJob = viewModelScope.launch {
            try {
                deps.discovery.discover().collect { device ->
                    _discover.update { s -> s.copy(found = s.found.filterNot { it.host == device.host } + device) }
                }
            } catch (e: NoWifiException) {
                _discover.update { it.copy(noWifi = true) }
            } finally {
                _discover.update { it.copy(scanning = false) }
            }
        }
    }

    fun stopDiscovery() {
        scanJob?.cancel()
    }

    /** Returns false if [input] isn't a valid address. */
    fun selectManual(input: String, nameFor: (String) -> String): Boolean {
        val parsed = HostValidator.parse(input) ?: return false
        select(DiscoveredDevice(nameFor(parsed.host), parsed.host, parsed.port, DiscoveredDevice.Source.MANUAL))
        return true
    }

    fun select(device: DiscoveredDevice) {
        stopDiscovery()
        _pairing.value = device.toDevice()
    }

    fun pair() {
        val device = _pairing.value ?: return
        viewModelScope.launch { deps.session.pair(device) }
    }
}
