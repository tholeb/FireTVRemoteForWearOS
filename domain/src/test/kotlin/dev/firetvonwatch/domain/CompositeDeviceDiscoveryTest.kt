package dev.firetvonwatch.domain

import dev.firetvonwatch.domain.model.DiscoveredDevice
import dev.firetvonwatch.domain.model.DiscoveredDevice.Source
import dev.firetvonwatch.testing.FakeNetworkGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeDeviceDiscoveryTest {
    @Test fun `dedupes by host and prefers mdns names`() = runTest {
        val scan = DeviceDiscovery {
            flow {
                emit(DiscoveredDevice("Android device", "10.0.0.5", source = Source.SUBNET_SCAN))
                emit(DiscoveredDevice("Android device", "10.0.0.9", source = Source.SUBNET_SCAN))
            }
        }
        val mdns = DeviceDiscovery {
            flow {
                delay(100)
                emit(DiscoveredDevice("Living room", "10.0.0.5", source = Source.MDNS))
                emit(DiscoveredDevice("Living room again", "10.0.0.5", source = Source.MDNS))
            }
        }
        val gate = FakeNetworkGate()
        val result = CompositeDeviceDiscovery(gate, listOf(scan, mdns)).discover().toList()
        val latest = result.associateBy { it.host }
        assertEquals("Living room", latest.getValue("10.0.0.5").name)
        assertEquals(3, result.size)
        assertEquals(0, gate.held)
    }

    @Test fun `no wifi throws and releases`() = runTest {
        val gate = FakeNetworkGate(available = false)
        val error = runCatching { CompositeDeviceDiscovery(gate, emptyList()).discover().toList() }.exceptionOrNull()
        assertTrue(error is NoWifiException)
        assertEquals(0, gate.held)
    }
}
