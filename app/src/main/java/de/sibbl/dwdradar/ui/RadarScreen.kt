package de.sibbl.dwdradar.ui

import android.content.Context
import android.view.ViewConfiguration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import de.sibbl.dwdradar.R
import de.sibbl.dwdradar.data.radar.RadarBackend
import de.sibbl.dwdradar.map.CityCatalog
import de.sibbl.dwdradar.map.MapViewport
import de.sibbl.dwdradar.model.GeoPoint
import de.sibbl.dwdradar.model.RadarFrameReference
import de.sibbl.dwdradar.model.ZoomPreset
import de.sibbl.dwdradar.util.TimeFormatters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun RadarScreen(
    uiState: RadarUiState,
    showLocationPrompt: Boolean,
    onRequestLocationPermission: () -> Unit,
    onDismissLocationPrompt: () -> Unit,
    onTogglePlayback: () -> Unit,
    onCycleZoom: () -> Unit,
    onResetToNow: () -> Unit,
    onPanMap: (Float, Float, Rect) -> Unit,
    onRotary: (Float) -> Unit,
    onRadialScroll: (Float) -> Unit,
    onZoomSwipe: (Float) -> Unit,
    onGestureEnd: () -> Unit,
    onRefreshData: () -> Unit,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val windowInfo = LocalWindowInfo.current
    val textMeasurer = rememberTextMeasurer()
    val selectedReference = uiState.timeline.frames.getOrNull(uiState.selectedFrameIndex)
    val selectedFrameReady = uiState.selectedFrame?.reference == selectedReference
    val selectedBitmap = remember(uiState.selectedFrame?.bitmap, selectedFrameReady) {
        uiState.selectedFrame?.takeIf { selectedFrameReady }?.bitmap?.asImageBitmap()
    }
    val cityLabelStyle = MaterialTheme.typography.labelSmall.copy(
        color = Color(0xFFF7FBFF),
        fontWeight = FontWeight.Medium,
        fontSize = 8.sp
    )
    val relativeOffsetSteps = uiState.selectedFrameIndex - uiState.timeline.nowFrameIndex
    val selectedTimestamp = selectedReference?.timestampMillis ?: uiState.timeline.nowTimestampMillis
    val selectedLoadProgress = selectedReference
        ?.let { uiState.frameLoadProgress[it.timestampMillis] }
        ?.coerceIn(0f, 1f)
        ?: 0f
    val selectedDisplayProgress = if (!selectedFrameReady && selectedLoadProgress >= 1f) {
        FRAME_DECODE_PROGRESS_CAP
    } else {
        selectedLoadProgress
    }
    val showSelectedLoadProgress = selectedReference != null && !selectedFrameReady
    val compactOffsetText = TimeFormatters.compactOffset(
        selectedMillis = selectedTimestamp,
        nowMillis = uiState.timeline.nowTimestampMillis
    )
    val zoomLabelText = zoomLabel(context, uiState.zoomPreset)
    val timelineAccent = when {
        relativeOffsetSteps < 0 -> Color(0xFFD7B3FF)
        relativeOffsetSteps > 0 -> Color(0xFF8DEEFF)
        else -> Color(0xFFEAF6FF)
    }
    val timelineLayerAccent = selectedTimelineLayerColor(
        selectedFrameIndex = uiState.selectedFrameIndex,
        nowFrameIndex = uiState.timeline.nowFrameIndex,
        frames = uiState.timeline.frames
    )
    val showInitialRingLoading = uiState.isLoading && uiState.selectedFrame == null
    val loadingRingTransition = rememberInfiniteTransition()
    val loadingRingProgress by loadingRingTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = LOADING_RING_DURATION_MILLIS,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        )
    )
    var timeRefreshPulseVisible by remember { mutableStateOf(false) }
    var timeRefreshPulseKey by remember { mutableStateOf(0) }
    val timeRefreshPulse by animateFloatAsState(
        targetValue = if (timeRefreshPulseVisible || uiState.isLoading) 1f else 0f
    )
    var showZoomLabel by remember { mutableStateOf(false) }
    var previousZoomPreset by remember { mutableStateOf<ZoomPreset?>(null) }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }
            .collect { isWindowFocused ->
                if (isWindowFocused) {
                    runCatching { focusRequester.requestFocus() }
                }
            }
    }

    LaunchedEffect(uiState.zoomPreset) {
        val shouldFlashZoomLabel = previousZoomPreset != null && previousZoomPreset != uiState.zoomPreset
        previousZoomPreset = uiState.zoomPreset
        if (!shouldFlashZoomLabel) {
            return@LaunchedEffect
        }
        showZoomLabel = true
        delay(1_000L)
        showZoomLabel = false
    }

    LaunchedEffect(timeRefreshPulseKey) {
        if (timeRefreshPulseKey == 0) {
            return@LaunchedEffect
        }
        timeRefreshPulseVisible = true
        delay(TIME_REFRESH_PULSE_MILLIS)
        timeRefreshPulseVisible = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val fullWidthPx = with(density) { maxWidth.toPx() }
            val fullHeightPx = with(density) { maxHeight.toPx() }
            val screenDiameterPx = minOf(fullWidthPx, fullHeightPx)
            val screenRadiusPx = screenDiameterPx / 2f
            val ringStrokeWidthPx = with(density) { 3.dp.toPx() }
            val mapGapWidthPx = with(density) { 2.dp.toPx() }
            val mapBorderWidthPx = with(density) { 2.dp.toPx() }
            val mapRadiusPx = screenRadiusPx - ringStrokeWidthPx - mapGapWidthPx - mapBorderWidthPx
            val mapRect = Rect(
                left = (fullWidthPx / 2f) - mapRadiusPx,
                top = (fullHeightPx / 2f) - mapRadiusPx,
                right = (fullWidthPx / 2f) + mapRadiusPx,
                bottom = (fullHeightPx / 2f) + mapRadiusPx
            )
            val rotaryScrollFactor = ViewConfiguration.get(context).scaledVerticalScrollFactor
                .takeIf { it != 0f } ?: 1f
            val borderTouchWidthPx = with(density) { BORDER_SCROLL_TOUCH_WIDTH_DP.dp.toPx() }
            val zoomSwipeTouchSlopPx = with(density) { ZOOM_SWIPE_TOUCH_SLOP_DP.dp.toPx() }
            val panTouchSlopPx = with(density) { PAN_TOUCH_SLOP_DP.dp.toPx() }
            val doubleTapSamePointPx = with(density) { DOUBLE_TAP_DRAG_SAME_POINT_DP.dp.toPx() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onRotaryScrollEvent {
                        onRotary(it.verticalScrollPixels / rotaryScrollFactor)
                        true
                    }
                    .pointerInput(
                        fullWidthPx,
                        fullHeightPx,
                        screenRadiusPx,
                        mapRadiusPx,
                        borderTouchWidthPx,
                        zoomSwipeTouchSlopPx,
                        panTouchSlopPx,
                        doubleTapSamePointPx,
                        mapRect
                    ) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val center = Offset(fullWidthPx / 2f, fullHeightPx / 2f)
                            val downDistance = distanceBetween(down.position, center)
                            val isBorderGesture = downDistance >= screenRadiusPx - borderTouchWidthPx

                            if (isBorderGesture) {
                                handleBorderScrollGesture(
                                    down = down,
                                    center = center,
                                    onRadialScroll = onRadialScroll,
                                    onGestureEnd = onGestureEnd
                                )
                                return@awaitEachGesture
                            }

                            when (
                                val firstTouch = awaitFirstTouchResult(
                                    down = down,
                                    mapRect = mapRect,
                                    panTouchSlopPx = panTouchSlopPx,
                                    onPanMap = onPanMap,
                                    onLongPress = onResetToNow
                                )
                            ) {
                                FirstTouchResult.Canceled,
                                FirstTouchResult.Handled -> return@awaitEachGesture
                                is FirstTouchResult.Tap -> handleTapOrZoomGesture(
                                    firstUpPosition = firstTouch.upPosition,
                                    doubleTapSamePointPx = doubleTapSamePointPx,
                                    zoomSwipeTouchSlopPx = zoomSwipeTouchSlopPx,
                                    onSingleTap = onTogglePlayback,
                                    onDoubleTap = onCycleZoom,
                                    onZoomSwipe = onZoomSwipe,
                                    onGestureEnd = onGestureEnd
                                )
                            }
                        }
                    }
                    .semantics {
                        contentDescription = context.getString(R.string.map_content_description)
                        stateDescription = if (uiState.isPlaying) {
                            context.getString(R.string.playback_running)
                        } else {
                            context.getString(R.string.playback_paused)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val viewport = MapViewport(
                        mapRect = mapRect,
                        camera = uiState.mapCamera
                    )
                    val mapCenter = mapRect.center
                    val mapRadius = mapRect.width / 2f
                    val ringRadius = (screenDiameterPx / 2f) - (ringStrokeWidthPx / 2f)
                    val clipPath = Path().apply { addOval(mapRect) }

                    if (showInitialRingLoading) {
                        drawLoadingTimelineRing(
                            center = mapCenter,
                            radius = ringRadius,
                            strokeWidth = ringStrokeWidthPx,
                            progress = loadingRingProgress
                        )
                    } else {
                        drawSegmentedTimelineRing(
                            center = mapCenter,
                            radius = ringRadius,
                            strokeWidth = ringStrokeWidthPx,
                            selectedFrameIndex = uiState.selectedFrameIndex,
                            nowFrameIndex = uiState.timeline.nowFrameIndex,
                            frames = uiState.timeline.frames,
                            frameLoadProgress = uiState.frameLoadProgress
                        )
                    }

                    clipPath(clipPath) {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFF162334), Color(0xFF081018)),
                                center = mapRect.center,
                                radius = mapRect.width * 0.78f
                            ),
                            topLeft = Offset(mapRect.left, mapRect.top),
                            size = Size(mapRect.width, mapRect.height)
                        )
                        drawCircle(
                            color = Color(0x18000000),
                            radius = mapRadius,
                            center = mapCenter,
                            style = Stroke(width = 10.dp.toPx())
                        )
                        drawGraticule(
                            viewport = viewport,
                            color = Color(0xFF223245)
                        )
                        drawGermanyOutlines(
                            viewport = viewport,
                            outlines = uiState.germanyOutlines,
                            fill = Color(0xFF111A25),
                            stroke = Color(0xFF586B7F)
                        )

                        if (selectedBitmap != null && uiState.selectedFrame != null && selectedFrameReady) {
                            val radarRect = viewport.radarRect(uiState.selectedFrame.bounds)
                            drawImage(
                                image = selectedBitmap,
                                dstOffset = androidx.compose.ui.unit.IntOffset(
                                    radarRect.left.toInt(),
                                    radarRect.top.toInt()
                                ),
                                dstSize = androidx.compose.ui.unit.IntSize(
                                    radarRect.width.toInt(),
                                    radarRect.height.toInt()
                                ),
                                alpha = 1f
                            )
                        }

                        drawCityLabels(
                            viewport = viewport,
                            zoomPreset = uiState.zoomPreset,
                            textMeasurer = textMeasurer,
                            labelStyle = cityLabelStyle
                        )

                        uiState.userLocation
                            ?.takeIf(RadarBackend.defaultBounds::contains)
                            ?.let { location ->
                                val locationPoint = viewport.toScreen(location)
                                drawCircle(
                                    color = Color.Black.copy(alpha = 0.45f),
                                    radius = 7.dp.toPx(),
                                    center = locationPoint
                                )
                                drawCircle(
                                    color = Color(0xFF39C6FF),
                                    radius = 5.2.dp.toPx(),
                                    center = locationPoint
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 1.8.dp.toPx(),
                                    center = locationPoint
                                )
                            }
                    }

                    drawCircle(
                        color = Color.Black,
                        radius = mapRadius + (mapBorderWidthPx / 2f),
                        center = mapCenter,
                        style = Stroke(width = mapBorderWidthPx)
                    )
                }
            }

            RadarGlassPill(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .clickable {
                        timeRefreshPulseKey += 1
                        onRefreshData()
                    },
                borderColor = blendColor(
                    start = timelineAccent.copy(alpha = 0.14f),
                    end = timelineLayerAccent.copy(alpha = 0.62f),
                    fraction = timeRefreshPulse
                ),
                bottomProgress = if (showSelectedLoadProgress) selectedDisplayProgress else null,
                bottomProgressColor = timelineLayerAccent
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = TimeFormatters.absoluteTime(selectedTimestamp),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp
                        ),
                        color = Color.White
                    )
                    Text(
                        text = compactOffsetText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.5.sp),
                        color = if (relativeOffsetSteps == 0) Color(0xFF93A5BA) else timelineAccent
                    )
                }
            }

            if (showZoomLabel) {
                RadarGlassPill(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    borderColor = Color(0x3319D9A3)
                ) {
                    Text(
                        text = zoomLabelText,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                        color = Color(0xFF19D9A3)
                    )
                }
            }

            if (uiState.errorMessage != null && uiState.selectedFrame == null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RadarGlassPill(borderColor = Color(0x55FF6B6B)) {
                        Text(
                            text = context.getString(R.string.error_loading_radar),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                    RadarRetryButton(
                        text = context.getString(R.string.retry),
                        onClick = onRetry
                    )
                    Text(
                        text = compactErrorDetails(uiState.errorMessage),
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = Color(0xFF9CA9B8),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 6.5.sp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (showLocationPrompt) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000))
                        .zIndex(2f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RadarGlassPill(borderColor = Color(0x335B7288)) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = context.getString(R.string.permission_location_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = context.getString(R.string.permission_location_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )
                            }
                        }
                        Button(onClick = onRequestLocationPermission) {
                            Text(text = context.getString(R.string.permission_location_action))
                        }
                        Button(onClick = onDismissLocationPrompt) {
                            Text(text = context.getString(R.string.permission_location_skip))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarGlassPill(
    modifier: Modifier = Modifier,
    borderColor: Color,
    bottomProgress: Float? = null,
    bottomProgressColor: Color = Color.White,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(
                color = Color(0xD90D141D),
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .drawWithContent {
                drawContent()
                if (bottomProgress != null) {
                    val cornerRadius = 12.dp.toPx()
                    val barHeight = 2f
                    val y = size.height - barHeight
                    val pillClip = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = 0f,
                                top = 0f,
                                right = size.width,
                                bottom = size.height,
                                radiusX = cornerRadius,
                                radiusY = cornerRadius
                            )
                        )
                    }
                    clipPath(pillClip) {
                        drawRect(
                            color = Color(0x668E98A3),
                            topLeft = Offset(0f, y),
                            size = Size(size.width, barHeight)
                        )
                        drawRect(
                            color = bottomProgressColor.copy(alpha = 0.96f),
                            topLeft = Offset(0f, y),
                            size = Size(size.width * bottomProgress.coerceIn(0f, 1f), barHeight)
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun RadarRetryButton(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                color = Color(0xFF172536),
                shape = RoundedCornerShape(50)
            )
            .border(
                width = 1.dp,
                color = Color(0xFF6FB7FF).copy(alpha = 0.45f),
                shape = RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color(0xFFEAF6FF),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSegmentedTimelineRing(
    center: Offset,
    radius: Float,
    strokeWidth: Float,
    selectedFrameIndex: Int,
    nowFrameIndex: Int,
    frames: List<RadarFrameReference>,
    frameLoadProgress: Map<Long, Float>
) {
    val gapAngle = TIMELINE_SEGMENT_GAP_ANGLE
    val ringBounds = Size(radius * 2f, radius * 2f)
    val topLeft = Offset(center.x - radius, center.y - radius)
    val selectedFrame = frames.getOrNull(selectedFrameIndex)
    val selectedDirection = selectedFrameIndex.compareTo(nowFrameIndex)
    val directionForColor = if (selectedDirection < 0) -1 else 1
    val segmentCount = selectedFrame?.visualSegmentCount() ?: STEPS_PER_REVOLUTION
    val relativeSteps = selectedFrameIndex - nowFrameIndex
    val layerDistance = visualLayerDistance(relativeSteps, segmentCount)
    val selectedSegment = visualSelectedSegment(relativeSteps, segmentCount)
    val layerStartIndex = visualLayerStartIndex(
        nowFrameIndex = nowFrameIndex,
        direction = directionForColor,
        layerDistance = layerDistance,
        segmentCount = segmentCount
    )
    val selectedLayerIsBroadForecast = selectedFrame?.isBroadForecastLayer() == true
    val stepAngle = 360f / segmentCount.toFloat()
    val sweepAngle = stepAngle - gapAngle
    val layerColor = layerTimelineColor(
        direction = directionForColor,
        layerDistance = layerDistance,
        isBroadForecast = selectedLayerIsBroadForecast
    )
    val previousLayerColor = if (selectedDirection == 0 || layerDistance == 0) {
        null
    } else {
        val carryFrameIndex = if (directionForColor > 0) {
            layerStartIndex - 1
        } else {
            layerStartIndex + segmentCount
        }
        val carryLayerIsBroadForecast = frames.getOrNull(carryFrameIndex)?.isBroadForecastLayer()
            ?: selectedLayerIsBroadForecast
        layerTimelineColor(
            direction = directionForColor,
            layerDistance = layerDistance - 1,
            isBroadForecast = carryLayerIsBroadForecast
        )
    }

    repeat(segmentCount) { segment ->
        val frame = frames.getOrNull(layerStartIndex + segment)
        val segmentColor = when {
            frame == null -> Color(0xFF111821).copy(alpha = 0.36f)
            segment == selectedSegment -> Color(0xFFEAF6FF).copy(alpha = 0.94f)
            selectedDirection > 0 && segment < selectedSegment -> layerColor
            selectedDirection < 0 && segment > selectedSegment -> layerColor
            selectedDirection > 0 && previousLayerColor != null && segment > selectedSegment -> previousLayerColor
            selectedDirection < 0 && previousLayerColor != null && segment < selectedSegment -> previousLayerColor
            else -> Color(0xFF111821).copy(alpha = 0.5f)
        }

        drawArc(
            color = segmentColor,
            startAngle = -90f + (segment * stepAngle) + gapAngle / 2f,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = topLeft,
            size = ringBounds,
            style = Stroke(width = strokeWidth)
        )
        if (frame != null) {
            val loadProgress = frameLoadProgress[frame.timestampMillis]?.coerceIn(0f, 1f) ?: 0f
            val pendingAlpha = (1f - loadProgress) * 0.72f
            if (pendingAlpha > 0f) {
                drawArc(
                    color = Color(0xFF8E98A3).copy(alpha = pendingAlpha),
                    startAngle = -90f + (segment * stepAngle) + gapAngle / 2f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringBounds,
                    style = Stroke(width = strokeWidth)
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLoadingTimelineRing(
    center: Offset,
    radius: Float,
    strokeWidth: Float,
    progress: Float
) {
    val segmentCount = STEPS_PER_REVOLUTION
    val gapAngle = TIMELINE_SEGMENT_GAP_ANGLE
    val stepAngle = 360f / segmentCount.toFloat()
    val sweepAngle = stepAngle - gapAngle
    val ringBounds = Size(radius * 2f, radius * 2f)
    val topLeft = Offset(center.x - radius, center.y - radius)

    repeat(segmentCount) { segment ->
        drawArc(
            color = Color(0xFF111821).copy(alpha = 0.5f),
            startAngle = -90f + (segment * stepAngle) + gapAngle / 2f,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = topLeft,
            size = ringBounds,
            style = Stroke(width = strokeWidth)
        )
    }
    drawArc(
        color = Color(0xFFA8F4FF).copy(alpha = 0.9f),
        startAngle = -90f + (progress.coerceIn(0f, 1f) * 360f) + gapAngle / 2f,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = topLeft,
        size = ringBounds,
        style = Stroke(width = strokeWidth)
    )
}

private fun layerTimelineColor(
    direction: Int,
    layerDistance: Int,
    isBroadForecast: Boolean
): Color {
    if (direction == 0) {
        return Color(0xFF7890A8).copy(alpha = 0.62f)
    }
    val distance = layerDistance.coerceAtMost(MAX_LAYER_SHADE_DISTANCE)
    val shade = distance / MAX_LAYER_SHADE_DISTANCE.toFloat()
    val alpha = (0.88f - (distance * 0.08f)).coerceAtLeast(0.48f)
    val color = if (direction > 0 && isBroadForecast) {
        blendColor(Color(0xFFA9C5FF), Color(0xFF3558B8), shade)
    } else if (direction > 0) {
        blendColor(Color(0xFFA8F4FF), Color(0xFF0075A8), shade)
    } else {
        blendColor(Color(0xFFE0C1FF), Color(0xFF7043B8), shade)
    }
    return color.copy(alpha = alpha)
}

private fun selectedTimelineLayerColor(
    selectedFrameIndex: Int,
    nowFrameIndex: Int,
    frames: List<RadarFrameReference>
): Color {
    val selectedFrame = frames.getOrNull(selectedFrameIndex) ?: return Color(0xFFA8F4FF)
    val segmentCount = selectedFrame.visualSegmentCount()
    val relativeSteps = selectedFrameIndex - nowFrameIndex
    val layerDistance = visualLayerDistance(relativeSteps, segmentCount)
    val direction = if (selectedFrameIndex < nowFrameIndex) -1 else 1
    return layerTimelineColor(
        direction = direction,
        layerDistance = layerDistance,
        isBroadForecast = selectedFrame.isBroadForecastLayer()
    )
}

private fun RadarFrameReference.isBroadForecastLayer(): Boolean {
    return timeStepMillis >= BROAD_FORECAST_TIMESTEP_MILLIS
}

private fun RadarFrameReference.visualSegmentCount(): Int {
    val groupMillis = if (isBroadForecastLayer()) DAY_MILLIS else HOUR_MILLIS
    return (groupMillis / timeStepMillis.coerceAtLeast(1L))
        .toInt()
        .coerceAtLeast(1)
}

private fun visualSelectedSegment(relativeSteps: Int, segmentCount: Int): Int {
    return positiveModulo(relativeSteps, segmentCount.coerceAtLeast(1))
}

private fun visualLayerDistance(relativeSteps: Int, segmentCount: Int): Int {
    val normalizedSegmentCount = segmentCount.coerceAtLeast(1)
    return if (relativeSteps >= 0) {
        relativeSteps / normalizedSegmentCount
    } else {
        ((-relativeSteps) - 1) / normalizedSegmentCount
    }
}

private fun visualLayerStartIndex(
    nowFrameIndex: Int,
    direction: Int,
    layerDistance: Int,
    segmentCount: Int
): Int {
    val normalizedSegmentCount = segmentCount.coerceAtLeast(1)
    return if (direction < 0) {
        nowFrameIndex - ((layerDistance + 1) * normalizedSegmentCount)
    } else {
        nowFrameIndex + (layerDistance * normalizedSegmentCount)
    }
}

private fun blendColor(start: Color, end: Color, fraction: Float): Color {
    val clamped = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + ((end.red - start.red) * clamped),
        green = start.green + ((end.green - start.green) * clamped),
        blue = start.blue + ((end.blue - start.blue) * clamped),
        alpha = start.alpha + ((end.alpha - start.alpha) * clamped)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGraticule(
    viewport: MapViewport,
    color: Color
) {
    listOf(45.0, 50.0, 55.0).forEach { latitude ->
        val left = viewport.toScreen(GeoPoint(latitude, 0.0))
        val right = viewport.toScreen(GeoPoint(latitude, 17.0))
        drawLine(color = color, start = left, end = right, strokeWidth = 1.dp.toPx())
    }
    listOf(0.0, 5.0, 10.0, 15.0).forEach { longitude ->
        val top = viewport.toScreen(GeoPoint(57.0, longitude))
        val bottom = viewport.toScreen(GeoPoint(43.75, longitude))
        drawLine(color = color, start = top, end = bottom, strokeWidth = 1.dp.toPx())
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGermanyOutlines(
    viewport: MapViewport,
    outlines: List<List<GeoPoint>>,
    fill: Color,
    stroke: Color
) {
    outlines.forEach { outline ->
        if (outline.size < 2) {
            return@forEach
        }
        val path = Path().apply {
            outline.forEachIndexed { index, point ->
                val projected = viewport.toScreen(point)
                if (index == 0) {
                    moveTo(projected.x, projected.y)
                } else {
                    lineTo(projected.x, projected.y)
                }
            }
            close()
        }
        drawPath(path = path, color = fill)
        drawPath(
            path = path,
            color = Color.Black.copy(alpha = 0.4f),
            style = Stroke(width = 2.4.dp.toPx())
        )
        drawPath(path = path, color = stroke, style = Stroke(width = 1.1.dp.toPx()))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCityLabels(
    viewport: MapViewport,
    zoomPreset: ZoomPreset,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    labelStyle: TextStyle
) {
    val dotRadius = 1.8.dp.toPx()
    val labelOffsetX = 5.dp.toPx()
    val labelOffsetY = 4.dp.toPx()

    CityCatalog.visibleFor(zoomPreset).forEach { city ->
        val point = viewport.toScreen(city.location)
        if (point.x !in -12f..(size.width + 12f) || point.y !in -12f..(size.height + 12f)) {
            return@forEach
        }

        val layout = textMeasurer.measure(
            text = city.name,
            style = labelStyle
        )
        val labelTopLeft = Offset(
            x = point.x + labelOffsetX,
            y = point.y - labelOffsetY
        )

        if (labelTopLeft.x > size.width ||
            labelTopLeft.y > size.height ||
            labelTopLeft.x + layout.size.width < 0f ||
            labelTopLeft.y + layout.size.height < 0f
        ) {
            return@forEach
        }

        drawCircle(
            color = Color.Black.copy(alpha = 0.5f),
            radius = dotRadius + 0.8.dp.toPx(),
            center = point + Offset(0.6f, 0.6f)
        )
        drawCircle(
            color = Color(0xFFE8EEF5),
            radius = dotRadius,
            center = point
        )
        drawText(
            textLayoutResult = layout,
            topLeft = labelTopLeft + Offset(0.8f, 0.8f),
            color = Color.Black.copy(alpha = 0.6f)
        )
        drawText(
            textLayoutResult = layout,
            topLeft = labelTopLeft
        )
    }
}

private fun zoomLabel(context: android.content.Context, zoomPreset: ZoomPreset): String {
    return when (zoomPreset) {
        ZoomPreset.NEAR -> context.getString(R.string.zoom_near)
        ZoomPreset.MID -> context.getString(R.string.zoom_mid)
        ZoomPreset.FAR -> context.getString(R.string.zoom_far)
    }
}

private fun compactErrorDetails(errorMessage: String?): String {
    val compactMessage = errorMessage
        ?.replace("https://app-prod-static.warnwetter.de/v16/", "")
        ?.replace('\n', ' ')
        ?.trim()
        .orEmpty()
        .ifBlank { "Unbekannter Fehler" }
    return "Details: $compactMessage"
}

private fun positiveModulo(value: Int, modulo: Int): Int {
    return ((value % modulo) + modulo) % modulo
}

private sealed class FirstTouchResult {
    data class Tap(val upPosition: Offset) : FirstTouchResult()
    object Handled : FirstTouchResult()
    object Canceled : FirstTouchResult()
}

private suspend fun AwaitPointerEventScope.handleBorderScrollGesture(
    down: PointerInputChange,
    center: Offset,
    onRadialScroll: (Float) -> Unit,
    onGestureEnd: () -> Unit
) {
    down.consume()
    var lastAngle = angleDegrees(center = center, point = down.position)
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) {
            change.consume()
            break
        }
        val angle = angleDegrees(center = center, point = change.position)
        val delta = normalizedAngleDelta(angle - lastAngle)
        if (abs(delta) >= MIN_RADIAL_SCROLL_DEGREES) {
            onRadialScroll(delta)
            lastAngle = angle
        }
        change.consume()
    }
    onGestureEnd()
}

private suspend fun AwaitPointerEventScope.awaitFirstTouchResult(
    down: PointerInputChange,
    mapRect: Rect,
    panTouchSlopPx: Float,
    onPanMap: (Float, Float, Rect) -> Unit,
    onLongPress: () -> Unit
): FirstTouchResult {
    val longPressAtMillis = down.uptimeMillis + GESTURE_LONG_PRESS_TIMEOUT_MILLIS
    var lastEventMillis = down.uptimeMillis

    while (true) {
        val millisUntilLongPress = (longPressAtMillis - lastEventMillis).coerceAtLeast(1L)
        val event = withTimeoutOrNull(millisUntilLongPress) { awaitPointerEvent() }
        if (event == null) {
            onLongPress()
            consumePointerUntilUp(down.id)
            return FirstTouchResult.Handled
        }

        val change = event.changes.firstOrNull { it.id == down.id } ?: return FirstTouchResult.Canceled
        lastEventMillis = change.uptimeMillis

        if (!change.pressed) {
            return FirstTouchResult.Tap(change.position)
        }

        if (distanceBetween(change.position, down.position) >= panTouchSlopPx) {
            val delta = change.positionChange()
            change.consume()
            onPanMap(delta.x, delta.y, mapRect)
            consumePanGesture(pointerId = down.id, mapRect = mapRect, onPanMap = onPanMap)
            return FirstTouchResult.Handled
        }
    }
}

private suspend fun AwaitPointerEventScope.handleTapOrZoomGesture(
    firstUpPosition: Offset,
    doubleTapSamePointPx: Float,
    zoomSwipeTouchSlopPx: Float,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onZoomSwipe: (Float) -> Unit,
    onGestureEnd: () -> Unit
) {
    val secondDown = withTimeoutOrNull(DOUBLE_TAP_DRAG_TIMEOUT_MILLIS) {
        awaitFirstDown(requireUnconsumed = false)
    }

    if (secondDown == null) {
        onSingleTap()
        return
    }

    if (distanceBetween(secondDown.position, firstUpPosition) > doubleTapSamePointPx) {
        onSingleTap()
        consumePointerUntilUp(secondDown.id)
        return
    }

    secondDown.consume()
    var totalX = 0f
    var totalY = 0f
    var zoomActive = false

    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == secondDown.id } ?: break

        if (!change.pressed) {
            change.consume()
            if (zoomActive) {
                onGestureEnd()
            } else {
                onDoubleTap()
            }
            return
        }

        val delta = change.positionChange()
        totalX += delta.x
        totalY += delta.y

        if (!zoomActive) {
            val verticalDragStarted = abs(totalY) >= zoomSwipeTouchSlopPx &&
                abs(totalY) > abs(totalX) * ZOOM_SWIPE_VERTICAL_BIAS
            val nonZoomDragStarted = distanceBetween(Offset(totalX, totalY), Offset.Zero) >= zoomSwipeTouchSlopPx &&
                !verticalDragStarted

            when {
                verticalDragStarted -> zoomActive = true
                nonZoomDragStarted -> {
                    change.consume()
                    consumePointerUntilUp(secondDown.id)
                    return
                }
            }
        }

        if (zoomActive) {
            onZoomSwipe(delta.y)
            change.consume()
        }
    }
}

private suspend fun AwaitPointerEventScope.consumePanGesture(
    pointerId: PointerId,
    mapRect: Rect,
    onPanMap: (Float, Float, Rect) -> Unit
) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        if (!change.pressed) {
            change.consume()
            return
        }
        val delta = change.positionChange()
        if (delta != Offset.Zero) {
            onPanMap(delta.x, delta.y, mapRect)
        }
        change.consume()
    }
}

private suspend fun AwaitPointerEventScope.consumePointerUntilUp(pointerId: PointerId) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        if (!change.pressed) {
            change.consume()
            return
        }
        change.consume()
    }
}

private fun distanceBetween(first: Offset, second: Offset): Float {
    val dx = first.x - second.x
    val dy = first.y - second.y
    return sqrt((dx * dx) + (dy * dy))
}

private fun angleDegrees(center: Offset, point: Offset): Float {
    return (atan2(point.y - center.y, point.x - center.x) * 180f / PI.toFloat())
}

private fun normalizedAngleDelta(deltaDegrees: Float): Float {
    var normalized = deltaDegrees
    while (normalized > 180f) {
        normalized -= 360f
    }
    while (normalized < -180f) {
        normalized += 360f
    }
    return normalized
}

private const val BORDER_SCROLL_TOUCH_WIDTH_DP = 34
private const val DOUBLE_TAP_DRAG_SAME_POINT_DP = 28
private const val DOUBLE_TAP_DRAG_TIMEOUT_MILLIS = 320L
private const val GESTURE_LONG_PRESS_TIMEOUT_MILLIS = 520L
private const val HOUR_MILLIS = 60 * 60 * 1000L
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private const val FRAME_DECODE_PROGRESS_CAP = 0.92f
private const val LOADING_RING_DURATION_MILLIS = 900
private const val MIN_RADIAL_SCROLL_DEGREES = 0.35f
private const val TIME_REFRESH_PULSE_MILLIS = 520L
private const val STEPS_PER_REVOLUTION = 12
private const val TIMELINE_SEGMENT_GAP_ANGLE = 1.2f
private const val MAX_LAYER_SHADE_DISTANCE = 4
private const val BROAD_FORECAST_TIMESTEP_MILLIS = 60 * 60 * 1000L
private const val PAN_TOUCH_SLOP_DP = 10
private const val ZOOM_SWIPE_TOUCH_SLOP_DP = 14
private const val ZOOM_SWIPE_VERTICAL_BIAS = 1.45f
