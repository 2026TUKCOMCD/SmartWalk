package com.navblind.service.location

import android.util.Log
import com.navblind.domain.model.Coordinate
import com.navblind.domain.model.FusedPosition
import com.navblind.domain.model.PositionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPS, FusedLocationProvider, ARCore Geospatial을 결합하여
 * 최적의 위치 정보를 제공하는 서비스입니다.
 */
@Singleton
class LocationFusionService @Inject constructor(
    private val locationService: LocationService,
    private val geospatialService: GeospatialService
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _fusedPosition = MutableStateFlow<FusedPosition?>(null)
    val fusedPosition: StateFlow<FusedPosition?> = _fusedPosition.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    // TODO: 데모 후 삭제 - GPS/VPS 위치를 별도로 노출 (디버그용)
    private val _gpsPosition = MutableStateFlow<FusedPosition?>(null)
    val gpsPosition: StateFlow<FusedPosition?> = _gpsPosition.asStateFlow()

    private val _vpsPosition = MutableStateFlow<FusedPosition?>(null)
    val vpsPosition: StateFlow<FusedPosition?> = _vpsPosition.asStateFlow()
    // TODO: 데모 후 삭제 끝

    private var lastGpsPosition: FusedPosition? = null
    private var lastGeospatialPosition: FusedPosition? = null

    /**
     * 위치 추적을 시작합니다.
     */
    fun startTracking() {
        if (_isTracking.value) return

        _isTracking.value = true
        Log.d(TAG, "Starting location tracking")

        // GPS/FusedLocation 추적 시작
        scope.launch {
            locationService.getLocationUpdates()
                .catch { e ->
                    Log.e(TAG, "Location updates error", e)
                }
                .collect { position ->
                    lastGpsPosition = position
                    _gpsPosition.value = position  // TODO: 데모 후 삭제
                    updateFusedPosition()
                }
        }

        // ARCore Geospatial 초기화 시도
        if (geospatialService.checkAvailability()) {
            geospatialService.initialize()

            scope.launch {
                geospatialService.geospatialPosition
                    .filterNotNull()
                    .collect { position ->
                        lastGeospatialPosition = position
                        _vpsPosition.value = position  // TODO: 데모 후 삭제
                        updateFusedPosition()
                    }
            }
        }
    }

    /**
     * 위치 추적을 중지합니다.
     */
    fun stopTracking() {
        _isTracking.value = false
        geospatialService.release()
        Log.d(TAG, "Stopped location tracking")
    }

    /**
     * 현재 가장 정확한 위치를 반환합니다.
     */
    suspend fun getCurrentPosition(): FusedPosition? {
        // 캐시된 위치가 있으면 사용
        _fusedPosition.value?.let { cached ->
            val age = System.currentTimeMillis() - cached.timestamp
            if (age < POSITION_CACHE_DURATION) {
                return cached
            }
        }

        // 새로운 위치 요청
        return locationService.getLastKnownLocation()
    }

    private fun updateFusedPosition() {
        val gps = lastGpsPosition
        val geospatial = lastGeospatialPosition

        // 융합 로직: ARCore Geospatial이 더 정확하면 우선 사용
        val bestPosition = when {
            // Geospatial이 GPS보다 정확하면 Geospatial 사용
            geospatial != null && gps != null &&
                    geospatial.accuracy < gps.accuracy &&
                    isRecent(geospatial) -> {
                Log.d(TAG, "Using Geospatial position (${geospatial.accuracy}m)")
                geospatial
            }

            // Geospatial만 있고 최신이면 사용
            geospatial != null && gps == null && isRecent(geospatial) -> {
                geospatial
            }

            // GPS가 있으면 사용
            gps != null -> {
                // Geospatial이 있으면 가중 평균 계산
                if (geospatial != null && isRecent(geospatial)) {
                    fusePositions(gps, geospatial)
                } else {
                    gps
                }
            }

            // 둘 다 없으면 null
            else -> null
        }

        bestPosition?.let {
            _fusedPosition.value = it
            Log.d(TAG, "Fused position updated: ${it.coordinate}, " +
                    "accuracy: ${it.accuracy}m, source: ${it.source}")
        }
    }

    /**
     * 두 위치를 가중 평균으로 융합합니다.
     * 정확도가 높을수록 가중치가 높습니다.
     */
    private fun fusePositions(gps: FusedPosition, geospatial: FusedPosition): FusedPosition {
        // 정확도의 역수를 가중치로 사용 (정확도가 낮을수록 높은 가중치)
        val gpsWeight = 1.0 / gps.accuracy
        val geospatialWeight = 1.0 / geospatial.accuracy
        val totalWeight = gpsWeight + geospatialWeight

        val fusedLat = (gps.coordinate.latitude * gpsWeight +
                geospatial.coordinate.latitude * geospatialWeight) / totalWeight
        val fusedLng = (gps.coordinate.longitude * gpsWeight +
                geospatial.coordinate.longitude * geospatialWeight) / totalWeight

        // 융합된 정확도 계산 (대략적인 추정)
        val fusedAccuracy = minOf(gps.accuracy, geospatial.accuracy)

        return FusedPosition(
            coordinate = Coordinate(fusedLat, fusedLng),
            accuracy = fusedAccuracy,
            altitude = geospatial.altitude ?: gps.altitude,
            heading = geospatial.heading ?: gps.heading,
            source = if (geospatial.accuracy < gps.accuracy) {
                PositionSource.ARCORE_GEOSPATIAL
            } else {
                PositionSource.FUSED_LOCATION
            }
        )
    }

    private fun isRecent(position: FusedPosition): Boolean {
        val age = System.currentTimeMillis() - position.timestamp
        return age < POSITION_STALE_THRESHOLD
    }

    companion object {
        private const val TAG = "LocationFusionService"
        private const val POSITION_CACHE_DURATION = 5000L // 5 seconds
        private const val POSITION_STALE_THRESHOLD = 10000L // 10 seconds
    }
}
