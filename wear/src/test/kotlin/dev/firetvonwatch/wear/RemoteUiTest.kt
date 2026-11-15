package dev.firetvonwatch.wear

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import dev.firetvonwatch.domain.model.RemoteSettings
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import dev.firetvonwatch.domain.DeviceDiscovery
import dev.firetvonwatch.domain.model.ConnectionError
import dev.firetvonwatch.domain.model.FireTvDevice
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.repository.DeviceRepository
import dev.firetvonwatch.domain.repository.SettingsRepository
import dev.firetvonwatch.domain.session.RemoteSession
import dev.firetvonwatch.testing.FakeFireTvConnection
import dev.firetvonwatch.testing.FakeNetworkGate
import dev.firetvonwatch.testing.InMemoryDeviceRepository
import dev.firetvonwatch.testing.InMemorySettingsRepository
import dev.firetvonwatch.wear.ui.FireTvApp
import dev.firetvonwatch.wear.ui.RemoteViewModel
import dev.firetvonwatch.wear.ui.remote.TouchpadScreen
import dev.firetvonwatch.wear.ui.theme.FireTvTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** UI tests against the fake Fire TV: real screens, ViewModels and session; fake transport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav", application = android.app.Application::class)
class RemoteUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tv = FireTvDevice("tv", "Living room", "192.168.1.42", authorized = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connection = FakeFireTvConnection()
    private val settingsRepo = InMemorySettingsRepository()
    private val deps = object : AppDependencies {
        override val deviceRepository: DeviceRepository = InMemoryDeviceRepository(listOf(tv))
        override val settingsRepository: SettingsRepository = settingsRepo
        override val session = RemoteSession(connection, FakeNetworkGate(), deviceRepository, settingsRepository, scope)
        override val discovery = DeviceDiscovery { emptyFlow() }
        override fun keyFingerprint() = "AA:BB"
        override fun resetKey() = Unit
    }
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After fun tearDown() = scope.cancel()

    /**
     * The session runs on the main looper, which Robolectric keeps paused; compose's waitUntil
     * only drives compose work, so we idle the looper as well.
     */
    private fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not met within $timeoutMs ms" }
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
        }
    }

    private fun waitForKeys(vararg keys: RemoteKey) {
        eventually { connection.sentKeys.size >= keys.size }
        assertEquals(keys.toList(), connection.sentKeys)
    }

    @Test fun okButtonSendsSelect() {
        compose.setContent { FireTvTheme { FireTvApp(deps) } }
        compose.onNodeWithTag("ok").performClick()
        waitForKeys(RemoteKey.SELECT)
    }

    @Test fun dpadAndCornerKeysUseAccessibleLabels() {
        compose.setContent { FireTvTheme { FireTvApp(deps) } }
        compose.onNodeWithContentDescription(context.getString(R.string.key_up)).performClick()
        eventually { connection.sentKeys.isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.key_home)).performClick()
        waitForKeys(RemoteKey.DPAD_UP, RemoteKey.HOME)
    }

    @Test fun mediaPageIsOneSwipeAway() {
        compose.setContent { FireTvTheme { FireTvApp(deps) } }
        // Start inside the screen: Wear reserves the very edges for system gestures.
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft(startX = centerX + width / 3f, endX = centerX - width / 3f) }
        eventually { true }
        compose.onNodeWithContentDescription(context.getString(R.string.key_play_pause)).performClick()
        waitForKeys(RemoteKey.PLAY_PAUSE)
    }

    @Test fun refusedConnectionShowsGuidanceAndRetry() {
        connection.connectError = ConnectionError.REFUSED
        compose.setContent { FireTvTheme { FireTvApp(deps) } }
        compose.onNodeWithTag("ok").performClick()
        eventually { connection.connectAttempts.isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.error_refused)).assertIsDisplayed()
        connection.connectError = null
        compose.onNodeWithTag("retry").performClick()
        eventually { connection.connectAttempts.size >= 2 }
        compose.onNodeWithTag("ok").assertIsDisplayed()
    }

    @Test fun touchpadSwipesAndTaps() {
        val vm = RemoteViewModel(deps)
        compose.setContent {
            FireTvTheme {
                val ui by vm.uiState.collectAsState()
                TouchpadScreen(vm, ui, onClose = {})
            }
        }
        compose.onNodeWithTag("touchpad").performTouchInput { swipeRight() }
        eventually { connection.sentKeys.isNotEmpty() }
        compose.onNodeWithTag("touchpad").performTouchInput { swipeUp() }
        eventually { connection.sentKeys.size >= 2 }
        compose.onNodeWithTag("touchpad").performClick()
        waitForKeys(RemoteKey.DPAD_RIGHT, RemoteKey.DPAD_UP, RemoteKey.SELECT)
    }

    @Test
    @Config(qualifiers = "fr-w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
    fun frenchLocaleShowsFrenchErrorAndLabels() {
        connection.connectError = ConnectionError.REFUSED
        compose.setContent { FireTvTheme { FireTvApp(deps) } }
        compose.onNodeWithContentDescription("Haut").assertIsDisplayed()
        compose.onNodeWithTag("ok").performClick()
        eventually { connection.connectAttempts.isNotEmpty() }
        compose.onNodeWithText("Connexion refusée. Activez le débogage ADB sur la TV.").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").assertIsDisplayed()
    }

    /**
     * Regression: the full-screen connection ring used to carry the state description, which
     * put a screen-sized accessibility node on top of every button and hid them from TalkBack.
     */
    @Test fun connectionStateNodeDoesNotCoverTheButtons() {
        compose.setContent { FireTvTheme { FireTvApp(deps) } }
        val state = compose.onNodeWithContentDescription(context.getString(R.string.state_disconnected)).fetchSemanticsNode()
        assertTrue("state node is ${state.size}", state.size.width < 10 && state.size.height < 10)
        compose.onNodeWithContentDescription(context.getString(R.string.key_up)).assertIsDisplayed()
    }

    private fun swipeToNextPage() {
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft(startX = centerX + width / 3f, endX = centerX - width / 3f) }
        eventually { true }
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun backGoesToPreviousPageAndNeverClosesTheRemote() {
        compose.setContent { FireTvTheme { FireTvApp(deps) } }
        swipeToNextPage()
        compose.onNodeWithContentDescription(context.getString(R.string.key_play_pause)).assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("ok").assertIsDisplayed()
        pressBack() // first page: ignored
        compose.onNodeWithTag("ok").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun backClosesTheAppFromTheFirstPageWhenAllowed() {
        settingsRepo.state.value = RemoteSettings(swipeBackCloses = true)
        compose.setContent { FireTvTheme { FireTvApp(deps) } }
        compose.onNodeWithTag("ok").assertIsDisplayed()
        pressBack()
        assertTrue(compose.activity.isFinishing)
    }

    @Test fun volumeKeysCanBeTurnedOff() {
        settingsRepo.state.value = RemoteSettings(volumeControlEnabled = false)
        compose.setContent { FireTvTheme { FireTvApp(deps) } }
        swipeToNextPage()
        compose.onAllNodesWithContentDescription(context.getString(R.string.key_volume_up)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(context.getString(R.string.key_mute)).assertCountEquals(0)
        compose.onNodeWithContentDescription(context.getString(R.string.key_play_pause)).performClick()
        waitForKeys(RemoteKey.PLAY_PAUSE)
    }

    @Test fun backOnTheTouchpadReturnsToTheRemote() {
        compose.setContent { FireTvTheme { FireTvApp(deps) } }
        compose.onNodeWithContentDescription(context.getString(R.string.open_touchpad)).performClick()
        compose.onNodeWithTag("touchpad").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("ok").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun settingsOpenAndBackReturnsToTheMediaPage() {
        compose.setContent { FireTvTheme { FireTvApp(deps) } }
        swipeToNextPage()
        compose.onNodeWithContentDescription(context.getString(R.string.open_settings)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_volume_control)).assertIsDisplayed()
        pressBack()
        compose.onNodeWithContentDescription(context.getString(R.string.key_play_pause)).assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }
}
