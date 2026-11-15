package dev.firetvonwatch.domain

import dev.firetvonwatch.domain.model.HostValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostValidatorTest {
    @Test fun `accepts ipv4 with optional port`() {
        assertEquals(HostValidator.Parsed("192.168.1.42", 5555), HostValidator.parse(" 192.168.1.42 "))
        assertEquals(HostValidator.Parsed("10.0.0.7", 5556), HostValidator.parse("10.0.0.7:5556"))
    }

    @Test fun `accepts hostnames`() {
        assertEquals(HostValidator.Parsed("firetv.local", 5555), HostValidator.parse("firetv.local"))
    }

    @Test fun `rejects invalid input`() {
        listOf("", "256.1.1.1", "1.2.3", "1.2.3.4:0", "1.2.3.4:99999", "a:b:c", "bad host", "1.2.3.4:x")
            .forEach { assertNull(it, HostValidator.parse(it)) }
    }
}
