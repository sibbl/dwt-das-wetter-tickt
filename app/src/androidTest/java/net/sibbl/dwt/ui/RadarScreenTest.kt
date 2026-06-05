package net.sibbl.dwt.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
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

class RadarScreenTest {
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
            .performTouchInput { swipeLeft() }

        composeRule.waitForIdle()
        assertTrue(panDeltas.isNotEmpty())
        assertTrue(panDeltas.any { (dx, dy) -> abs(dx) > 0f || abs(dy) > 0f })
    }

    private fun setRadarContent(
        onTogglePlayback: () -> Unit = {},
        onCycleZoom: () -> Unit = {},
        onResetToNow: () -> Unit = {},
        onPanMap: (Float, Float, Rect) -> Unit = { _, _, _ -> }
    ) {
        composeRule.setContent {
            MaterialTheme {
                RadarScreen(
                    uiState = sampleState(),
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
                    onRefreshData = {},
                    onToggleRainLayer = {},
                    onToggleCloudLayer = {},
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
