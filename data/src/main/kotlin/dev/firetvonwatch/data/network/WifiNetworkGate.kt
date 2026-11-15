package dev.firetvonwatch.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.firetvonwatch.domain.NetworkGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

/**
 * Asks for a Wi-Fi network and binds the process to it.
 *
 * When a Wear OS watch is paired with a phone, the default network is often the phone's
 * Bluetooth proxy, which is slow and may not reach the LAN. Following Google's Wear OS
 * networking guidance, the app asks for Wi-Fi explicitly (which can turn the watch's Wi-Fi on)
 * and drops the request when it's no longer needed, so Wi-Fi isn't held longer than necessary.
 */
class WifiNetworkGate(context: Context) : NetworkGate {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var references = 0
    private var callback: ConnectivityManager.NetworkCallback? = null

    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network.asStateFlow()

    override suspend fun acquire(timeoutMs: Long): Boolean {
        synchronized(lock) {
            references++
            if (callback == null) register()
        }
        return withTimeoutOrNull(timeoutMs) { _network.filterNotNull().first() } != null
    }

    override fun release() {
        synchronized(lock) {
            if (references == 0) return
            references--
            if (references == 0) unregister()
        }
    }

    /** IPv4 address and prefix length of the bound Wi-Fi network, for the subnet scan. */
    fun currentIpv4(): Pair<Inet4Address, Int>? {
        val network = _network.value ?: return null
        val link = connectivity.getLinkProperties(network) ?: return null
        val address = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return (address.address as Inet4Address) to address.prefixLength
    }

    private fun register() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivity.bindProcessToNetwork(network)
                _network.value = network
            }

            override fun onLost(network: Network) {
                if (_network.value == network) {
                    _network.value = null
                    connectivity.bindProcessToNetwork(null)
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.requestNetwork(request, cb)
        callback = cb
    }

    private fun unregister() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        connectivity.bindProcessToNetwork(null)
        _network.value = null
    }
}
