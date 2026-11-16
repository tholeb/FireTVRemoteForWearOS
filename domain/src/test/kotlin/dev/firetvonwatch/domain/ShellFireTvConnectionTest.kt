package dev.firetvonwatch.domain

import dev.firetvonwatch.domain.model.ConnectionError
import dev.firetvonwatch.domain.model.ConnectionState
import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.model.InputDialect
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.protocol.ShellFireTvConnection
import dev.firetvonwatch.domain.protocol.ShellResult
import dev.firetvonwatch.domain.protocol.ShellTransportFactory
import dev.firetvonwatch.domain.protocol.TransportException
import dev.firetvonwatch.domain.protocol.InjectorPayload
import dev.firetvonwatch.domain.protocol.ShellCommandBuilder
import dev.firetvonwatch.domain.protocol.InjectorProtocol
import dev.firetvonwatch.testing.FakeInteractiveShell
import dev.firetvonwatch.testing.FakeShellTransport
import dev.firetvonwatch.testing.FakeShellTransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShellFireTvConnectionTest {
    private val device = FireTvDevice("tv", "TV", "192.168.1.42")

    @Test fun `connect probes the TV and picks cmd input`() = runTest {
        val factory = FakeShellTransportFactory()
        val connection = ShellFireTvConnection(factory, StandardTestDispatcher(testScheduler))

        val info = connection.connect(device).getOrThrow()

        assertEquals(InputDialect.CMD_INPUT, info.inputDialect)
        assertEquals("AFTKRT", info.model)
        assertTrue(connection.state.value is ConnectionState.Connected)
        connection.sendKeys(listOf(RemoteKey.HOME)).getOrThrow()
        assertEquals("cmd input keyevent 3", factory.opened.single().commands.last())
    }

    @Test fun `legacy TV uses the input wrapper`() = runTest {
        val factory = FakeShellTransportFactory(transportProvider = {
            FakeShellTransport { if (it.startsWith("getprop")) ShellResult(0, "AFTMM\n28\n0") else ShellResult(0, "") }
        })
        val connection = ShellFireTvConnection(factory, StandardTestDispatcher(testScheduler))
        connection.connect(device).getOrThrow()
        connection.sendKeys(listOf(RemoteKey.DPAD_LEFT, RemoteKey.DPAD_LEFT))
        assertEquals("input keyevent 21 21", factory.opened.single().commands.last())
    }

    @Test fun `transport errors map to failed state`() = runTest {
        val factory = FakeShellTransportFactory(nextError = ConnectionError.REFUSED)
        val connection = ShellFireTvConnection(factory, StandardTestDispatcher(testScheduler))

        val result = connection.connect(device)

        assertTrue(result.isFailure)
        val state = connection.state.value as ConnectionState.Failed
        assertEquals(ConnectionError.REFUSED, state.error)
    }

    /** Real time on purpose: the open() blocks on a real thread, so virtual time would skip the probe timeout. */
    @Test fun `slow handshake shows awaiting authorization`() = runBlocking {
        val release = CountDownLatch(1)
        val factory = ShellTransportFactory { _, _ ->
            release.await(5, TimeUnit.SECONDS)
            FakeShellTransport(FakeShellTransport::fireOs8)
        }
        val connection = ShellFireTvConnection(factory, Dispatchers.IO, authHintDelayMs = 100)
        val pending = async(Dispatchers.Default) { connection.connect(device) }
        val deadline = System.currentTimeMillis() + 3_000
        while (connection.state.value !is ConnectionState.AwaitingAuthorization) {
            check(System.currentTimeMillis() < deadline) { "state=${connection.state.value}" }
            Thread.sleep(10)
        }
        release.countDown()
        pending.await().getOrThrow()
        assertTrue(connection.state.value is ConnectionState.Connected)
    }

    @Test fun `non zero exit is a rejected command but keeps the connection`() = runTest {
        val factory = FakeShellTransportFactory(transportProvider = {
            FakeShellTransport { if (it.startsWith("getprop")) ShellResult(0, "M\n30\n1") else ShellResult(1, "Error: boom") }
        })
        val connection = ShellFireTvConnection(factory, StandardTestDispatcher(testScheduler))
        connection.connect(device).getOrThrow()

        val error = connection.launchApp("com.netflix.ninja").exceptionOrNull()

        assertTrue(error is CommandRejectedException)
        assertEquals("Error: boom", error!!.message)
        assertTrue(connection.state.value is ConnectionState.Connected)
    }

    @Test fun `hanging command times out, closes the transport and reports IO`() = runBlocking {
        val transport = FakeShellTransport(FakeShellTransport::fireOs8)
        val connection = ShellFireTvConnection({ _, _ -> transport }, Dispatchers.IO, commandTimeoutMs = 200)
        connection.connect(device).getOrThrow()
        transport.hang = true

        val error = connection.sendKeys(listOf(RemoteKey.SELECT)).exceptionOrNull()

        assertTrue(error is TransportException)
        assertTrue(transport.closed)
        assertEquals(ConnectionError.IO, (connection.state.value as ConnectionState.Failed).error)
    }

    @Test fun `unsupported text is rejected without touching the TV`() = runTest {
        val factory = FakeShellTransportFactory()
        val connection = ShellFireTvConnection(factory, StandardTestDispatcher(testScheduler))
        connection.connect(device).getOrThrow()
        val before = factory.opened.single().commands.size
        assertTrue(connection.sendText("naïve", false).isFailure)
        assertEquals(before, factory.opened.single().commands.size)
    }

    @Test fun `sending while disconnected fails fast`() = runTest {
        val connection = ShellFireTvConnection(FakeShellTransportFactory(), StandardTestDispatcher(testScheduler))
        assertTrue(connection.sendKeys(listOf(RemoteKey.HOME)).exceptionOrNull() is NotConnectedException)
    }

    @Test fun `disconnect closes the transport`() = runTest {
        val factory = FakeShellTransportFactory()
        val connection = ShellFireTvConnection(factory, StandardTestDispatcher(testScheduler))
        connection.connect(device).getOrThrow()
        connection.disconnect()
        assertTrue(factory.opened.single().closed)
        assertEquals(ConnectionState.Disconnected, connection.state.value)
    }

}

