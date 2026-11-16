package dev.firetvonwatch.wear.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.firetvonwatch.domain.model.ConnectionError
import dev.firetvonwatch.domain.model.ConnectionState
import dev.firetvonwatch.wear.R
import dev.firetvonwatch.wear.ui.theme.StatusBusy
import dev.firetvonwatch.wear.ui.theme.StatusConnected
import dev.firetvonwatch.wear.ui.theme.StatusError
import dev.firetvonwatch.wear.ui.theme.StatusIdle

/** Round icon button for remote keys. */
@Composable
fun KeyButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    primary: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    FilledIconButton(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(size),
        colors = if (primary) IconButtonDefaults.filledIconButtonColors() else IconButtonDefaults.filledTonalIconButtonColors(),
    ) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(size * 0.5f))
    }
}

fun ConnectionState.ringColor(): Color = when (this) {
    is ConnectionState.Connected -> StatusConnected
    is ConnectionState.Connecting, is ConnectionState.AwaitingAuthorization -> StatusBusy
    is ConnectionState.Failed -> StatusError
    ConnectionState.Disconnected -> StatusIdle
}

@Composable
fun ConnectionState.label(): String = when (this) {
    is ConnectionState.Connected -> stringResource(R.string.state_connected) + " · " + device.name
    is ConnectionState.Connecting -> stringResource(R.string.state_connecting)
    is ConnectionState.AwaitingAuthorization -> stringResource(R.string.state_awaiting_auth_title)
    is ConnectionState.Failed -> errorText(error)
    ConnectionState.Disconnected -> stringResource(R.string.state_disconnected)
}

/** Thin ring at the screen edge: green = connected, amber spinner = busy, red = error, grey = idle. */
@Composable
fun ConnectionRing(state: ConnectionState, modifier: Modifier = Modifier) {
    val color = state.ringColor()
    val busy = state is ConnectionState.Connecting || state is ConnectionState.AwaitingAuthorization
    val description = state.label()
    // The full-screen ring must not carry semantics: a node covering the whole screen on top
    // of the buttons hides them from TalkBack. The state is announced by a tiny live region.
    Box(
        Modifier.size(1.dp).semantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
    )
    if (busy) {
        BusyRing(color, modifier)
    } else {
        // Static: no animation, so no frame is drawn while nothing changes.
        Canvas(modifier.fillMaxSize()) {
            val (topLeft, arcSize, stroke) = ringGeometry()
            val alpha = if (state is ConnectionState.Disconnected) 0.5f else 0.9f
            drawArc(color.copy(alpha = alpha), 0f, 360f, false, topLeft, arcSize, style = stroke)
        }
    }
}

/**
 * Spinning arc. Kept in its own composable so the infinite animation (which asks for a frame
 * on every vsync) only exists while connecting, not for as long as the remote is showing.
 */
@Composable
private fun BusyRing(color: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "ring")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    Canvas(modifier.fillMaxSize()) {
        val (topLeft, arcSize, stroke) = ringGeometry()
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, topLeft, arcSize, style = stroke)
        drawArc(color, rotation - 90f, 90f, false, topLeft, arcSize, style = stroke)
    }
}

private fun DrawScope.ringGeometry(): Triple<Offset, Size, Stroke> {
    val strokePx = 3.dp.toPx()
    val inset = strokePx / 2 + 1.dp.toPx()
    return Triple(Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), Stroke(strokePx))
}

@Composable
fun errorText(error: ConnectionError): String = stringResource(
    when (error) {
        ConnectionError.NO_WIFI -> R.string.error_no_wifi
        ConnectionError.UNREACHABLE -> R.string.error_unreachable
        ConnectionError.REFUSED -> R.string.error_refused
        ConnectionError.AUTH_TIMEOUT -> R.string.error_auth_timeout
        ConnectionError.AUTH_REJECTED -> R.string.error_auth_rejected
        ConnectionError.IO -> R.string.error_io
    },
)

/** Centered message with optional actions, used for errors and the authorization prompt. */
@Composable
fun StatusPanel(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        actions()
    }
}
