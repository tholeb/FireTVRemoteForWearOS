package dev.firetvonwatch.domain

import dev.firetvonwatch.domain.model.DiscoveredDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NoWifiException : Exception("Wi-Fi is not available")

/**
 * Runs all discovery sources in parallel, holding Wi-Fi for the duration. Emits one entry per
 * host. An mDNS result (which has a real name) replaces an earlier subnet-scan hit for the same host.
 */
class CompositeDeviceDiscovery(
    private val network: NetworkGate,
    private val sources: List<DeviceDiscovery>,
) : DeviceDiscovery {

    override fun discover(): Flow<DiscoveredDevice> = channelFlow {
        if (!network.acquire()) {
            network.release()
            throw NoWifiException()
        }
        try {
            val seen = mutableMapOf<String, DiscoveredDevice>()
            val lock = Mutex()
            sources.map { source ->
                launch {
                    source.discover().collect { found ->
                        val emit = lock.withLock {
                            val previous = seen[found.host]
                            val better = previous == null ||
                                (previous.source == DiscoveredDevice.Source.SUBNET_SCAN &&
                                    found.source == DiscoveredDevice.Source.MDNS)
                            if (better) seen[found.host] = found
                            better
                        }
                        if (emit) send(found)
                    }
                }
            }.joinAll()
        } finally {
            network.release()
        }
    }
}