class InjectorTest {
    private val device = FireTvDevice("tv", "TV", "192.168.1.42")
    private val payload = InjectorPayload(byteArrayOf(1, 2, 3))

    private fun connection(factory: FakeShellTransportFactory) =
        ShellFireTvConnection(factory, kotlinx.coroutines.Dispatchers.IO, injectorPayload = { payload })

    private fun awaitFastInput(c: ShellFireTvConnection, expected: Boolean = true) {
        val deadline = System.currentTimeMillis() + 5_000
        while (((c.state.value as? ConnectionState.Connected)?.info?.fastInput ?: false) != expected) {
            check(System.currentTimeMillis() < deadline) { "fastInput never became $expected; state=${c.state.value}" }
            Thread.sleep(10)
        }
    }

    @Test fun `injector is pushed, started and used for keys`() = runBlocking {
        val factory = FakeShellTransportFactory()
        val c = connection(factory)
        c.connect(device).getOrThrow()
        awaitFastInput(c)
        val t = factory.opened.single()
        assertTrue(payload.remotePath in t.pushed)
        assertTrue("old versions are cleaned up before pushing", t.commands.any { it == InjectorProtocol.cleanupOthers(payload) })
        assertEquals(InjectorProtocol.startCommand(payload), t.interactive.single().command)

        val execsBefore = t.commands.size
        c.sendKeys(listOf(RemoteKey.DPAD_UP, RemoteKey.DPAD_UP, RemoteKey.SELECT)).getOrThrow()
        assertEquals(listOf("K 19 19 23"), t.interactive.single().written)
        assertEquals("keys must not go through exec", execsBefore, t.commands.size)
    }

