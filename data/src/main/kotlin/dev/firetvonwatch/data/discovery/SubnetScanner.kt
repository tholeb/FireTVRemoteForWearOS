package dev.firetvonwatch.data.discovery

import dev.firetvonwatch.domain.DeviceDiscovery
import dev.firetvonwatch.domain.model.DEFAULT_ADB_PORT
import dev.firetvonwatch.domain.model.DiscoveredDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Tries a TCP connect to the ADB port on every address in the watch's /24. A hit is any device
 * with network ADB on, not necessarily a Fire TV, so it has no name (the UI shows a generic,
 * translated one).
 */
class SubnetScanner(
    private val localAddress: () -> Pair<Inet4Address, Int>?,
    private val port: Int = DEFAULT_ADB_PORT,
    private val timeoutMs: Int = 400,
    private val concurrency: Int = 48,
) : DeviceDiscovery {

    override fun discover(): Flow<DiscoveredDevice> = channelFlow {
        val (address, _) = localAddress() ?: return@channelFlow
        val own = ownAddresses()
        val semaphore = Semaphore(concurrency)
        for (host in candidates(address).filterNot { it in own }) {
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    if (isOpen(host)) send(DiscoveredDevice("", host, port, DiscoveredDevice.Source.SUBNET_SCAN))
                }
            }
        }
    }

    private fun isOpen(host: String): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    }.getOrDefault(false)

    /** All IPv4 addresses of this watch (Wi-Fi, cellular, ...), so it never "finds" its own adbd. */
    private fun ownAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    companion object {
        /** All other addresses in the /24 containing [address]. */
        fun candidates(address: Inet4Address): List<String> {
            val b = address.address.map { it.toInt() and 0xFF }
            val own = b[3]
            return (1..254).filter { it != own }.map { "${b[0]}.${b[1]}.${b[2]}.$it" }
        }
    }
}
