package net.sibbl.dwdradar.ui

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.sibbl.dwdradar.data.radar.RadarBackend
import net.sibbl.dwdradar.data.radar.RadarBitmapFrame
import net.sibbl.dwdradar.data.radar.RadarRepository
import net.sibbl.dwdradar.location.LocationRepository
import net.sibbl.dwdradar.map.GermanyOutlineRepository
import net.sibbl.dwdradar.map.MapViewport
import net.sibbl.dwdradar.model.MapCamera
import net.sibbl.dwdradar.model.RadarFrameReference
import net.sibbl.dwdradar.model.RadarTimeline
import net.sibbl.dwdradar.model.ZoomPreset
import net.sibbl.dwdradar.storage.RadarLayerPreferenceStore
import net.sibbl.dwdradar.storage.MapCameraPreferenceStore
import net.sibbl.dwdradar.storage.UserLocationPreferenceStore
import java.util.LinkedHashMap
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
    private val loadedFrames = object : LinkedHashMap<Long, RadarBitmapFrame>(
        MAX_LOADED_FRAMES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, RadarBitmapFrame>?): Boolean {
            return size > MAX_LOADED_FRAMES
        }
    }
    private val loadedCloudFrames = object : LinkedHashMap<Long, RadarBitmapFrame>(
        MAX_LOADED_CLOUD_FRAMES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, RadarBitmapFrame>?): Boolean {
            return size > MAX_LOADED_CLOUD_FRAMES
        }
    }

    private val _uiState = MutableStateFlow(
        radarLayerPreferenceStore.restore().let { layerPreferences ->
            RadarUiState(
                mapCamera = mapCameraPreferenceStore.restoreMapCamera(),
                rainLayerVisible = layerPreferences.rainVisible,
                cloudLayerVisible = layerPreferences.cloudVisible,
                userLocation = userLocationPreferenceStore.restore()
            )
        }
    )
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    private var frameJob: Job? = null
    private var cloudFrameJob: Job? = null
    private var prefetchJob: Job? = null
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
                errorMessage = null
            )
        }
        loadFrameAt(timeline.nowFrameIndex, force = true)
        loadCloudFrameForSelected(force = true)
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
        if (shouldLoadCloudTimeline) {
            refreshCloudTimeline()
        } else if (shouldLoadCloudFrame) {
            loadCloudFrameForSelected()
        }
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
                mapCamera = updatedCamera,
                errorMessage = null
            )
        }
        persistMapCamera(updatedCamera)
        loadFrameAt(nowIndex, force = true)
        loadCloudFrameForSelected(force = true)
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

    private fun refreshTimeline(forceRefresh: Boolean = false) {
        viewModelScope.launch {
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
                    precipitationTimeline to cloudTimeline
                }.onSuccess { (precipitationTimeline, cloudTimeline) ->
                    applyTimelines(
                        timeline = precipitationTimeline,
                        cloudTimeline = cloudTimeline
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
                    val newProgress = initialFrameLoadProgress(state.timeline, cloudTimeline)
                    state.copy(
                        cloudTimeline = cloudTimeline,
                        frameLoadProgress = state.frameLoadProgress + newProgress,
                        selectedCloudFrame = loadedCloudFrameForSelected(
                            timestampMillis = selectedTimestamp,
                            cloudTimeline = cloudTimeline
                        )
                    )
                }
                loadCloudFrameForSelected(force = true)
            }
        }
    }

    private fun applyTimelines(timeline: RadarTimeline, cloudTimeline: RadarTimeline) {
        val previousState = _uiState.value
        val previousTimestamp = previousState.timeline.frames
            .getOrNull(previousState.selectedFrameIndex)
            ?.timestampMillis
        val preservedIndex = if (previousTimestamp != null && timeline.frames.isNotEmpty()) {
            timeline.frames.indices.minBy { index ->
                kotlin.math.abs(timeline.frames[index].timestampMillis - previousTimestamp)
            }
        } else {
            timeline.nowFrameIndex
        }
        _uiState.update { state ->
            val reference = timeline.frames.getOrNull(preservedIndex)
            state.copy(
                isLoading = false,
                timeline = timeline,
                cloudTimeline = cloudTimeline,
                selectedFrameIndex = preservedIndex,
                selectedFrame = loadedFrameFor(reference),
                selectedCloudFrame = loadedCloudFrameForSelected(
                    timestampMillis = reference?.timestampMillis,
                    cloudTimeline = cloudTimeline
                ),
                frameLoadProgress = initialFrameLoadProgress(timeline, cloudTimeline),
                errorMessage = null
            )
        }
        loadFrameAt(preservedIndex)
        loadCloudFrameForSelected()
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
                errorMessage = null
            )
        }
        loadFrameAt(clampedIndex)
        loadCloudFrameForSelected()
        if (pausePlayback) {
            scheduleSmartPrefetch(clampedIndex)
        }
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
        prefetchJob?.cancel()
        val state = _uiState.value
        val timeline = state.timeline
        if (timeline.frames.isEmpty()) {
            return
        }
        val plan = buildAssetPrefetchPlan(timeline, centerIndex)
        val framePlan = buildFramePrefetchPlan(timeline, centerIndex)

        val cloudPlan = mutableListOf<RadarFrameReference>()
        val cloudFramePlan = mutableListOf<RadarFrameReference>()
        if (state.cloudLayerVisible && state.cloudTimeline.frames.isNotEmpty()) {
            framePlan.forEach { rainRef ->
                closestCloudReference(rainRef.timestampMillis, state.cloudTimeline)?.let { cloudRef ->
                    if (loadedCloudFrameFor(cloudRef) == null && !cloudFramePlan.contains(cloudRef)) {
                        cloudFramePlan.add(cloudRef)
                    }
                }
            }
            plan.forEach { rainRef ->
                closestCloudReference(rainRef.timestampMillis, state.cloudTimeline)?.let { cloudRef ->
                    if (!radarRepository.isAssetCached(cloudRef) && !cloudPlan.contains(cloudRef)) {
                        cloudPlan.add(cloudRef)
                    }
                }
            }
        }

        if (plan.isEmpty() && framePlan.isEmpty() && cloudPlan.isEmpty() && cloudFramePlan.isEmpty()) {
            return
        }
        prefetchJob = viewModelScope.launch {
            delay(PREFETCH_START_DELAY_MILLIS)
            framePlan.forEachIndexed { order, reference ->
                if (order >= IMMEDIATE_PREFETCH_FRAME_COUNT) {
                    delay(BACKGROUND_FRAME_DECODE_DELAY_MILLIS)
                }
                if (loadedFrameFor(reference) != null) {
                    return@forEachIndexed
                }
                runCatching {
                    radarRepository.loadFrame(reference) { loadProgress ->
                        markAssetProgress(reference.assetPath, loadProgress)
                    }
                }.onSuccess { frame ->
                    rememberLoadedFrame(frame)
                    markAssetProgress(frame.reference.assetPath, 1f)
                }.onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                }
            }
            cloudFramePlan.forEachIndexed { order, reference ->
                if (order >= IMMEDIATE_PREFETCH_FRAME_COUNT) {
                    delay(BACKGROUND_FRAME_DECODE_DELAY_MILLIS)
                }
                if (loadedCloudFrameFor(reference) != null) {
                    return@forEachIndexed
                }
                runCatching {
                    radarRepository.loadFrame(reference) { loadProgress ->
                        markAssetProgress(reference.assetPath, loadProgress)
                    }
                }.onSuccess { frame ->
                    rememberLoadedCloudFrame(frame)
                    markAssetProgress(frame.reference.assetPath, 1f)
                }.onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                }
            }
            plan.forEachIndexed { order, reference ->
                if (order >= IMMEDIATE_PREFETCH_ASSET_COUNT) {
                    delay(BACKGROUND_PREFETCH_DELAY_MILLIS)
                }
                val progress = _uiState.value.frameLoadProgress[reference.timestampMillis] ?: 0f
                if (progress >= 1f) {
                    return@forEachIndexed
                }
                runCatching {
                    radarRepository.prefetchAsset(reference) { loadProgress ->
                        markAssetProgress(reference.assetPath, loadProgress)
                    }
                }.onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                }
            }
            cloudPlan.forEachIndexed { order, reference ->
                if (order >= IMMEDIATE_PREFETCH_ASSET_COUNT) {
                    delay(BACKGROUND_PREFETCH_DELAY_MILLIS)
                }
                val progress = _uiState.value.frameLoadProgress[reference.timestampMillis] ?: 0f
                if (progress >= 1f) {
                    return@forEachIndexed
                }
                runCatching {
                    radarRepository.prefetchAsset(reference) { loadProgress ->
                        markAssetProgress(reference.assetPath, loadProgress)
                    }
                }.onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                }
            }
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

    private fun buildFramePrefetchPlan(
        timeline: RadarTimeline,
        centerIndex: Int
    ): List<RadarFrameReference> {
        return buildLayerOrderedFrameReferences(
            timeline = timeline,
            centerIndex = centerIndex,
            includeCenter = false
        )
            .take(MAX_PREFETCH_DECODE_FRAMES)
            .distinctBy { it.timestampMillis }
    }

    private fun buildAssetPrefetchPlan(
        timeline: RadarTimeline,
        centerIndex: Int
    ): List<RadarFrameReference> {
        val selectedReference = timeline.frameAt(centerIndex)
        return buildLayerOrderedFrameReferences(
            timeline = timeline,
            centerIndex = centerIndex,
            includeCenter = true
        )
            .filter { it.assetPath != selectedReference.assetPath }
            .distinctBy { it.assetPath }
    }

    private fun buildLayerOrderedFrameReferences(
        timeline: RadarTimeline,
        centerIndex: Int,
        includeCenter: Boolean
    ): List<RadarFrameReference> {
        if (timeline.frames.isEmpty() || centerIndex !in timeline.frames.indices) {
            return emptyList()
        }
        val layers = visualTimelineLayers(timeline)
        val selectedLayerIndex = layers.indexOfFirst { layer ->
            centerIndex in layer.startIndex until layer.endExclusive
        }
        if (selectedLayerIndex == -1) {
            return emptyList()
        }

        return buildList {
            addAll(layerFrameIndices(layers[selectedLayerIndex], centerIndex, includeCenter))
            var distance = 1
            while (size < timeline.frames.size && (selectedLayerIndex - distance >= 0 || selectedLayerIndex + distance < layers.size)) {
                val futureLayerIndex = selectedLayerIndex + distance
                if (futureLayerIndex < layers.size) {
                    addAll(layerFrameIndices(layers[futureLayerIndex], centerIndex, includeCenter = true))
                }
                val pastLayerIndex = selectedLayerIndex - distance
                if (pastLayerIndex >= 0) {
                    addAll(layerFrameIndices(layers[pastLayerIndex], centerIndex, includeCenter = true))
                }
                distance++
            }
        }
            .filter { index -> index in timeline.frames.indices }
            .map(timeline.frames::get)
    }

    private fun layerFrameIndices(
        layer: VisualTimelineLayerRange,
        centerIndex: Int,
        includeCenter: Boolean
    ): List<Int> {
        if (centerIndex in layer.startIndex until layer.endExclusive) {
            return buildList {
                if (includeCenter) {
                    add(centerIndex)
                }
                var distance = 1
                while (centerIndex - distance >= layer.startIndex || centerIndex + distance < layer.endExclusive) {
                    val futureIndex = centerIndex + distance
                    if (futureIndex < layer.endExclusive) {
                        add(futureIndex)
                    }
                    val pastIndex = centerIndex - distance
                    if (pastIndex >= layer.startIndex) {
                        add(pastIndex)
                    }
                    distance++
                }
            }
        }

        return if (layer.startIndex > centerIndex) {
            (layer.startIndex until layer.endExclusive).toList()
        } else {
            (layer.endExclusive - 1 downTo layer.startIndex).toList()
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

    private fun initialFrameLoadProgress(timeline: RadarTimeline, cloudTimeline: RadarTimeline): Map<Long, Float> {
        val allFrames = timeline.frames + cloudTimeline.frames
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
            val updates = rainUpdates + cloudUpdates
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
        return loadedFrames[reference.timestampMillis]
            ?.takeIf { it.reference == reference }
    }

    private fun loadedCloudFrameForSelected(
        timestampMillis: Long?,
        cloudTimeline: RadarTimeline = _uiState.value.cloudTimeline
    ): RadarBitmapFrame? {
        return loadedCloudFrameFor(closestCloudReference(timestampMillis, cloudTimeline))
    }

    private fun loadedCloudFrameFor(reference: RadarFrameReference?): RadarBitmapFrame? {
        if (reference == null) {
            return null
        }
        return loadedCloudFrames[reference.timestampMillis]
            ?.takeIf { it.reference == reference }
    }

    private fun rememberLoadedCloudFrame(frame: RadarBitmapFrame) {
        loadedCloudFrames[frame.reference.timestampMillis] = frame
    }

    private fun closestCloudReference(
        timestampMillis: Long?,
        cloudTimeline: RadarTimeline
    ): RadarFrameReference? {
        if (timestampMillis == null || cloudTimeline.frames.isEmpty()) {
            return null
        }
        return cloudTimeline.frames.minBy { reference ->
            kotlin.math.abs(reference.timestampMillis - timestampMillis)
        }
    }

    private fun rememberLoadedFrame(frame: RadarBitmapFrame) {
        loadedFrames[frame.reference.timestampMillis] = frame
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
        const val BACKGROUND_FRAME_DECODE_DELAY_MILLIS = 80L
        const val BACKGROUND_PREFETCH_DELAY_MILLIS = 900L
        const val IMMEDIATE_PREFETCH_FRAME_COUNT = 4
        const val IMMEDIATE_PREFETCH_ASSET_COUNT = 2
        const val LONG_PRESS_LOCATION_FORCE_CENTER_MILLIS = 5_000L
        const val MAX_LAYER_MENU_SCROLL_DP = 76f
        const val MAX_LOADED_CLOUD_FRAMES = 18
        const val MAX_LOADED_FRAMES = 48
        const val MENU_SCROLL_DP_PER_TICK = 18f
        const val MAX_PREFETCH_DECODE_FRAMES = MAX_LOADED_FRAMES - 1
        const val MAX_VISUAL_LAYER_SEGMENTS = 24
        const val PREFETCH_START_DELAY_MILLIS = 150L
        const val BROAD_FORECAST_TIMESTEP_MILLIS = 60 * 60 * 1000L
        const val RADIAL_DEGREES_PER_STEP = 24f
        const val ROTARY_TICKS_PER_STEP = 0.15f
        const val TIMELINE_LOAD_ATTEMPTS = 3
        const val TIMELINE_RETRY_DELAY_MILLIS = 450L
        const val ZOOM_SWIPE_PIXELS_PER_STEP = 42f
        const val ROTARY_CLOSE_COOLDOWN_MILLIS = 500L
    }
}