    @Test fun `already present injector is not pushed again`() = runBlocking {
        val factory = FakeShellTransportFactory(transportProvider = {
            FakeShellTransport { cmd ->
                if (cmd.contains("sha256sum")) ShellResult(0, "present") else FakeShellTransport.fireOs8(cmd)
            }
        })
        val c = connection(factory)
        c.connect(device).getOrThrow()
        awaitFastInput(c)
        assertTrue(factory.opened.single().pushed.isEmpty())
    }

    @Test fun `injector that fails to start leaves the fallback in place`() = runBlocking {
        val factory = FakeShellTransportFactory(transportProvider = {
            FakeShellTransport(FakeShellTransport::fireOs8).apply { interactiveFactory = { FakeInteractiveShell.broken() } }
        })
        val c = connection(factory)
        c.connect(device).getOrThrow()
        val t = factory.opened.single()
        val deadline = System.currentTimeMillis() + 5_000
        while (!(t.interactive.firstOrNull()?.closed ?: false)) {
            check(System.currentTimeMillis() < deadline); Thread.sleep(10)
        }
        c.sendKeys(listOf(RemoteKey.HOME)).getOrThrow()
        assertEquals("cmd input keyevent 3", t.commands.last())
        assertFalse((c.state.value as ConnectionState.Connected).info.fastInput)
    }

    @Test fun `injector that stops answering is dropped and the key is resent via fallback`() = runBlocking {
        val factory = FakeShellTransportFactory()
        val c = ShellFireTvConnection(factory, kotlinx.coroutines.Dispatchers.IO, injectorPayload = { payload }, injectorTimeoutMs = 200)
        c.connect(device).getOrThrow()
        awaitFastInput(c)
        val t = factory.opened.single()
        t.interactive.single().onLine = { null } // hangs

        c.sendKeys(listOf(RemoteKey.BACK)).getOrThrow()

        assertEquals("cmd input keyevent 4", t.commands.last())
        assertTrue(t.interactive.single().closed)
        awaitFastInput(c, expected = false)
        assertTrue(c.state.value is ConnectionState.Connected)
    }

    @Test fun `disconnect stops the injector`() = runBlocking {
        val factory = FakeShellTransportFactory()
        val c = connection(factory)
        c.connect(device).getOrThrow()
        awaitFastInput(c)
        c.disconnect()
        assertTrue(factory.opened.single().interactive.single().closed)
    }

    @Test fun `payload id is a stable content hash`() {
        assertEquals(InjectorPayload(byteArrayOf(1, 2, 3)).id, payload.id)
        assertTrue(InjectorPayload(byteArrayOf(9)).id != payload.id)
        assertTrue(payload.remotePath.startsWith("/data/local/tmp/firetvonwatch/injector-"))
        assertEquals("K 3 4", InjectorProtocol.keys(listOf(RemoteKey.HOME, RemoteKey.BACK)))
        val start = InjectorProtocol.startCommand(payload)
        assertTrue(start.contains("chmod 700 /data/local/tmp/firetvonwatch"))
        assertTrue(start.contains("chmod 644 ${payload.remotePath} && ANDROID_DATA=/data/local/tmp/firetvonwatch "))
        assertTrue(InjectorProtocol.presenceCheck(payload).contains("in ${payload.id}*) echo present"))
    }

    @Test fun `app details come from the injector, ERR is a failure that keeps it running`() = runBlocking {
        val factory = FakeShellTransportFactory()
        val c = connection(factory)
        c.connect(device).getOrThrow()
        awaitFastInput(c)

        val netflix = c.appDetails("com.netflix.ninja").getOrThrow()
        assertEquals("Netflix", netflix.label)
        assertArrayEquals(byteArrayOf(1, 2, 3), netflix.icon)

        assertTrue(c.appDetails("org.example.gone").isFailure)
        assertTrue("an ERR reply must not drop the injector", (c.state.value as ConnectionState.Connected).info.fastInput)
        assertTrue(c.appDetails("bad;pkg").isFailure)
    }

