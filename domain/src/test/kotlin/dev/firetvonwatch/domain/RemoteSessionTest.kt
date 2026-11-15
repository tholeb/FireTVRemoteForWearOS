package dev.firetvonwatch.domain

import dev.firetvonwatch.domain.model.AppDetails
import dev.firetvonwatch.domain.model.ConnectionError
import dev.firetvonwatch.domain.model.ConnectionState
import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.model.RemoteCommand
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.model.RemoteSettings
import dev.firetvonwatch.domain.session.RemoteSession
import dev.firetvonwatch.domain.session.SessionEvent
import dev.firetvonwatch.testing.FakeFireTvConnection
import dev.firetvonwatch.testing.FakeNetworkGate
import dev.firetvonwatch.testing.InMemoryDeviceRepository
import dev.firetvonwatch.testing.InMemorySettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSessionTest {
    private val tv = FireTvDevice("tv", "Living room", "192.168.1.42", authorized = true)

    private class Env(
        val connection: FakeFireTvConnection = FakeFireTvConnection(),
        val network: FakeNetworkGate = FakeNetworkGate(),
        val devices: InMemoryDeviceRepository,
        val settings: InMemorySettingsRepository = InMemorySettingsRepository(),
    )

    private fun TestScope.session(env: Env) =
        RemoteSession(env.connection, env.network, env.devices, env.settings, backgroundScope)

    @Test fun `press connects on demand and sends the key`() = runTest {
        val env = Env(devices = InMemoryDeviceRepository(listOf(tv)))
        val session = session(env)
        session.press(RemoteKey.HOME)
        runCurrent()
        assertEquals(listOf(RemoteKey.HOME), env.connection.sentKeys)
        assertEquals(1, env.network.held)
    }

    @Test fun `burst of keys is coalesced into one call`() = runTest {
        val env = Env(devices = InMemoryDeviceRepository(listOf(tv)))
        val session = session(env)
        repeat(5) { session.press(RemoteKey.DPAD_DOWN) }
        session.submit(RemoteCommand.Text("hi"))
        session.press(RemoteKey.SELECT)
        runCurrent()
        assertEquals(
            listOf(
                FakeFireTvConnection.Sent.Keys(List(5) { RemoteKey.DPAD_DOWN }),
                FakeFireTvConnection.Sent.Text("hi", false),
                FakeFireTvConnection.Sent.Keys(listOf(RemoteKey.SELECT)),
            ),
            env.connection.sent,
        )
    }

    @Test fun `idle timeout releases connection and wifi`() = runTest {
        val env = Env(
            devices = InMemoryDeviceRepository(listOf(tv)),
            settings = InMemorySettingsRepository(RemoteSettings(idleTimeoutSeconds = 30)),
        )
        val session = session(env)
        session.press(RemoteKey.HOME)
        runCurrent()
        advanceTimeBy(29_000); runCurrent()
        assertTrue(env.connection.state.value is ConnectionState.Connected)
        session.press(RemoteKey.BACK) // activity restarts the timer
        runCurrent()
        advanceTimeBy(29_000); runCurrent()
        assertTrue(env.connection.state.value is ConnectionState.Connected)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(ConnectionState.Disconnected, env.connection.state.value)
        assertEquals(0, env.network.held)
    }

    @Test fun `going to background releases immediately`() = runTest {
        val env = Env(devices = InMemoryDeviceRepository(listOf(tv)))
        val session = session(env)
        session.setForeground(true)
        runCurrent()
        assertTrue(env.connection.state.value is ConnectionState.Connected)
        session.setForeground(false)
        runCurrent()
        assertEquals(ConnectionState.Disconnected, env.connection.state.value)
        assertEquals(0, env.network.held)
    }

    @Test fun `dropped connection is reconnected and the command resent`() = runTest {
        val env = Env(devices = InMemoryDeviceRepository(listOf(tv)))
        val session = session(env)
        session.press(RemoteKey.HOME)
        runCurrent()
        env.connection.failNextSends = 1
        session.press(RemoteKey.PLAY_PAUSE)
        runCurrent()
        assertEquals(listOf(RemoteKey.HOME, RemoteKey.PLAY_PAUSE), env.connection.sentKeys)
        assertEquals(2, env.connection.connectAttempts.size)
    }

    @Test fun `auto reconnect with backoff while in foreground`() = runTest {
        val env = Env(devices = InMemoryDeviceRepository(listOf(tv)))
        env.connection.connectError = ConnectionError.UNREACHABLE
        val session = session(env)
        session.setForeground(true)
        runCurrent()
        assertEquals(1, env.connection.connectAttempts.size)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, env.connection.connectAttempts.size)
        env.connection.connectError = null
        advanceTimeBy(2_001); runCurrent()
        assertEquals(3, env.connection.connectAttempts.size)
        assertTrue(session.state.value is ConnectionState.Connected)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(3, env.connection.connectAttempts.size)
    }

    @Test fun `authorization rejection is not retried automatically`() = runTest {
        val env = Env(devices = InMemoryDeviceRepository(listOf(tv)))
        env.connection.connectError = ConnectionError.AUTH_REJECTED
        val session = session(env)
        session.setForeground(true)
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, env.connection.connectAttempts.size)
    }

    @Test fun `no wifi surfaces as NO_WIFI failure`() = runTest {
        val env = Env(devices = InMemoryDeviceRepository(listOf(tv)), network = FakeNetworkGate(available = false))
        val session = session(env)
        session.press(RemoteKey.HOME)
        runCurrent()
        val state = session.state.value as ConnectionState.Failed
        assertEquals(ConnectionError.NO_WIFI, state.error)
        assertEquals(0, env.network.held)
        assertTrue(env.connection.sent.isEmpty())
    }

    @Test fun `pair stores device, makes it active and marks it authorized`() = runTest {
        val env = Env(devices = InMemoryDeviceRepository())
        val session = session(env)
        val newTv = FireTvDevice("new", "Bedroom", "192.168.1.57")
        assertTrue(session.pair(newTv))
        val stored = env.devices.activeDevice.first()!!
        assertEquals("new", stored.id)
        assertTrue(stored.authorized)
    }

    @Test fun `switching active device drops the old connection`() = runTest {
        val other = FireTvDevice("other", "Bedroom", "192.168.1.57", authorized = true)
        val env = Env(devices = InMemoryDeviceRepository(listOf(tv, other)))
        val session = session(env)
        session.setForeground(true)
        runCurrent()
        env.devices.setActive("other")
        runCurrent()
        val state = session.state.value as ConnectionState.Connected
        assertEquals("other", state.device.id)
    }

    @Test fun `missing device emits event`() = runTest {
        val env = Env(devices = InMemoryDeviceRepository())
        val session = session(env)
        val events = mutableListOf<SessionEvent>()
        backgroundScope.launch { session.events.collect { events += it } }
        runCurrent()
        session.press(RemoteKey.HOME)
        runCurrent()
        assertEquals(listOf(SessionEvent.NoDeviceConfigured), events)
    }

    @Test fun `listApps puts known apps first`() = runTest {
        val env = Env(devices = InMemoryDeviceRepository(listOf(tv)))
        env.connection.launchablePackages = listOf("org.example.zzz", "com.netflix.ninja")
        val session = session(env)
        val apps = session.listApps().getOrThrow()
        assertEquals(listOf("com.netflix.ninja", "org.example.zzz"), apps.map { it.packageName })
        assertEquals("Netflix", apps.first().label)
    }

    @Test fun `groupConsecutiveKeys keeps order`() {
        val k = RemoteCommand.Key(RemoteKey.HOME)
        val t = RemoteCommand.Text("x")
        assertEquals(listOf(listOf(k, k), listOf(t), listOf(k)), RemoteSession.groupConsecutiveKeys(listOf(k, k, t, k)))
    }

    @Test fun `app details are cached and not fetched while disconnected`() = runTest {
        val env = Env(devices = InMemoryDeviceRepository(listOf(tv)))
        env.connection.details = mapOf("com.netflix.ninja" to AppDetails("com.netflix.ninja", "Netflix", null))
        val session = session(env)

        assertEquals(null, session.appDetails("com.netflix.ninja")) // not connected: no reconnect for icons
        assertEquals(0, env.connection.appDetailsCalls)

        session.listApps().getOrThrow() // connects
        assertEquals("Netflix", session.appDetails("com.netflix.ninja")!!.label)
        assertEquals("Netflix", session.appDetails("com.netflix.ninja")!!.label)
        assertEquals("second call is served from the cache", 1, env.connection.appDetailsCalls)
        assertEquals(null, session.appDetails("org.example.missing"))
    }
}
