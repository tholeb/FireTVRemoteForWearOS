package dev.firetvonwatch.wear.ui.remote

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.firetvonwatch.domain.model.AppDetails
import dev.firetvonwatch.domain.model.KnownApps
import dev.firetvonwatch.wear.R
import dev.firetvonwatch.wear.input.Haptics
import dev.firetvonwatch.wear.ui.AppsState
import dev.firetvonwatch.wear.ui.RemoteUiState
import dev.firetvonwatch.wear.ui.RemoteViewModel

@Composable
fun AppsPage(vm: RemoteViewModel, ui: RemoteUiState, isCurrent: Boolean, haptics: Haptics) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val details by vm.appDetails.collectAsStateWithLifecycle()
    val favorites = ui.settings.favoriteApps
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(isCurrent) {
        if (isCurrent && apps is AppsState.Idle) vm.loadApps()
    }
    LaunchedEffect(favorites, isCurrent) {
        if (isCurrent) vm.loadDetails(favorites)
    }

    val launch: (String) -> Unit = {
        haptics.confirm()
        vm.launchApp(it)
    }
    val toggle: (String) -> Unit = {
        haptics.tap()
        vm.toggleFavorite(it)
    }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.apps_title)) } }
            if (favorites.isNotEmpty()) {
                item { ListHeader { Text(stringResource(R.string.apps_favorites), style = MaterialTheme.typography.labelSmall) } }
                items(favorites) { pkg ->
                    AppButton(pkg, details[pkg], favorite = true, onClick = launch, onLongClick = toggle)
                }
                item { ListHeader { Text(stringResource(R.string.apps_all), style = MaterialTheme.typography.labelSmall) } }
            }
            when (val state = apps) {
                AppsState.Idle -> item {
                    Button(onClick = vm::loadApps, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.apps_load)) }
                }
                AppsState.Loading -> item { CircularProgressIndicator(Modifier.size(32.dp)) }
                is AppsState.Error -> {
                    item {
                        Text(
                            stringResource(R.string.apps_error, state.message),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                    item { Button(onClick = vm::loadApps) { Text(stringResource(R.string.retry)) } }
                }
                is AppsState.Loaded -> {
                    val others = state.apps.filterNot { it.packageName in favorites }
                    items(others) { app ->
                        AppButton(app.packageName, details[app.packageName], favorite = false, onClick = launch, onLongClick = toggle)
                    }
                    if (others.isEmpty() && favorites.isEmpty()) {
                        item { Text(stringResource(R.string.apps_none), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                    }
                    item {
                        Text(
                            stringResource(R.string.apps_long_press_hint),
                            style = MaterialTheme.typography.bodyExtraSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                }
            }
            // Room below the last item so it can scroll clear of the round screen's bottom edge.
            item { Spacer(Modifier.height(BottomSpace)) }
        }
    }
}

/** Extra space after the last item of a scrolling list (round screens cut off the bottom). */
internal val BottomSpace = 56.dp

@Composable
private fun AppButton(
    packageName: String,
    details: AppDetails?,
    favorite: Boolean,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
) {
    val label = details?.label ?: KnownApps.labelFor(packageName)
    Button(
        onClick = { onClick(packageName) },
        onLongClick = { onLongClick(packageName) },
        onLongClickLabel = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add),
        modifier = Modifier.fillMaxWidth(),
        colors = if (favorite) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.filledVariantButtonColors(),
        icon = { AppIcon(label, details?.icon) },
        label = { Text(label, maxLines = 2) },
    )
}

/** The app's icon from the TV, or a round placeholder with its first letter. */
@Composable
private fun AppIcon(label: String, png: ByteArray?) {
    val bitmap = remember(png) { png?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(32.dp))
    } else {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(label.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
