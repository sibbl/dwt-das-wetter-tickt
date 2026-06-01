package de.sibbl.dwdradar.ui

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sibbl.dwdradar.data.radar.RadarBackend
import de.sibbl.dwdradar.data.radar.RadarBitmapFrame
import de.sibbl.dwdradar.data.radar.RadarRepository
import de.sibbl.dwdradar.location.LocationRepository
import de.sibbl.dwdradar.map.GermanyOutlineRepository
import de.sibbl.dwdradar.map.MapViewport
import de.sibbl.dwdradar.model.MapCamera
import de.sibbl.dwdradar.model.RadarFrameReference
import de.sibbl.dwdradar.model.RadarTimeline
import de.sibbl.dwdradar.model.ZoomPreset
import de.sibbl.dwdradar.storage.MapCameraPreferenceStore
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

    private val _uiState = MutableStateFlow(
        RadarUiState(
            mapCamera = mapCameraPreferenceStore.restoreMapCamera()
        )
    )
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    private var frameJob: Job? = null
    private var prefetchJob: Job? = null
    private var longPressLocationRefreshJob: Job? = null
    private var longPressLocationRefreshGeneration = 0
    private var hasSavedMapCenter = mapCameraPreferenceStore.hasSavedCenter()
    private var hasCenteredOnLocation = hasSavedMapCenter

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
                errorMessage = null
            )
        }
        loadFrameAt(timeline.nowFrameIndex, force = true)
        scheduleSmartPrefetch(timeline.nowFrameIndex)
    }

    fun onLocationPermissionChanged(granted: Boolean) {
        if (!granted) {
            return
        }
        viewModelScope.launch {
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
                mapCamera = updatedCamera,
                errorMessage = null
            )
        }
        persistMapCamera(updatedCamera)
        loadFrameAt(nowIndex, force = true)
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
                    radarRepository.loadTimeline(forceRefresh = forceRefresh || attempt > 0)
                }.onSuccess { timeline ->
                    applyTimeline(timeline)
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

    private fun applyTimeline(timeline: RadarTimeline) {
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
                selectedFrameIndex = preservedIndex,
                selectedFrame = loadedFrameFor(reference),
                frameLoadProgress = initialFrameLoadProgress(timeline),
                errorMessage = null
            )
        }
        loadFrameAt(preservedIndex)
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
                errorMessage = null
            )
        }
        loadFrameAt(clampedIndex)
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
        val timeline = _uiState.value.timeline
        if (timeline.frames.isEmpty()) {
            return
        }
        val plan = buildAssetPrefetchPlan(timeline, centerIndex)
        val framePlan = buildFramePrefetchPlan(timeline, centerIndex)
        if (plan.isEmpty() && framePlan.isEmpty()) {
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
        }
    }

    private fun buildFramePrefetchPlan(
        timeline: RadarTimeline,
        centerIndex: Int
    ): List<RadarFrameReference> {
        val centerReference = timeline.frameAt(centerIndex)
        val framesPerHour = (HOUR_MILLIS / centerReference.timeStepMillis.coerceAtLeast(1L))
            .toInt()
            .coerceAtLeast(1)
        val offsets = buildList {
            add(1)
            add(-1)
            add(framesPerHour)
            add(-framesPerHour)
            add(2)
            add(-2)
            repeat(NEXT_HOUR_PREFETCH_FRAMES) { step ->
                add(framesPerHour + step)
                add(-framesPerHour - step)
            }
            for (step in 3 until framesPerHour) {
                add(step)
                add(-step)
            }
        }
        return offsets
            .mapNotNull { offset ->
                val index = centerIndex + offset
                if (index in timeline.frames.indices) timeline.frameAt(index) else null
            }
            .distinctBy { it.timestampMillis }
    }

    private fun buildAssetPrefetchPlan(
        timeline: RadarTimeline,
        centerIndex: Int
    ): List<RadarFrameReference> {
        val assets = timeline.frames
            .distinctBy { it.assetPath }
            .sortedWith(compareBy<RadarFrameReference> { it.sectionStartMillis }.thenBy { it.assetPath })
        val selectedReference = timeline.frameAt(centerIndex)
        val selectedAssetIndex = assets.indexOfFirst { it.assetPath == selectedReference.assetPath }
        if (selectedAssetIndex == -1) {
            return emptyList()
        }

        val plan = mutableListOf<RadarFrameReference>()
        var distance = 1
        while (plan.size < assets.size - 1) {
            val futureIndex = selectedAssetIndex + distance
            if (futureIndex in assets.indices) {
                plan += assets[futureIndex]
            }
            val pastIndex = selectedAssetIndex - distance
            if (pastIndex in assets.indices) {
                plan += assets[pastIndex]
            }
            distance++
        }
        return plan
    }

    private fun initialFrameLoadProgress(timeline: RadarTimeline): Map<Long, Float> {
        val progressByAsset = timeline.frames
            .distinctBy { it.assetPath }
            .associate { reference ->
                reference.assetPath to if (radarRepository.isAssetCached(reference)) 1f else 0f
            }
        return timeline.frames.associate { reference ->
            reference.timestampMillis to (progressByAsset[reference.assetPath] ?: 0f)
        }
    }

    private fun markAssetProgress(assetPath: String, progress: Float) {
        val normalizedProgress = progress.coerceIn(0f, 1f)
        _uiState.update { state ->
            val updates = state.timeline.frames
                .filter { it.assetPath == assetPath }
                .associate { it.timestampMillis to normalizedProgress }
            if (updates.isEmpty()) {
                state
            } else {
                state.copy(frameLoadProgress = state.frameLoadProgress + updates)
            }
        }
    }

    private fun refreshLocationAfterLongPress(
        generation: Int,
        forceCenterUntilMillis: Long
    ) {
        longPressLocationRefreshJob?.cancel()
        longPressLocationRefreshJob = viewModelScope.launch {
            val location = locationRepository.currentLocation()
                ?.takeIf(RadarBackend.defaultBounds::contains)
                ?: return@launch
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
        }
    }

    private fun loadedFrameFor(reference: RadarFrameReference?): RadarBitmapFrame? {
        if (reference == null) {
            return null
        }
        return loadedFrames[reference.timestampMillis]
            ?.takeIf { it.reference == reference }
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
        const val HOUR_MILLIS = 60 * 60 * 1000L
        const val IMMEDIATE_PREFETCH_FRAME_COUNT = 4
        const val IMMEDIATE_PREFETCH_ASSET_COUNT = 2
        const val LONG_PRESS_LOCATION_FORCE_CENTER_MILLIS = 5_000L
        const val MAX_LOADED_FRAMES = 48
        const val NEXT_HOUR_PREFETCH_FRAMES = 4
        const val PREFETCH_START_DELAY_MILLIS = 150L
        const val RADIAL_DEGREES_PER_STEP = 24f
        const val ROTARY_TICKS_PER_STEP = 0.15f
        const val TIMELINE_LOAD_ATTEMPTS = 3
        const val TIMELINE_RETRY_DELAY_MILLIS = 450L
        const val ZOOM_SWIPE_PIXELS_PER_STEP = 42f
    }
}
