package dev.firetvonwatch.wear.input

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Android 17 (API 37) runtime permission for LAN access. Older releases don't need it. */
object LocalNetworkPermission {
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val FIRST_SDK = 37

    fun isRequired(): Boolean = Build.VERSION.SDK_INT >= FIRST_SDK

    fun isGranted(context: Context): Boolean =
        !isRequired() || ContextCompat.checkSelfPermission(context, NAME) == PackageManager.PERMISSION_GRANTED
}

class PermissionState internal constructor(
    private val grantedState: MutableState<Boolean>,
    private val request: () -> Unit,
) {
    val granted: Boolean get() = grantedState.value
    fun launchRequest() = request()
}

@Composable
fun rememberLocalNetworkPermission(): PermissionState {
    val context = LocalContext.current
    val granted = remember { mutableStateOf(LocalNetworkPermission.isGranted(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted.value = it }
    return remember {
        PermissionState(granted) {
            if (LocalNetworkPermission.isRequired()) launcher.launch(LocalNetworkPermission.NAME) else granted.value = true
        }
    }
}
