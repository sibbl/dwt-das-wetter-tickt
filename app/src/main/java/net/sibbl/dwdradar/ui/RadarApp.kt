package net.sibbl.dwdradar.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun RadarApp(
    viewModel: RadarViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val locationPermissionGranted = remember(context) {
        hasAnyLocationPermission(context)
    }
    var permissionPromptDismissed by rememberSaveable {
        mutableStateOf(locationPermissionGranted)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        if (granted) {
            permissionPromptDismissed = true
            viewModel.onLocationPermissionChanged(true)
        }
    }

    LaunchedEffect(Unit) {
        if (locationPermissionGranted) {
            viewModel.onLocationPermissionChanged(true)
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp
    val maxScrollDp = (screenHeightDp - 142f).coerceAtLeast(0f)

    RadarScreen(
        uiState = uiState,
        showLocationPrompt = !locationPermissionGranted && !permissionPromptDismissed,
        onRequestLocationPermission = {
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        },
        onDismissLocationPrompt = { permissionPromptDismissed = true },
        onTogglePlayback = viewModel::togglePlayback,
        onCycleZoom = viewModel::cycleZoom,
        onResetToNow = viewModel::resetToNowAndCenter,
        onPanMap = viewModel::panMap,
        onRotary = viewModel::scrubByRotary,
        onRadialScroll = viewModel::scrubByRadialGesture,
        onZoomSwipe = viewModel::zoomBySwipe,
        onGestureEnd = viewModel::finishGestureInput,
        onRefreshData = viewModel::refreshRadarData,
        onToggleRainLayer = viewModel::toggleRainLayer,
        onToggleCloudLayer = viewModel::toggleCloudLayer,
        onLayerMenuExpandedChange = viewModel::setLayerMenuOpen,
        onLayerMenuScroll = { delta -> viewModel.scrollLayerMenu(delta, maxScrollDp) },
        onLayerMenuScrollChange = { scroll -> viewModel.setLayerMenuScroll(scroll, maxScrollDp) },
        onRetry = viewModel::retry
    )
}

private fun hasAnyLocationPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
