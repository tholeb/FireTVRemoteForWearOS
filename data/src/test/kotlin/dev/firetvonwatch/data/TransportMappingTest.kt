package dev.firetvonwatch.data

import dev.firetvonwatch.data.adb.DadbShellTransportFactory
import dev.firetvonwatch.data.discovery.SubnetScanner
import dev.firetvonwatch.domain.model.ConnectionError
import dev.firetvonwatch.domain.protocol.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

class TransportMappingTest {
    @Test fun `handshake failures map to user-facing errors`() {
        assertEquals(
            ConnectionError.AUTH_TIMEOUT,
            DadbShellTransportFactory.mapHandshakeFailure(IOException("x", SocketTimeoutException())).error,
        )
        assertEquals(ConnectionError.AUTH_REJECTED, DadbShellTransportFactory.mapHandshakeFailure(EOFException()).error)
    }

    @Test fun `closed port is reported as refused, not unreachable`() {
        val port = ServerSocket(0).use { it.localPort } // bound then released -> nothing listens
        val factory = DadbShellTransportFactory(keyPair = { error("not reached") }, connectTimeoutMs = 500)
        val error = runCatching { factory.open("127.0.0.1", port) }.exceptionOrNull() as TransportException
        assertEquals(ConnectionError.REFUSED, error.error)
    }

    @Test fun `subnet candidates cover the slash 24 except self`() {
        val list = SubnetScanner.candidates(InetAddress.getByName("192.168.1.42") as Inet4Address)
        assertEquals(253, list.size)
        assertFalse("192.168.1.42" in list)
        assertEquals("192.168.1.1", list.first())
        assertEquals("192.168.1.254", list.last())
    }
}
