package dev.firetvonwatch.wear.tile

import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.wear.MainActivity
import dev.firetvonwatch.wear.R
import dev.firetvonwatch.wear.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * One-tap tile: Back, Play/Pause, Home are sent straight from the tile (LoadAction → this
 * service), and a fourth button opens the full remote. The session connects on demand and
 * disconnects after the idle timeout.
 */
class RemoteTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Main.immediate) {
            val container = applicationContext.appContainer
            TileAction.keyFor(requestParams.currentState.lastClickableId)?.let(container.session::press)
            val deviceName = container.deviceRepository.activeDevice.first()?.name ?: getString(R.string.app_name)
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(deviceName)))
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture {
            val builder = ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION)
            TileAction.entries.forEach { action ->
                builder.addIdToImageMapping(
                    action.id,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(action.icon).build(),
                        )
                        .build(),
                )
            }
            builder.build()
        }

    private fun layout(deviceName: String): LayoutElementBuilders.LayoutElement {
        val row = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(button(TileAction.BACK, 44f))
            .addContent(spacer(8f))
            .addContent(button(TileAction.PLAY_PAUSE, 60f))
            .addContent(spacer(8f))
            .addContent(button(TileAction.HOME, 44f))
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(deviceName)
                            .setMaxLines(1)
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder().setSize(sp(14f)).setColor(argb(0xFFFFFFFF.toInt())).build(),
                            )
                            .build(),
                    )
                    .addContent(spacer(10f, vertical = true))
                    .addContent(row)
                    .addContent(spacer(10f, vertical = true))
                    .addContent(button(TileAction.OPEN, 40f))
                    .build(),
            )
            .build()
    }

    private fun button(action: TileAction, size: Float): LayoutElementBuilders.LayoutElement {
        val onClick: ActionBuilders.Action = if (action == TileAction.OPEN) {
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(packageName)
                        .setClassName(MainActivity::class.java.name)
                        .build(),
                )
                .build()
        } else {
            ActionBuilders.LoadAction.Builder().build()
        }
        val primary = action == TileAction.PLAY_PAUSE
        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(size))
            .setHeight(dp(size))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(if (primary) 0xFFFF9900.toInt() else 0xFF303030.toInt()))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(size / 2)).build())
                            .build(),
                    )
                    .setClickable(ModifiersBuilders.Clickable.Builder().setId(action.id).setOnClick(onClick).build())
                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(getString(action.label)).build())
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(action.id)
                    .setWidth(dp(size * 0.5f))
                    .setHeight(dp(size * 0.5f))
                    .setColorFilter(
                        LayoutElementBuilders.ColorFilter.Builder()
                            .setTint(argb(if (primary) 0xFF1F1200.toInt() else 0xFFFFFFFF.toInt()))
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun spacer(size: Float, vertical: Boolean = false): LayoutElementBuilders.LayoutElement {
        val builder = LayoutElementBuilders.Spacer.Builder()
        return if (vertical) builder.setHeight(dp(size)).build() else builder.setWidth(dp(size)).build()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}

/** Buttons on the tile. [id] is both the clickable id and the image resource id. */
enum class TileAction(val id: String, val icon: Int, val label: Int, val key: RemoteKey?) {
    BACK("back", R.drawable.ic_back, R.string.key_back, RemoteKey.BACK),
    PLAY_PAUSE("play_pause", R.drawable.ic_play_pause, R.string.key_play_pause, RemoteKey.PLAY_PAUSE),
    HOME("home", R.drawable.ic_home, R.string.key_home, RemoteKey.HOME),
    OPEN("open", R.drawable.ic_tv, R.string.open_remote, null);

    companion object {
        fun keyFor(clickableId: String?): RemoteKey? = entries.firstOrNull { it.id == clickableId }?.key
    }
}
