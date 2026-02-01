package com.navblind.service.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.navblind.domain.model.Coordinate
import com.navblind.domain.model.FusedPosition
import com.navblind.domain.model.PositionSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class LocationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient
) {

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        LOCATION_UPDATE_INTERVAL
    ).apply {
        setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL)
        setMinUpdateDistanceMeters(MIN_UPDATE_DISTANCE)
    }.build()

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun getLastKnownLocation(): FusedPosition? {
        if (!hasLocationPermission()) return null

        return suspendCancellableCoroutine { continuation ->
            try {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            continuation.resume(location.toFusedPosition())
                        } else {
                            continuation.resume(null)
                        }
                    }
                    .addOnFailureListener { exception ->
                        continuation.resumeWithException(exception)
                    }
            } catch (e: SecurityException) {
                continuation.resumeWithException(e)
            }
        }
    }

    fun getLocationUpdates(): Flow<FusedPosition> = callbackFlow {
        if (!hasLocationPermission()) {
            close(SecurityException("Location permission not granted"))
            return@callbackFlow
        }

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(location.toFusedPosition())
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }

        awaitClose {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun Location.toFusedPosition(): FusedPosition {
        return FusedPosition(
            coordinate = Coordinate(latitude, longitude),
            accuracy = accuracy,
            altitude = if (hasAltitude()) altitude else null,
            heading = if (hasBearing()) bearing else null,
            source = PositionSource.FUSED_LOCATION,
            timestamp = time
        )
    }

    companion object {
        private const val LOCATION_UPDATE_INTERVAL = 2000L // 2 seconds
        private const val FASTEST_UPDATE_INTERVAL = 1000L // 1 second
        private const val MIN_UPDATE_DISTANCE = 2f // 2 meters
    }
}
