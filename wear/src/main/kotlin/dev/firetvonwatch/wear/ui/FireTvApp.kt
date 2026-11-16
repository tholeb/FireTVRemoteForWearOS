package dev.firetvonwatch.wear.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.firetvonwatch.domain.session.SessionEvent
import dev.firetvonwatch.wear.AppDependencies
import dev.firetvonwatch.wear.R
import dev.firetvonwatch.wear.input.rememberLocalNetworkPermission
import dev.firetvonwatch.wear.ui.common.RemoteScreenAwake
import dev.firetvonwatch.wear.ui.remote.KeyboardScreen
import dev.firetvonwatch.wear.ui.remote.RemoteScreen
import dev.firetvonwatch.wear.ui.remote.TouchpadScreen
import dev.firetvonwatch.wear.ui.settings.DevicesScreen
import dev.firetvonwatch.wear.ui.settings.SettingsScreen
import dev.firetvonwatch.wear.ui.setup.DiscoverScreen
import dev.firetvonwatch.wear.ui.setup.OnboardingScreen
import dev.firetvonwatch.wear.ui.setup.PairScreen
import kotlinx.coroutines.flow.first

object Routes {
    const val ONBOARDING = "onboarding"
    const val DISCOVER = "discover"
    const val PAIR = "pair"
    const val REMOTE = "remote"
    const val TOUCHPAD = "touchpad"
    const val KEYBOARD = "keyboard"
    const val SETTINGS = "settings"
    const val DEVICES = "devices"
    val SETUP = setOf(ONBOARDING, DISCOVER, PAIR)
}

@Composable
fun FireTvApp(deps: AppDependencies) {
    val factory = remember(deps) {
        viewModelFactory {
            initializer { RemoteViewModel(deps) }
            initializer { SetupViewModel(deps) }
        }
    }
    val remoteVm: RemoteViewModel = viewModel(factory = factory)
    val setupVm: SetupViewModel = viewModel(factory = factory)
    val ui by remoteVm.uiState.collectAsStateWithLifecycle()
    val permission = rememberLocalNetworkPermission()
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(remoteVm) {
        remoteVm.events.collect { event ->
            val message = when (event) {
                is SessionEvent.CommandFailed -> resources.getString(R.string.command_failed, event.reason)
                SessionEvent.NoDeviceConfigured -> resources.getString(R.string.no_device_configured)
                SessionEvent.TvStillAsleep -> resources.getString(R.string.tv_still_asleep)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    AppScaffold {
        val devices = ui.devices
        if (devices == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@AppScaffold
        }
        val start = rememberSaveable { if (devices.isEmpty() || !permission.granted) Routes.ONBOARDING else Routes.REMOTE }
        val nav = rememberSwipeDismissableNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route

        LaunchedEffect(Unit) {
            if (start == Routes.REMOTE && deps.settingsRepository.settings.first().startInTouchpad) nav.navigate(Routes.TOUCHPAD)
        }
        // Every TV forgotten: go back to setup.
        LaunchedEffect(devices.isEmpty(), route) {
            if (devices.isEmpty() && route != null && route !in Routes.SETUP) nav.resetTo(Routes.ONBOARDING)
        }

        // Remote and touchpad: screen stays on and dims after the watch's screen timeout (setting).
        RemoteScreenAwake(active = route == Routes.REMOTE || route == Routes.TOUCHPAD, settings = ui.settings) {
            SwipeDismissableNavHost(
                navController = nav,
                startDestination = start,
                // The touchpad uses horizontal swipes itself; leave it with the close button or hardware back.
                userSwipeEnabled = route != Routes.TOUCHPAD,
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        permission = permission,
                        hasDevices = devices.isNotEmpty(),
                        onFindTv = { nav.navigate(Routes.DISCOVER) },
                        onOpenRemote = { nav.resetTo(Routes.REMOTE) },
                    )
                }
                composable(Routes.DISCOVER) {
                    DiscoverScreen(setupVm, permission, onSelected = { nav.navigate(Routes.PAIR) })
                }
                composable(Routes.PAIR) {
                    PairScreen(setupVm, remoteVm, ui, onDone = { nav.resetTo(Routes.REMOTE) })
                }
                composable(Routes.REMOTE) {
                    RemoteScreen(
                        vm = remoteVm,
                        ui = ui,
                        onTouchpad = { nav.navigate(Routes.TOUCHPAD) },
                        onKeyboard = { nav.navigate(Routes.KEYBOARD) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                        onDevices = { nav.navigate(Routes.DEVICES) },
                    )
                }
                composable(Routes.TOUCHPAD) { TouchpadScreen(remoteVm, ui, onClose = { nav.popBackStack() }) }
                composable(Routes.KEYBOARD) { KeyboardScreen(remoteVm, ui) }
                composable(Routes.SETTINGS) { SettingsScreen(remoteVm, ui, onDevices = { nav.navigate(Routes.DEVICES) }) }
                composable(Routes.DEVICES) {
                    DevicesScreen(remoteVm, ui, onAdd = { nav.navigate(Routes.DISCOVER) }, onSelected = { nav.popBackStack() })
                }
            }
        }
    }
}

/** Clears the back stack and opens [route] as the new root. */
private fun NavHostController.resetTo(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
