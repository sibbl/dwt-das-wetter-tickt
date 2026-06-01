package de.sibbl.dwdradar.tile

import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.ColorBuilders.argb
import androidx.wear.tiles.DimensionBuilders.expand
import androidx.wear.tiles.DimensionBuilders.sp
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.LayoutElementBuilders.FONT_WEIGHT_BOLD
import androidx.wear.tiles.LayoutElementBuilders.Text
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import com.google.common.util.concurrent.ListenableFuture
import de.sibbl.dwdradar.MainActivity
import de.sibbl.dwdradar.R
import de.sibbl.dwdradar.data.radar.RadarRepository
import de.sibbl.dwdradar.util.QuarterHourRefreshPolicy
import kotlinx.coroutines.runBlocking

class RadarTileService : TileService() {
    private val radarRepository by lazy { RadarRepository(applicationContext) }
    private val snapshotRenderer by lazy {
        RadarTileSnapshotRenderer(
            context = applicationContext,
            radarRepository = radarRepository
        )
    }

    override fun onTileRequest(requestParams: TileRequest): ListenableFuture<TileBuilders.Tile> {
        return immediateFuture(
            runBlocking {
                val freshnessMillis = QuarterHourRefreshPolicy.nextRefreshDelayMillis(System.currentTimeMillis())
                runCatching {
                    val timeline = radarRepository.loadTimeline()
                    val currentFrame = timeline.frames.getOrNull(timeline.nowFrameIndex)
                        ?: error("No radar frames available for tile.")
                    buildRadarTile(
                        resourcesVersion = RadarTileVersionCodec.encode(currentFrame),
                        freshnessMillis = freshnessMillis
                    )
                }.getOrElse {
                    buildMessageTile(
                        message = getString(R.string.error_loading_radar),
                        freshnessMillis = freshnessMillis
                    )
                }
            }
        )
    }

    @Deprecated("Using legacy tiles resources builder for tiles 1.4.x")
    override fun onResourcesRequest(
        requestParams: ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        return immediateFuture(
            runBlocking {
                val imageBytes = RadarTileVersionCodec.decode(requestParams.version)
                    ?.let { reference ->
                        runCatching {
                            snapshotRenderer.render(reference)
                        }.getOrElse {
                            snapshotRenderer.renderFallback(getString(R.string.error_loading_radar))
                        }
                    }
                    ?: snapshotRenderer.renderFallback(getString(R.string.error_loading_radar))

                ResourceBuilders.Resources.Builder()
                    .setVersion(requestParams.version)
                    .addIdToImageMapping(
                        IMAGE_RESOURCE_ID,
                        ResourceBuilders.ImageResource.Builder()
                            .setInlineResource(
                                ResourceBuilders.InlineImageResource.Builder()
                                    .setData(imageBytes)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            }
        )
    }

    private fun buildRadarTile(
        resourcesVersion: String,
        freshnessMillis: Long
    ): TileBuilders.Tile {
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(resourcesVersion)
            .setFreshnessIntervalMillis(freshnessMillis)
            .setTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(
                    buildTileRoot(
                        content = LayoutElementBuilders.Image.Builder()
                            .setResourceId(IMAGE_RESOURCE_ID)
                            .setWidth(expand())
                            .setHeight(expand())
                            .build()
                    )
                )
            )
            .build()
    }

    private fun buildMessageTile(
        message: String,
        freshnessMillis: Long
    ): TileBuilders.Tile {
        val messageText = Text.Builder()
            .setText(message)
            .setMaxLines(2)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(15f))
                    .setWeight(FONT_WEIGHT_BOLD)
                    .setColor(argb(0xFFFFFFFF.toInt()))
                    .build()
            )
            .build()

        return TileBuilders.Tile.Builder()
            .setFreshnessIntervalMillis(freshnessMillis)
            .setTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(
                    buildTileRoot(content = messageText)
                )
            )
            .build()
    }

    private fun buildTileRoot(
        content: LayoutElementBuilders.LayoutElement
    ): LayoutElementBuilders.Box {
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open-app")
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName(MainActivity::class.java.name)
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF000000.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(content)
            .build()
    }

    private fun <T> immediateFuture(value: T): ListenableFuture<T> {
        return ResolvableFuture.create<T>().apply {
            set(value)
        }
    }

    private companion object {
        const val IMAGE_RESOURCE_ID = "radar_snapshot"
    }
}
