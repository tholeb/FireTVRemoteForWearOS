package dev.firetvonwatch.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.firetvonwatch.domain.DeviceDiscovery
import dev.firetvonwatch.domain.model.DEFAULT_ADB_PORT
import dev.firetvonwatch.domain.model.DiscoveredDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.util.concurrent.Executors

/**
 * Finds Fire TVs through mDNS. Fire OS advertises Amazon's "Whisperplay" service
 * (`_amzn-wplay._tcp`). The ADB port isn't part of the advertisement, so the default 5555 is used.
 */
class NsdDeviceDiscovery(
    context: Context,
    private val serviceType: String = SERVICE_TYPE,
    private val durationMs: Long = 8_000,
) : DeviceDiscovery {
    private val nsd = context.getSystemService(NsdManager::class.java)

    override fun discover(): Flow<DiscoveredDevice> = callbackFlow {
        val executor = Executors.newSingleThreadExecutor()
        val infoCallbacks = mutableListOf<NsdManager.ServiceInfoCallback>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val cb = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                    override fun onServiceLost() = Unit
                    override fun onServiceInfoCallbackUnregistered() = Unit
                    override fun onServiceUpdated(info: NsdServiceInfo) {
                        val host = info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress ?: return
                        trySend(DiscoveredDevice(friendlyName(info), host, DEFAULT_ADB_PORT, DiscoveredDevice.Source.MDNS))
                    }
                }
                synchronized(infoCallbacks) { infoCallbacks += cb }
                runCatching { nsd.registerServiceInfoCallback(serviceInfo, executor, cb) }
            }
        }

        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { close(); return@callbackFlow }
        launch {
            delay(durationMs)
            close()
        }
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            synchronized(infoCallbacks) { infoCallbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } } }
            executor.shutdown()
        }
    }

    private fun friendlyName(info: NsdServiceInfo): String {
        val txtName = info.attributes["n"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        return txtName ?: info.serviceName
    }

    companion object {
        const val SERVICE_TYPE = "_amzn-wplay._tcp"
    }
}
