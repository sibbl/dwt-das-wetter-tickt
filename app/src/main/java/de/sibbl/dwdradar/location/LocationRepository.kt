package de.sibbl.dwdradar.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import de.sibbl.dwdradar.model.GeoPoint
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class LocationRepository(
    private val context: Context
) {
    private val locationManager: LocationManager =
        context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): GeoPoint? = withContext(Dispatchers.Main.immediate) {
        if (!hasPermission()) {
            return@withContext null
        }

        suspendCancellableCoroutine { continuation ->
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                    LocationManager.GPS_PROVIDER

                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                    LocationManager.NETWORK_PROVIDER

                locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) ->
                    LocationManager.PASSIVE_PROVIDER

                else -> {
                    continuation.resume(lastKnownLocation())
                    return@suspendCancellableCoroutine
                }
            }

            LocationManagerCompat.getCurrentLocation(
                locationManager,
                provider,
                CancellationSignal(),
                ContextCompat.getMainExecutor(context)
            ) { location: Location? ->
                continuation.resume(
                    location?.let {
                        GeoPoint(
                            latitude = it.latitude,
                            longitude = it.longitude
                        )
                    } ?: lastKnownLocation()
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): GeoPoint? {
        if (!hasPermission()) {
            return null
        }

        return locationManager.getProviders(true)
            .asSequence()
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?.let { location ->
                GeoPoint(
                    latitude = location.latitude,
                    longitude = location.longitude
                )
            }
    }
}
