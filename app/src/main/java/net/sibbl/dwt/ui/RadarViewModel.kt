package net.sibbl.dwt.ui

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.sibbl.dwt.data.radar.RadarBackend
import net.sibbl.dwt.data.radar.RadarBitmapFrame
import net.sibbl.dwt.data.radar.RadarRepository
import net.sibbl.dwt.location.LocationRepository
import net.sibbl.dwt.map.GermanyOutlineRepository
import net.sibbl.dwt.map.MapViewport
import net.sibbl.dwt.model.MapCamera
import net.sibbl.dwt.model.RadarFrameReference
import net.sibbl.dwt.model.RadarTimeline
import net.sibbl.dwt.model.ZoomPreset
import net.sibbl.dwt.storage.RadarLayerPreferenceStore
import net.sibbl.dwt.storage.MapCameraPreferenceStore
import net.sibbl.dwt.storage.UserLocationPreferenceStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RadarViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val radarRepository = RadarRepository(application)
    private val outlineRepository = GermanyOutlineRepository(application)
    private val locationRepository = LocationRepository(application)
    private val mapCameraPreferenceStore = MapCameraPreferenceStore(application)
    private val radarLayerPreferenceStore = RadarLayerPreferenceStore(application)
    private val userLocationPreferenceStore = UserLocationPreferenceStore(application)
    private val rotaryStepAccumulator = RotaryStepAccumulator(ROTARY_TICKS_PER_STEP)
    private val radialStepAccumulator = RotaryStepAccumulator(RADIAL_DEGREES_PER_STEP)
    private val zoomSwipeAccumulator = RotaryStepAccumulator(ZOOM_SWIPE_PIXELS_PER_STEP)
    private val loadedFrames = FrameWindowCache<RadarFrameReference, RadarBitmapFrame>()
    private val loadedCloudFrames = FrameWindowCache<RadarFrameReference, RadarBitmapFrame>()
    private val loadedLightningFrames = FrameWindowCache<RadarFrameReference, RadarBitmapFrame>()

    private val _uiState = MutableStateFlow(
        radarLayerPreferenceStore.restore().let { layerPreferences ->
            RadarUiState(
                mapCamera = mapCameraPreferenceStore.restoreMapCamera(),
                rainLayerVisible = layerPreferences.rainVisible,
                cloudLayerVisible = layerPreferences.cloudVisible,
                lightningLayerVisible = layerPreferences.lightningVisible,
                userLocation = userLocationPreferenceStore.restore()
            )
        }
    )
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    private var frameJob: Job? = null
    private var cloudFrameJob: Job? = null
    private var lightningFrameJob: Job? = null
    private var timelineRefreshJob: Job? = null
    private val prefetcher = LayerPrefetcher<RadarFrameReference>(
        scope = viewModelScope,
        isReady = { reference -> cachedFrame(reference) != null },
        load = { reference ->
            val frame = radarRepository.loadFrame(reference) { progress ->
                markAssetProgress(reference.assetPath, progress)
            }
            when (reference.layerKey) {
                RadarBackend.CLOUD_LAYER -> rememberLoadedCloudFrame(frame)
                in RadarBackend.lightningLayers -> rememberLoadedLightningFrame(frame)
                else -> rememberLoadedFrame(frame)
            }
            // Scrubbing may select a frame while this queue is preparing it.
            publishCachedSelection()
        }
    )
    private var longPressLocationRefreshJob: Job? = null
    private var longPressLocationRefreshGeneration = 0
    private var hasSavedMapCenter = mapCameraPreferenceStore.hasSavedCenter()
    private var hasCenteredOnLocation = hasSavedMapCenter
    private var menuClosedTimeMillis: Long = 0L
    var isLayerMenuOpen: Boolean = false
        private set

    init {
        loadOutlines()
        refreshTimeline()
        startPlaybackLoop()
        startPeriodicRefreshLoop()
    }

    fun onAppOpened() {
        refreshTimeline(forceRefresh = true, selectNow = true)
        val timeline = _uiState.value.timeline
        if (timeline.frames.isEmpty()) {
            return
        }
        rotaryStepAccumulator.reset()
        _uiState.update { state ->
            val reference = timeline.frames.getOrNull(timeline.nowFrameIndex)
            state.copy(
                isPlaying = false,
                selectedFrameIndex = timeline.nowFrameIndex,
                selectedFrame = loadedFrameFor(reference),
                selectedCloudFrame = loadedCloudFrameForSelected(reference?.timestampMillis),
                selectedLightningFrame = loadedLightningFrameForSelected(reference?.timestampMillis),
                errorMessage = null
            )
        }
        retainNavigationWindow()
        loadFrameAt(timeline.nowFrameIndex, force = true)
        loadCloudFrameForSelected(force = true)
        loadLightningFrameForSelected(force = true)
        scheduleSmartPrefetch(timeline.nowFrameIndex)
    }

    fun onLocationPermissionChanged(granted: Boolean) {
        if (!granted) {
            return
        }
        _uiState.update { it.copy(isLocationLoading = true) }
        viewModelScope.launch {
            try {
                val location = locationRepository.currentLocation()
                    ?.takeIf(RadarBackend.defaultBounds::contains)
                val shouldCenterOnLocation = location != null && !hasCenteredOnLocation && !hasSavedMapCenter
                var updatedCamera: MapCamera? = null
                _uiState.update { state ->
                    val nextCamera = if (shouldCenterOnLocation) {
                        state.mapCamera.copy(center = requireNotNull(location))
                    } else {
                        state.mapCamera
                    }
                    updatedCamera = if (shouldCenterOnLocation) nextCamera else null
                    state.copy(
                        userLocation = location ?: state.userLocation,
                        mapCamera = nextCamera
                    )
                }
                updatedCamera?.let(::persistMapCamera)
                if (location != null && shouldCenterOnLocation) {
                    hasCenteredOnLocation = true
                }
                if (location != null) {
                    userLocationPreferenceStore.save(location)
                }
            } finally {
                _uiState.update { it.copy(isLocationLoading = false) }
            }
        }
    }

    fun togglePlayback() {
        _uiState.update { state ->
            if (state.timeline.frames.isEmpty()) {
                state
            } else {
                state.copy(isPlaying = !state.isPlaying)
            }
        }
    }

    fun toggleRainLayer() {
        var nextVisible = true
        _uiState.update { state ->
            nextVisible = !state.rainLayerVisible
            state.copy(rainLayerVisible = nextVisible)
        }
        radarLayerPreferenceStore.saveRainVisible(nextVisible)
    }

    fun toggleCloudLayer() {
        var shouldLoadCloudFrame = false
        var shouldLoadCloudTimeline = false
        var nextVisible = true
        _uiState.update { state ->
            nextVisible = !state.cloudLayerVisible
            shouldLoadCloudFrame = nextVisible
            shouldLoadCloudTimeline = nextVisible && state.cloudTimeline.frames.isEmpty()
            state.copy(
                cloudLayerVisible = nextVisible,
                selectedCloudFrame = if (nextVisible) {
                    loadedCloudFrameForSelected(
                        state.timeline.frames
                            .getOrNull(state.selectedFrameIndex)
                            ?.timestampMillis
                    )
                } else {
                    state.selectedCloudFrame
                }
            )
        }
        radarLayerPreferenceStore.saveCloudVisible(nextVisible)
        retainNavigationWindow()
        if (shouldLoadCloudTimeline) {
            refreshCloudTimeline()
        } else if (shouldLoadCloudFrame) {
            loadCloudFrameForSelected()
        }
        scheduleSmartPrefetch(_uiState.value.selectedFrameIndex)
    }

    fun toggleLightningLayer() {
        var shouldLoadLightningFrame = false
        var shouldLoadLightningTimeline = false
        var nextVisible = true
        _uiState.update { state ->
            nextVisible = !state.lightningLayerVisible
            shouldLoadLightningFrame = nextVisible
            shouldLoadLightningTimeline = nextVisible && state.lightningTimeline.frames.isEmpty()
            state.copy(
                lightningLayerVisible = nextVisible,
                selectedLightningFrame = if (nextVisible) {
                    loadedLightningFrameForSelected(
                        state.timeline.frames.getOrNull(state.selectedFrameIndex)?.timestampMillis
                    )
                } else {
                    state.selectedLightningFrame
                }
            )
        }
        radarLayerPreferenceStore.saveLightningVisible(nextVisible)
        retainNavigationWindow()
        if (shouldLoadLightningTimeline) {
            refreshLightningTimeline()
        } else if (shouldLoadLightningFrame) {
            loadLightningFrameForSelected()
        }
        scheduleSmartPrefetch(_uiState.value.selectedFrameIndex)
    }

    fun cycleZoom() {
        updateMapCamera { camera ->
            camera.copy(zoomPreset = camera.zoomPreset.next())
        }
    }

    fun zoomBySwipe(deltaY: Float) {
        val zoomDelta = zoomSwipeAccumulator.consume(deltaY)
        if (zoomDelta == 0) {
            return
        }
        updateMapCamera { camera ->
            if (zoomDelta < 0) {
                camera.copy(zoomPreset = camera.zoomPreset.zoomedIn())
            } else {
                camera.copy(zoomPreset = camera.zoomPreset.zoomedOut())
            }
        }
    }

    fun resetToNowAndCenter() {
        val state = _uiState.value
        val nowIndex = state.timeline.nowFrameIndex
        val refreshGeneration = ++longPressLocationRefreshGeneration
        val forceCenterUntilMillis = System.currentTimeMillis() + LONG_PRESS_LOCATION_FORCE_CENTER_MILLIS
        val centered = state.userLocation?.takeIf(RadarBackend.defaultBounds::contains)
            ?: RadarBackend.defaultBounds.center
        hasCenteredOnLocation = state.userLocation != null
        rotaryStepAccumulator.reset()
        radialStepAccumulator.reset()
        zoomSwipeAccumulator.reset()
        val updatedCamera = state.mapCamera.copy(center = centered)
        _uiState.update {
            val reference = state.timeline.frames.getOrNull(nowIndex)
            it.copy(
                isPlaying = false,
                selectedFrameIndex = nowIndex,
                selectedFrame = loadedFrameFor(reference),
                selectedCloudFrame = loadedCloudFrameForSelected(reference?.timestampMillis),
                selectedLightningFrame = loadedLightningFrameForSelected(reference?.timestampMillis),
                mapCamera = updatedCamera,
                errorMessage = null
            )
        }
        retainNavigationWindow()
        persistMapCamera(updatedCamera)
        loadFrameAt(nowIndex, force = true)
        loadCloudFrameForSelected(force = true)
        loadLightningFrameForSelected(force = true)
        scheduleSmartPrefetch(nowIndex)
        refreshLocationAfterLongPress(
            generation = refreshGeneration,
            forceCenterUntilMillis = forceCenterUntilMillis
        )
    }

    fun panMap(deltaX: Float, deltaY: Float, mapRect: Rect) {
        val state = _uiState.value
        val newCenter = MapViewport(mapRect = mapRect, camera = state.mapCamera)
            .panCenterBy(deltaX = deltaX, deltaY = deltaY)
        hasCenteredOnLocation = false
        updateMapCamera { camera ->
            camera.copy(center = newCenter)
        }
    }

    fun scrubByRotary(deltaTicks: Float) {
        if (android.os.SystemClock.uptimeMillis() - menuClosedTimeMillis < ROTARY_CLOSE_COOLDOWN_MILLIS) {
            rotaryStepAccumulator.reset()
            return
        }
        val timeline = _uiState.value.timeline
        if (timeline.frames.isEmpty()) {
            return
        }

        val stepDelta = rotaryStepAccumulator.consume(deltaTicks)
        if (stepDelta == 0) {
            return
        }
        selectIndex(_uiState.value.selectedFrameIndex + stepDelta, pausePlayback = true)
    }

    fun scrubByRadialGesture(deltaDegrees: Float) {
        val timeline = _uiState.value.timeline
        if (timeline.frames.isEmpty()) {
            return
        }
        val stepDelta = radialStepAccumulator.consume(deltaDegrees)
        if (stepDelta == 0) {
            return
        }
        selectIndex(_uiState.value.selectedFrameIndex + stepDelta, pausePlayback = true)
    }

    fun finishGestureInput() {
        radialStepAccumulator.reset()
        zoomSwipeAccumulator.reset()
        val selectedIndex = _uiState.value.selectedFrameIndex
        loadFrameAt(selectedIndex)
        scheduleSmartPrefetch(selectedIndex)
    }

    fun retry() {
        refreshTimeline(forceRefresh = true)
    }

    fun refreshRadarData() {
        refreshTimeline(forceRefresh = true)
    }

    fun setLayerMenuOpen(open: Boolean) {
        isLayerMenuOpen = open
        if (!open) {
            menuClosedTimeMillis = android.os.SystemClock.uptimeMillis()
        }
        _uiState.update { state ->
            state.copy(
                layerMenuExpanded = open,
                layerMenuScrollDp = if (open) state.layerMenuScrollDp else 0f
            )
        }
    }

    fun setLayerMenuScroll(scrollDp: Float, maxScrollDp: Float) {
        _uiState.update { state ->
            state.copy(
                layerMenuScrollDp = scrollDp.coerceIn(0f, maxScrollDp)
            )
        }
    }

    fun scrollLayerMenu(deltaTicks: Float, maxScrollDp: Float) {
        if (!isLayerMenuOpen) {
            return
        }
        _uiState.update { state ->
            val nextScroll = (state.layerMenuScrollDp + (deltaTicks * MENU_SCROLL_DP_PER_TICK))
                .coerceIn(0f, maxScrollDp)
            val shouldClose = deltaTicks < 0f && state.layerMenuScrollDp <= 0f && nextScroll <= 0f
            if (shouldClose) {
                isLayerMenuOpen = false
                menuClosedTimeMillis = android.os.SystemClock.uptimeMillis()
                state.copy(
                    layerMenuExpanded = false,
                    layerMenuScrollDp = 0f
                )
            } else {
                state.copy(layerMenuScrollDp = nextScroll)
            }
        }
    }

    private fun loadOutlines() {
        viewModelScope.launch {
            val outlines = outlineRepository.loadOutlines()
            _uiState.update { state ->
                state.copy(germanyOutlines = outlines)
            }
        }
    }

    private fun refreshTimeline(forceRefresh: Boolean = false, selectNow: Boolean = false) {
        timelineRefreshJob?.cancel()
        timelineRefreshJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    isLoading = state.timeline.frames.isEmpty(),
                    errorMessage = null
                )
            }

            var lastError: Throwable? = null
            repeat(TIMELINE_LOAD_ATTEMPTS) { attempt ->
                runCatching {
                    val precipitationTimeline = radarRepository.loadTimeline(
                        forceRefresh = forceRefresh || attempt > 0,
                        layerKey = RadarBackend.PRECIPITATION_LAYER
                    )
                    val cloudTimeline = runCatching {
                        radarRepository.loadTimeline(layerKey = RadarBackend.CLOUD_LAYER)
                    }.getOrElse {
                        RadarTimeline(
                            frames = emptyList(),
                            nowTimestampMillis = precipitationTimeline.nowTimestampMillis,
                            nowFrameIndex = 0
                        )
                    }
                    val lightningTimeline = runCatching {
                        radarRepository.loadTimeline(layerKeys = RadarBackend.lightningLayers)
                    }.getOrElse {
                        RadarTimeline(
                            frames = emptyList(),
                            nowTimestampMillis = precipitationTimeline.nowTimestampMillis,
                            nowFrameIndex = 0
                        )
                    }
                    Triple(precipitationTimeline, cloudTimeline, lightningTimeline)
                }.onSuccess { (precipitationTimeline, cloudTimeline, lightningTimeline) ->
                    applyTimelines(
                        timeline = precipitationTimeline,
                        cloudTimeline = cloudTimeline,
                        lightningTimeline = lightningTimeline,
                        selectNow = selectNow
                    )
                    return@launch
                }.onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    lastError = throwable
                }
                delay(TIMELINE_RETRY_DELAY_MILLIS * (attempt + 1))
            }

            lastError?.let { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        isPlaying = false,
                        errorMessage = throwable.message
                    )
                }
            }
        }
    }

    private fun refreshCloudTimeline() {
        viewModelScope.launch {
            runCatching {
                radarRepository.loadTimeline(layerKey = RadarBackend.CLOUD_LAYER)
            }.onSuccess { cloudTimeline ->
                _uiState.update { state ->
                    val selectedTimestamp = state.timeline.frames
                        .getOrNull(state.selectedFrameIndex)
                        ?.timestampMillis
                    val newProgress = initialFrameLoadProgress(
                        state.timeline,
                        cloudTimeline,
                        state.lightningTimeline
                    )
                    state.copy(
                        cloudTimeline = cloudTimeline,
                        frameLoadProgress = state.frameLoadProgress + newProgress,
                        selectedCloudFrame = loadedCloudFrameForSelected(
                            timestampMillis = selectedTimestamp,
                            cloudTimeline = cloudTimeline
                        )
                    )
                }
                retainNavigationWindow()
                loadCloudFrameForSelected(force = true)
                scheduleSmartPrefetch(_uiState.value.selectedFrameIndex)
            }
        }
    }

    private fun refreshLightningTimeline() {
        viewModelScope.launch {
            runCatching {
                radarRepository.loadTimeline(layerKeys = RadarBackend.lightningLayers)
            }.onSuccess { lightningTimeline ->
                _uiState.update { state ->
                    val selectedTimestamp = state.timeline.frames
                        .getOrNull(state.selectedFrameIndex)
                        ?.timestampMillis
                    state.copy(
                        lightningTimeline = lightningTimeline,
                        frameLoadProgress = state.frameLoadProgress +
                            initialFrameLoadProgress(state.timeline, state.cloudTimeline, lightningTimeline),
                        selectedLightningFrame = loadedLightningFrameForSelected(
                            timestampMillis = selectedTimestamp,
                            lightningTimeline = lightningTimeline
                        )
                    )
                }
                retainNavigationWindow()
                loadLightningFrameForSelected(force = true)
                scheduleSmartPrefetch(_uiState.value.selectedFrameIndex)
            }
        }
    }

    private fun applyTimelines(
        timeline: RadarTimeline,
        cloudTimeline: RadarTimeline,
        lightningTimeline: RadarTimeline,
        selectNow: Boolean
    ) {
        val previousState = _uiState.value
        val previousTimestamp = previousState.timeline.frames
            .getOrNull(previousState.selectedFrameIndex)
            ?.timestampMillis
        val preservedIndex = selectedIndexAfterTimelineRefresh(
            timeline = timeline,
            previousTimestamp = previousTimestamp,
            selectNow = selectNow
        )
        _uiState.update { state ->
            val reference = timeline.frames.getOrNull(preservedIndex)
            state.copy(
                isLoading = false,
                timeline = timeline,
                cloudTimeline = cloudTimeline,
                lightningTimeline = lightningTimeline,
                selectedFrameIndex = preservedIndex,
                selectedFrame = loadedFrameFor(reference),
                selectedCloudFrame = loadedCloudFrameForSelected(
                    timestampMillis = reference?.timestampMillis,
                    cloudTimeline = cloudTimeline
                ),
                selectedLightningFrame = loadedLightningFrameForSelected(
                    timestampMillis = reference?.timestampMillis,
                    lightningTimeline = lightningTimeline
                ),
                frameLoadProgress = initialFrameLoadProgress(timeline, cloudTimeline, lightningTimeline),
                errorMessage = null
            )
        }
        retainNavigationWindow()
        loadFrameAt(preservedIndex)
        loadCloudFrameForSelected()
        loadLightningFrameForSelected()
        scheduleSmartPrefetch(preservedIndex)
    }

    private fun startPeriodicRefreshLoop() {
        viewModelScope.launch {
            while (true) {
                delay(5 * 60 * 1000L)
                refreshTimeline(forceRefresh = true)
            }
        }
    }

    private fun startPlaybackLoop() {
        viewModelScope.launch {
            while (true) {
                delay(ANIMATION_FRAME_DELAY_MILLIS)
                val state = _uiState.value
                if (!state.isPlaying || state.timeline.frames.isEmpty()) {
                    continue
                }
                val nextIndex = if (state.selectedFrameIndex >= state.timeline.frames.lastIndex) {
                    0
                } else {
                    state.selectedFrameIndex + 1
                }
                selectIndex(nextIndex, pausePlayback = false)
            }
        }
    }

    private fun selectIndex(index: Int, pausePlayback: Boolean) {
        val timeline = _uiState.value.timeline
        if (timeline.frames.isEmpty()) {
            return
        }
        val clampedIndex = index.coerceIn(0, timeline.frames.lastIndex)
        val reference = timeline.frameAt(clampedIndex)
        _uiState.update { state ->
            state.copy(
                isPlaying = if (pausePlayback) false else state.isPlaying,
                selectedFrameIndex = clampedIndex,
                selectedFrame = loadedFrameFor(reference),
                selectedCloudFrame = loadedCloudFrameForSelected(reference.timestampMillis),
                selectedLightningFrame = loadedLightningFrameForSelected(reference.timestampMillis),
                errorMessage = null
            )
        }
        retainNavigationWindow()
        loadFrameAt(clampedIndex)
        loadCloudFrameForSelected()
        loadLightningFrameForSelected()
        scheduleSmartPrefetch(clampedIndex)
    }

    private fun loadFrameAt(index: Int, force: Boolean = false) {
        val timeline = _uiState.value.timeline
        if (timeline.frames.isEmpty()) {
            return
        }
        val reference = timeline.frameAt(index)
        frameJob?.cancel()
        val cachedFrame = loadedFrameFor(reference)
        if (!force && cachedFrame != null) {
            markAssetProgress(reference.assetPath, 1f)
            _uiState.update { state ->
                val currentReference = state.timeline.frames.getOrNull(state.selectedFrameIndex)
                if (currentReference == reference) {
                    state.copy(
                        isLoading = false,
                        selectedFrame = cachedFrame,
                        errorMessage = null
                    )
                } else {
                    state
                }
            }
            return
        }
        frameJob = viewModelScope.launch {
            markAssetProgress(
                assetPath = reference.assetPath,
                progress = _uiState.value.frameLoadProgress[reference.timestampMillis] ?: 0f
            )
            runCatching {
                radarRepository.loadFrame(reference) { progress ->
                    markAssetProgress(reference.assetPath, progress)
                }
            }.onSuccess { frame ->
                rememberLoadedFrame(frame)
                markAssetProgress(frame.reference.assetPath, 1f)
                _uiState.update { state ->
                    val currentReference = state.timeline.frames.getOrNull(state.selectedFrameIndex)
                    if (currentReference == frame.reference) {
                        state.copy(
                            isLoading = false,
                            selectedFrame = frame,
                            errorMessage = null
                        )
                    } else {
                        state.copy(isLoading = false)
                    }
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                _uiState.update { state ->
                    val currentReference = state.timeline.frames.getOrNull(state.selectedFrameIndex)
                    if (currentReference == reference) {
                        state.copy(
                            isLoading = false,
                            isPlaying = false,
                            errorMessage = throwable.message
                        )
                    } else {
                        state.copy(isLoading = false)
                    }
                }
            }
        }
    }

    private fun scheduleSmartPrefetch(centerIndex: Int) {
        prefetcher.schedule(framePrefetchPlan(_uiState.value, centerIndex))
    }

    private fun rainPrefetchPlan(state: RadarUiState, centerIndex: Int) = buildLayerPrefetchPlan(
        items = state.timeline.frames,
        layers = visualTimelineLayers(state.timeline).map { it.startIndex until it.endExclusive },
        centerIndex = centerIndex
    )

    private fun framePrefetchPlan(
        state: RadarUiState,
        centerIndex: Int
    ): LayerPrefetchPlan<RadarFrameReference> {
        val rainPlan = rainPrefetchPlan(state, centerIndex)
        fun withOverlays(references: List<RadarFrameReference>) = references.flatMap { reference ->
            listOfNotNull(
                reference,
                if (state.cloudLayerVisible) {
                    closestCloudReference(reference.timestampMillis, state.cloudTimeline)
                } else null,
                if (state.lightningLayerVisible) {
                    closestReference(reference.timestampMillis, state.lightningTimeline)
                } else null
            )
        }.distinct()
        return LayerPrefetchPlan(
            currentLayer = withOverlays(rainPlan.currentLayer),
            adjacentLayers = withOverlays(rainPlan.adjacentLayers)
        )
    }

    private fun cachedFrame(reference: RadarFrameReference): RadarBitmapFrame? = when (reference.layerKey) {
        RadarBackend.CLOUD_LAYER -> loadedCloudFrameFor(reference)
        in RadarBackend.lightningLayers -> loadedLightningFrameFor(reference)
        else -> loadedFrameFor(reference)
    }

    private fun publishCachedSelection() {
        _uiState.update { state ->
            val reference = state.timeline.frames.getOrNull(state.selectedFrameIndex)
            val rainFrame = loadedFrameFor(reference)
            state.copy(
                selectedFrame = rainFrame,
                selectedCloudFrame = loadedCloudFrameForSelected(reference?.timestampMillis),
                selectedLightningFrame = loadedLightningFrameForSelected(reference?.timestampMillis),
                isLoading = if (rainFrame != null) false else state.isLoading,
                errorMessage = if (rainFrame != null) null else state.errorMessage
            )
        }
    }

    private fun loadCloudFrameForSelected(force: Boolean = false) {
        val state = _uiState.value
        if (!state.cloudLayerVisible) {
            return
        }
        val selectedTimestamp = state.timeline.frames
            .getOrNull(state.selectedFrameIndex)
            ?.timestampMillis
            ?: return
        val reference = closestCloudReference(
            timestampMillis = selectedTimestamp,
            cloudTimeline = state.cloudTimeline
        ) ?: return
        loadCloudFrame(reference, force = force)
    }

    private fun loadCloudFrame(reference: RadarFrameReference, force: Boolean = false) {
        cloudFrameJob?.cancel()
        val cachedFrame = loadedCloudFrameFor(reference)
        if (!force && cachedFrame != null) {
            markAssetProgress(reference.assetPath, 1f)
            _uiState.update { state ->
                val currentTimestamp = state.timeline.frames
                    .getOrNull(state.selectedFrameIndex)
                    ?.timestampMillis
                val currentReference = closestCloudReference(currentTimestamp, state.cloudTimeline)
                if (currentReference == reference) {
                    state.copy(selectedCloudFrame = cachedFrame)
                } else {
                    state
                }
            }
            return
        }

        cloudFrameJob = viewModelScope.launch {
            markAssetProgress(
                assetPath = reference.assetPath,
                progress = _uiState.value.frameLoadProgress[reference.timestampMillis] ?: 0f
            )
            runCatching {
                radarRepository.loadFrame(reference) { progress ->
                    markAssetProgress(reference.assetPath, progress)
                }
            }.onSuccess { frame ->
                rememberLoadedCloudFrame(frame)
                markAssetProgress(frame.reference.assetPath, 1f)
                _uiState.update { state ->
                    val currentTimestamp = state.timeline.frames
                        .getOrNull(state.selectedFrameIndex)
                        ?.timestampMillis
                    val currentReference = closestCloudReference(currentTimestamp, state.cloudTimeline)
                    if (currentReference == frame.reference && state.cloudLayerVisible) {
                        state.copy(selectedCloudFrame = frame)
                    } else {
                        state
                    }
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
            }
        }
    }

    private fun loadLightningFrameForSelected(force: Boolean = false) {
        val state = _uiState.value
        if (!state.lightningLayerVisible) {
            return
        }
        val selectedTimestamp = state.timeline.frames
            .getOrNull(state.selectedFrameIndex)
            ?.timestampMillis
            ?: return
        val reference = closestReference(selectedTimestamp, state.lightningTimeline) ?: return
        loadLightningFrame(reference, force)
    }

    private fun loadLightningFrame(reference: RadarFrameReference, force: Boolean = false) {
        lightningFrameJob?.cancel()
        val cachedFrame = loadedLightningFrameFor(reference)
        if (!force && cachedFrame != null) {
            markAssetProgress(reference.assetPath, 1f)
            _uiState.update { state ->
                val selectedTimestamp = state.timeline.frames
                    .getOrNull(state.selectedFrameIndex)
                    ?.timestampMillis
                if (closestReference(selectedTimestamp, state.lightningTimeline) == reference) {
                    state.copy(selectedLightningFrame = cachedFrame)
                } else {
                    state
                }
            }
            return
        }
        lightningFrameJob = viewModelScope.launch {
            runCatching {
                radarRepository.loadFrame(reference) { progress ->
                    markAssetProgress(reference.assetPath, progress)
                }
            }.onSuccess { frame ->
                rememberLoadedLightningFrame(frame)
                markAssetProgress(frame.reference.assetPath, 1f)
                _uiState.update { state ->
                    val selectedTimestamp = state.timeline.frames
                        .getOrNull(state.selectedFrameIndex)
                        ?.timestampMillis
                    if (closestReference(selectedTimestamp, state.lightningTimeline) == frame.reference &&
                        state.lightningLayerVisible
                    ) {
                        state.copy(selectedLightningFrame = frame)
                    } else {
                        state
                    }
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
            }
        }
    }

    private fun visualTimelineLayers(timeline: RadarTimeline): List<VisualTimelineLayerRange> {
        val frames = timeline.frames
        if (frames.isEmpty()) {
            return emptyList()
        }
        val nowIndex = timeline.nowFrameIndex.coerceIn(frames.indices)
        return buildList {
            appendVisualRunPartitions(
                frames = frames,
                runStartIndex = 0,
                runEndExclusive = nowIndex
            )
            var runStartIndex = nowIndex
            while (runStartIndex < frames.size) {
                val runEndExclusive = visualRunEndExclusive(runStartIndex, frames)
                appendVisualRunPartitions(
                    frames = frames,
                    runStartIndex = runStartIndex,
                    runEndExclusive = runEndExclusive
                )
                runStartIndex = runEndExclusive
            }
        }
    }

    private fun MutableList<VisualTimelineLayerRange>.appendVisualRunPartitions(
        frames: List<RadarFrameReference>,
        runStartIndex: Int,
        runEndExclusive: Int
    ) {
        if (runStartIndex >= runEndExclusive) {
            return
        }
        var subRunStartIndex = runStartIndex
        while (subRunStartIndex < runEndExclusive) {
            val subRunEndExclusive = visualRunEndExclusive(subRunStartIndex, frames)
                .coerceAtMost(runEndExclusive)
            val totalFrames = subRunEndExclusive - subRunStartIndex
            val partitionCount = visualPartitionCount(totalFrames)
            repeat(partitionCount) { partitionIndex ->
                val layerStartIndex = subRunStartIndex + visualPartitionStartOffset(
                    totalFrames = totalFrames,
                    partitionIndex = partitionIndex
                )
                val layerEndExclusive = subRunStartIndex + visualPartitionStartOffset(
                    totalFrames = totalFrames,
                    partitionIndex = partitionIndex + 1
                )
                add(VisualTimelineLayerRange(layerStartIndex, layerEndExclusive))
            }
            subRunStartIndex = subRunEndExclusive
        }
    }

    private fun visualRunEndExclusive(startIndex: Int, frames: List<RadarFrameReference>): Int {
        var endExclusive = startIndex + 1
        while (endExclusive < frames.size && frames[endExclusive].continuesVisualLayerAfter(frames[endExclusive - 1])) {
            endExclusive++
        }
        return endExclusive
    }

    private fun visualPartitionCount(totalFrames: Int): Int {
        return ((totalFrames.coerceAtLeast(1) + MAX_VISUAL_LAYER_SEGMENTS - 1) / MAX_VISUAL_LAYER_SEGMENTS)
            .coerceAtLeast(1)
    }

    private fun visualPartitionStartOffset(totalFrames: Int, partitionIndex: Int): Int {
        val partitionCount = visualPartitionCount(totalFrames)
        val clampedPartitionIndex = partitionIndex.coerceIn(0, partitionCount)
        val baseSize = totalFrames / partitionCount
        val largerPartitionCount = totalFrames % partitionCount
        val largerFrames = clampedPartitionIndex.coerceAtMost(largerPartitionCount) * (baseSize + 1)
        val regularFrames = (clampedPartitionIndex - largerPartitionCount).coerceAtLeast(0) * baseSize
        return largerFrames + regularFrames
    }

    private fun RadarFrameReference.continuesVisualLayerAfter(previous: RadarFrameReference): Boolean {
        return timeStepMillis == previous.timeStepMillis &&
            timestampMillis == previous.timestampMillis + previous.timeStepMillis &&
            isBroadForecastLayer() == previous.isBroadForecastLayer()
    }

    private fun RadarFrameReference.isBroadForecastLayer(): Boolean {
        return timeStepMillis >= BROAD_FORECAST_TIMESTEP_MILLIS
    }

    private fun initialFrameLoadProgress(
        timeline: RadarTimeline,
        cloudTimeline: RadarTimeline,
        lightningTimeline: RadarTimeline
    ): Map<Long, Float> {
        val allFrames = timeline.frames + cloudTimeline.frames + lightningTimeline.frames
        val progressByAsset = allFrames
            .distinctBy { it.assetPath }
            .associate { reference ->
                reference.assetPath to if (radarRepository.isAssetCached(reference)) 1f else 0f
            }
        return allFrames.associate { reference ->
            reference.timestampMillis to (progressByAsset[reference.assetPath] ?: 0f)
        }
    }

    private fun markAssetProgress(assetPath: String, progress: Float) {
        val normalizedProgress = progress.coerceIn(0f, 1f)
        _uiState.update { state ->
            val rainUpdates = state.timeline.frames
                .filter { it.assetPath == assetPath }
                .associate { it.timestampMillis to normalizedProgress }
            val cloudUpdates = state.cloudTimeline.frames
                .filter { it.assetPath == assetPath }
                .associate { it.timestampMillis to normalizedProgress }
            val lightningUpdates = state.lightningTimeline.frames
                .filter { it.assetPath == assetPath }
                .associate { it.timestampMillis to normalizedProgress }
            val updates = rainUpdates + cloudUpdates + lightningUpdates
            if (updates.isEmpty()) {
                state
            } else {
                state.copy(frameLoadProgress = state.frameLoadProgress + updates)
            }
        }
    }

    private data class VisualTimelineLayerRange(
        val startIndex: Int,
        val endExclusive: Int
    )

    private fun refreshLocationAfterLongPress(
        generation: Int,
        forceCenterUntilMillis: Long
    ) {
        longPressLocationRefreshJob?.cancel()
        _uiState.update { it.copy(isLocationLoading = true) }
        longPressLocationRefreshJob = viewModelScope.launch {
            try {
                val location = locationRepository.currentLocation()
                    ?.takeIf(RadarBackend.defaultBounds::contains)
                    ?: return@launch
                userLocationPreferenceStore.save(location)
                var updatedCamera: MapCamera? = null
                _uiState.update { state ->
                    if (generation != longPressLocationRefreshGeneration) {
                        return@update state
                    }
                    val shouldForceCenter = System.currentTimeMillis() <= forceCenterUntilMillis
                    val nextCamera = if (shouldForceCenter) {
                        state.mapCamera.copy(center = location)
                    } else {
                        state.mapCamera
                    }
                    updatedCamera = if (shouldForceCenter) nextCamera else null
                    state.copy(
                        userLocation = location,
                        mapCamera = nextCamera
                    )
                }
                updatedCamera?.let { camera ->
                    hasCenteredOnLocation = true
                    persistMapCamera(camera)
                }
            } finally {
                _uiState.update { it.copy(isLocationLoading = false) }
            }
        }
    }

    private fun loadedFrameFor(reference: RadarFrameReference?): RadarBitmapFrame? {
        if (reference == null) {
            return null
        }
        return loadedFrames[reference]
    }

    private fun loadedCloudFrameForSelected(
        timestampMillis: Long?,
        cloudTimeline: RadarTimeline = _uiState.value.cloudTimeline
    ): RadarBitmapFrame? {
        return loadedCloudFrameFor(closestCloudReference(timestampMillis, cloudTimeline))
    }

    private fun loadedLightningFrameForSelected(
        timestampMillis: Long?,
        lightningTimeline: RadarTimeline = _uiState.value.lightningTimeline
    ): RadarBitmapFrame? {
        return loadedLightningFrameFor(closestReference(timestampMillis, lightningTimeline))
    }

    private fun loadedLightningFrameFor(reference: RadarFrameReference?): RadarBitmapFrame? {
        if (reference == null) {
            return null
        }
        return loadedLightningFrames[reference]
    }

    private fun rememberLoadedLightningFrame(frame: RadarBitmapFrame) {
        loadedLightningFrames[frame.reference] = frame
        _uiState.update {
            it.copy(decodedLightningFrameTimestamps = loadedLightningFrames.keys.map { it.timestampMillis }.toSet())
        }
    }

    private fun loadedCloudFrameFor(reference: RadarFrameReference?): RadarBitmapFrame? {
        if (reference == null) {
            return null
        }
        return loadedCloudFrames[reference]
    }

    private fun rememberLoadedCloudFrame(frame: RadarBitmapFrame) {
        loadedCloudFrames[frame.reference] = frame
        _uiState.update { it.copy(decodedCloudFrameTimestamps = loadedCloudFrames.keys.map { it.timestampMillis }.toSet()) }
    }

    private fun closestCloudReference(
        timestampMillis: Long?,
        cloudTimeline: RadarTimeline
    ): RadarFrameReference? = closestReference(timestampMillis, cloudTimeline)

    private fun closestReference(
        timestampMillis: Long?,
        timeline: RadarTimeline
    ): RadarFrameReference? {
        if (timestampMillis == null || timeline.frames.isEmpty()) {
            return null
        }
        return timeline.frames.minBy { reference ->
            kotlin.math.abs(reference.timestampMillis - timestampMillis)
        }
    }

    private fun rememberLoadedFrame(frame: RadarBitmapFrame) {
        loadedFrames[frame.reference] = frame
        _uiState.update { it.copy(decodedRainFrameTimestamps = loadedFrames.keys.map { it.timestampMillis }.toSet()) }
    }

    private fun retainNavigationWindow() {
        val state = _uiState.value
        val retained = framePrefetchPlan(state, state.selectedFrameIndex).retainedItems
        loadedFrames.retain(retained.filter { it.layerKey == RadarBackend.PRECIPITATION_LAYER }.toSet())
        loadedCloudFrames.retain(retained.filter { it.layerKey == RadarBackend.CLOUD_LAYER }.toSet())
        loadedLightningFrames.retain(retained.filter { it.layerKey in RadarBackend.lightningLayers }.toSet())
        radarRepository.resizeCache(retained.size.coerceAtLeast(1))
        _uiState.update {
            it.copy(
                decodedRainFrameTimestamps = loadedFrames.keys.map { it.timestampMillis }.toSet(),
                decodedCloudFrameTimestamps = loadedCloudFrames.keys.map { it.timestampMillis }.toSet(),
                decodedLightningFrameTimestamps = loadedLightningFrames.keys.map { it.timestampMillis }.toSet()
            )
        }
    }

    private fun updateMapCamera(transform: (MapCamera) -> MapCamera) {
        var updatedCamera: MapCamera? = null
        _uiState.update { state ->
            val nextCamera = transform(state.mapCamera)
            updatedCamera = nextCamera
            state.copy(mapCamera = nextCamera)
        }
        updatedCamera?.let(::persistMapCamera)
    }

    private fun persistMapCamera(camera: MapCamera) {
        mapCameraPreferenceStore.save(camera)
        hasSavedMapCenter = true
    }

    private companion object {
        const val ANIMATION_FRAME_DELAY_MILLIS = 350L
        const val LONG_PRESS_LOCATION_FORCE_CENTER_MILLIS = 5_000L
        const val MAX_LAYER_MENU_SCROLL_DP = 76f
        const val MENU_SCROLL_DP_PER_TICK = 18f
        const val MAX_VISUAL_LAYER_SEGMENTS = 24
        const val BROAD_FORECAST_TIMESTEP_MILLIS = 60 * 60 * 1000L
        const val RADIAL_DEGREES_PER_STEP = 24f
        const val ROTARY_TICKS_PER_STEP = 0.15f
        const val TIMELINE_LOAD_ATTEMPTS = 3
        const val TIMELINE_RETRY_DELAY_MILLIS = 450L
        const val ZOOM_SWIPE_PIXELS_PER_STEP = 42f
        const val ROTARY_CLOSE_COOLDOWN_MILLIS = 500L
    }
}

internal fun selectedIndexAfterTimelineRefresh(
    timeline: RadarTimeline,
    previousTimestamp: Long?,
    selectNow: Boolean
): Int {
    if (selectNow || previousTimestamp == null || timeline.frames.isEmpty()) {
        return timeline.nowFrameIndex
    }
    return timeline.frames.indices.minBy { index ->
        kotlin.math.abs(timeline.frames[index].timestampMillis - previousTimestamp)
    }
}
