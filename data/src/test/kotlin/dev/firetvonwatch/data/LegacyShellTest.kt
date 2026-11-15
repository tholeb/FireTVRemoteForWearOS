package dev.firetvonwatch.data

import dadb.AdbStream
import dev.firetvonwatch.data.adb.LegacyInteractiveShell
import dev.firetvonwatch.data.adb.LegacyShell
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyShellTest {
    @Test fun `exit code marker is parsed and stripped, pty CRLF normalised`() {
        val r = LegacyShell.parse("AFTM\r\n22\r\n0\r\n__FTVW_RC=0\r\n")
        assertEquals(0, r.exitCode)
        assertEquals("AFTM\n22\n0\n", r.output)
        assertEquals(1, LegacyShell.parse("Error: no\n__FTVW_RC=1\n").exitCode)
        assertEquals(255, LegacyShell.parse("killed").exitCode)
        assertEquals("cmd x; echo \"__FTVW_RC=\$?\"", LegacyShell.wrap("cmd x"))
    }

    private class FakeStream(output: String) : AdbStream {
        val written = Buffer()
        override val source: BufferedSource = Buffer().writeUtf8(output)
        override val sink: BufferedSink = written
        override fun close() = Unit
    }

    @Test fun `pty echo of written lines is skipped`() {
        val stream = FakeStream("READY 1\r\nK 19 20\r\nOK\r\nK 4\r\nOK\r\n")
        val shell = LegacyInteractiveShell(stream)
        assertEquals("READY 1", shell.readLine())
        shell.writeLine("K 19 20")
        assertEquals("OK", shell.readLine())
        shell.writeLine("K 4")
        assertEquals("OK", shell.readLine())
        assertNull(shell.readLine())
        assertEquals("K 19 20\nK 4\n", stream.written.readUtf8())
    }
}
