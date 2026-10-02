package net.sibbl.dwt.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import net.sibbl.dwt.R
import net.sibbl.dwt.data.radar.RadarBackend
import net.sibbl.dwt.model.MapCamera
import net.sibbl.dwt.model.RadarFrameReference
import net.sibbl.dwt.model.RadarTimeline
import net.sibbl.dwt.model.ZoomPreset
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], qualifiers = "w192dp-h192dp-round")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class RadarScreenJvmTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun singleTap_togglesPlayback() {
        var toggleCalls = 0

        setRadarContent(
            onTogglePlayback = { toggleCalls++ }
        )

        composeRule.onNodeWithContentDescription(mapDescription())
            .performTouchInput { click() }

        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        assertEquals(1, toggleCalls)
    }

    @Test
    fun doubleTap_cyclesZoom() {
        var zoomCalls = 0
        var toggleCalls = 0

        setRadarContent(
            onTogglePlayback = { toggleCalls++ },
            onCycleZoom = { zoomCalls++ }
        )

        composeRule.onNodeWithContentDescription(mapDescription())
            .performTouchInput { doubleClick() }

        composeRule.waitForIdle()
        assertEquals(1, zoomCalls)
        assertEquals(0, toggleCalls)
    }

    @Test
    fun longPress_resetsToNow() {
        var resetCalls = 0

        setRadarContent(
            onResetToNow = { resetCalls++ }
        )

        composeRule.onNodeWithContentDescription(mapDescription())
            .performTouchInput { longClick() }

        composeRule.waitForIdle()
        assertEquals(1, resetCalls)
    }

    @Test
    fun swipe_invokesPanCallback() {
        val panDeltas = mutableListOf<Pair<Float, Float>>()

        setRadarContent(
            onPanMap = { dx, dy, _ -> panDeltas += dx to dy }
        )

        composeRule.onNodeWithContentDescription(mapDescription())
            .performTouchInput { swipe(center, center + Offset(-80f, 0f), 300L) }

        composeRule.waitForIdle()
        assertTrue(panDeltas.isNotEmpty())
        assertTrue(panDeltas.any { (dx, dy) -> abs(dx) > 0f || abs(dy) > 0f })
    }

    @Test
    fun timeTap_selectsNowWithoutRefreshOrMapActions() {
        var now = 0; var refresh = 0; var pan = 0; var playback = 0; var reset = 0
        setRadarContent(onSelectNow = { now++ }, onRefreshData = { refresh++ },
            onPanMap = { _, _, _ -> pan++ }, onTogglePlayback = { playback++ }, onResetToNow = { reset++ })
        composeRule.onNodeWithContentDescription(timeDescription()).performTouchInput { click() }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        assertEquals(1, now)
        assertEquals(0, refresh + pan + playback + reset)
    }

    @Test
    fun timePull_refreshesOnceWithoutMapGesturesOrTap() {
        var now = 0; var refresh = 0; var pan = 0; var playback = 0; var reset = 0
        setRadarContent(onSelectNow = { now++ }, onRefreshData = { refresh++ },
            onPanMap = { _, _, _ -> pan++ }, onTogglePlayback = { playback++ }, onResetToNow = { reset++ })
        composeRule.onNodeWithContentDescription(timeDescription()).performTouchInput {
            swipe(center, center + Offset(0f, 120f), 500L)
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        assertEquals(1, refresh)
        assertEquals(0, now + pan + playback + reset)
    }

    @Test
    fun timePull_whileRefreshingDoesNotRestartOrSelectNow() {
        var refresh = 0; var now = 0
        setRadarContent(state = sampleState().copy(isRefreshing = true),
            onRefreshData = { refresh++ }, onSelectNow = { now++ })
        repeat(3) {
            composeRule.onNodeWithContentDescription(timeDescription()).performTouchInput {
                swipe(center, center + Offset(0f, 120f), 300L)
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, refresh)
        assertEquals(0, now)
    }

    @Test
    fun timePull_shortSidewaysAndCanceledGesturesDoNothing() {
        var refresh = 0; var now = 0; var pan = 0
        setRadarContent(onRefreshData = { refresh++ }, onSelectNow = { now++ },
            onPanMap = { _, _, _ -> pan++ })
        for (offset in listOf(Offset(0f, 20f), Offset(100f, 0f), Offset(0f, -40f))) {
            composeRule.onNodeWithContentDescription(timeDescription()).performTouchInput {
                swipe(center, center + offset, 300L)
            }
        }
        composeRule.onNodeWithContentDescription(timeDescription()).performTouchInput {
            down(center)
            moveBy(Offset(0f, 120f))
            cancel()
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        assertEquals(0, refresh + now + pan)
    }

    @Test
    fun timePull_canBeRepeatedAfterReturningToRest() {
        var refresh = 0
        setRadarContent(onRefreshData = { refresh++ })
        repeat(2) {
            composeRule.onNodeWithContentDescription(timeDescription()).performTouchInput {
                swipe(center, center + Offset(0f, 120f), 300L)
            }
            composeRule.mainClock.advanceTimeBy(1_000L)
            composeRule.waitForIdle()
        }
        assertEquals(2, refresh)
    }

    @Test
    fun refreshState_isVisibleBeyondTapPulse() {
        setRadarContent(state = sampleState().copy(isRefreshing = true))
        composeRule.mainClock.advanceTimeBy(2_000L)
        composeRule.onNodeWithContentDescription(timeDescription()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                composeRule.activity.getString(R.string.time_refreshing))
        )
        composeRule.runOnIdle {
            val view = composeRule.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val output = java.io.File("build/ui-checks/time-refresh.png")
            output.parentFile.mkdirs()
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun timeDescription() = composeRule.activity.getString(R.string.time_control_description)

    private fun setRadarContent(
        onTogglePlayback: () -> Unit = {},
        onCycleZoom: () -> Unit = {},
        onResetToNow: () -> Unit = {},
        onPanMap: (Float, Float, Rect) -> Unit = { _, _, _ -> },
        onSelectNow: () -> Unit = {},
        onRefreshData: () -> Unit = {},
        state: RadarUiState = sampleState()
    ) {
        composeRule.setContent {
            MaterialTheme {
                RadarScreen(
                    uiState = state,
                    showLocationPrompt = false,
                    onRequestLocationPermission = {},
                    onDismissLocationPrompt = {},
                    onTogglePlayback = onTogglePlayback,
                    onCycleZoom = onCycleZoom,
                    onResetToNow = onResetToNow,
                    onPanMap = onPanMap,
                    onRotary = {},
                    onRadialScroll = {},
                    onZoomSwipe = {},
                    onGestureEnd = {},
                    onRefreshData = onRefreshData,
                    onSelectNow = onSelectNow,
                    onToggleRainLayer = {},
                    onToggleCloudLayer = {},
                    onToggleLightningLayer = {},
                    onLayerMenuExpandedChange = {},
                    onLayerMenuScroll = {},
                    onLayerMenuScrollChange = {},
                    onRetry = {}
                )
            }
        }
    }

    private fun sampleState(): RadarUiState {
        return RadarUiState(
            isLoading = false,
            isPlaying = true,
            timeline = RadarTimeline(
                frames = listOf(
                    RadarFrameReference(
                        timestampMillis = 0L,
                        assetPath = "past.zip",
                        timeStepMillis = 300L,
                        sectionStartMillis = 0L,
                        sectionEndMillis = 600L
                    ),
                    RadarFrameReference(
                        timestampMillis = 300L,
                        assetPath = "now.zip",
                        timeStepMillis = 300L,
                        sectionStartMillis = 0L,
                        sectionEndMillis = 600L
                    )
                ),
                nowTimestampMillis = 300L,
                nowFrameIndex = 1
            ),
            selectedFrameIndex = 1,
            mapCamera = MapCamera(
                center = RadarBackend.defaultBounds.center,
                zoomPreset = ZoomPreset.MID
            )
        )
    }

    private fun mapDescription(): String {
        return composeRule.activity.getString(R.string.map_content_description)
    }
}