    @Test fun `app info replies are parsed defensively`() {
        val ok = InjectorProtocol.parseAppInfo("com.a.b", "I TmV0ZmxpeA== -")!!
        assertEquals("Netflix", ok.label)
        assertEquals(null, ok.icon)
        assertEquals("com.a.b", InjectorProtocol.parseAppInfo("com.a.b", "I  -")?.label ?: "com.a.b")
        assertEquals(null, InjectorProtocol.parseAppInfo("com.a.b", "ERR info X"))
        assertEquals(null, InjectorProtocol.parseAppInfo("com.a.b", "I %%% -"))
        assertEquals(null, InjectorProtocol.parseAppInfo("com.a.b", null))
    }
}

class WakeUpTest {
    private val device = FireTvDevice("tv", "TV", "192.168.1.42")

    /** Fake TV whose `dumpsys power` answers come from [states] in order. */
    private fun connection(states: MutableList<String>, scope: kotlinx.coroutines.test.TestScope): Pair<ShellFireTvConnection, FakeShellTransportFactory> {
        val factory = FakeShellTransportFactory(transportProvider = {
            FakeShellTransport { cmd ->
                if (cmd == ShellCommandBuilder.POWER_STATE) {
                    ShellResult(0, "  mWakefulness=${states.removeAt(0)}\n  mWakefulnessChanging=false\n")
                } else {
                    FakeShellTransport.fireOs8(cmd)
                }
            }
        })
        return ShellFireTvConnection(factory, StandardTestDispatcher(scope.testScheduler)) to factory
    }

    private fun FakeShellTransportFactory.keyCommands() = opened.single().commands.filter { it.startsWith("cmd input keyevent") }

    @Test fun `already awake TV gets no key at all`() = runTest {
        val (c, f) = connection(mutableListOf("Awake"), this)
        c.connect(device).getOrThrow()
        assertEquals(true, c.wakeUp().getOrThrow())
        assertEquals(emptyList<String>(), f.keyCommands())
    }

    @Test fun `WAKEUP is enough when it works`() = runTest {
        val (c, f) = connection(mutableListOf("Asleep", "Awake"), this)
        c.connect(device).getOrThrow()
        assertEquals(true, c.wakeUp().getOrThrow())
        assertEquals(listOf("cmd input keyevent 224"), f.keyCommands())
    }

    @Test fun `POWER is sent only when the TV is verifiably still asleep`() = runTest {
        val (c, f) = connection(mutableListOf("Asleep", "Dozing", "Awake"), this)
        c.connect(device).getOrThrow()
        assertEquals(true, c.wakeUp().getOrThrow())
        assertEquals(listOf("cmd input keyevent 224", "cmd input keyevent 26"), f.keyCommands())
    }

    @Test fun `reports a TV that stays asleep`() = runTest {
        val (c, _) = connection(mutableListOf("Asleep", "Asleep", "Asleep"), this)
        c.connect(device).getOrThrow()
        assertEquals(false, c.wakeUp().getOrThrow())
    }

    @Test fun `unknown power state never triggers POWER`() = runTest {
        val (c, f) = connection(mutableListOf("Asleep", "Weird"), this)
        c.connect(device).getOrThrow()
        assertEquals(true, c.wakeUp().getOrThrow())
        assertEquals(listOf("cmd input keyevent 224"), f.keyCommands())
    }

    @Test fun `power state parsing`() {
        assertEquals(true, ShellCommandBuilder.parseAwake("mWakefulness=Awake"))
        assertEquals(false, ShellCommandBuilder.parseAwake("x\n  mWakefulness=Asleep\n"))
        assertEquals(false, ShellCommandBuilder.parseAwake("mWakefulness=Dreaming"))
        assertEquals(null, ShellCommandBuilder.parseAwake("Permission Denial"))
    }
}
