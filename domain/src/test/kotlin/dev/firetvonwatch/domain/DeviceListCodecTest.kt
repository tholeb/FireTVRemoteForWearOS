package dev.firetvonwatch.domain

import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.repository.DeviceListCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceListCodecTest {
    @Test fun `round trips including awkward names`() {
        val devices = listOf(
            FireTvDevice("1.2.3.4:5555", "Living room", "1.2.3.4", 5555, authorized = true),
            FireTvDevice("h:1", "Tab\tNew\nline \\ slash", "h", 1, authorized = false),
        )
        assertEquals(devices, DeviceListCodec.decode(DeviceListCodec.encode(devices)))
    }

    @Test fun `empty and corrupt input yields empty or partial list`() {
        assertEquals(emptyList<FireTvDevice>(), DeviceListCodec.decode(null))
        assertEquals(emptyList<FireTvDevice>(), DeviceListCodec.decode(""))
        assertEquals(emptyList<FireTvDevice>(), DeviceListCodec.decode("garbage"))
    }
}
