package com.navblind.service.location

import android.util.Log
import com.navblind.BuildConfig
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
 * GPS, FusedLocationProvider, ARCore Geospatial, IMU를 결합하여
 * 최적의 위치 및 방위각 정보를 제공하는 서비스입니다.
 *
 * 위치 융합 (Kalman Filter + PDR):
 * - GPS/FusedLocation: KF 보정 소스 (1Hz)
 * - ARCore Geospatial (VPS): 도시 환경에서 추가 KF 보정
 * - PDR: 걸음 감지마다 KF 예측 (~3~5Hz), GPS 주기 사이의 위치 갱신
 *
 * 방위각 융합:
 * - VPS heading: 정확하지만 낮은 업데이트 빈도
 * - IMU heading: 빠른 응답, drift 있음
 * - Kalman Filter로 두 소스를 융합
 */
@Singleton
class LocationFusionService @Inject constructor(
    private val locationService: LocationService,
    private val geospatialService: GeospatialService,
    private val headingFusionService: HeadingFusionService,
    private val imuSensorService: IMUSensorService
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

    private var lastFusedHeading: FusedHeadingData? = null

    // Position Kalman Filter 인스턴스
    private val positionKF = PositionKalmanFilter()

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
                    // 권한 없음 등으로 실패 시 isTracking 리셋 → 권한 허용 후 재시도 가능
                    _isTracking.value = false
                }
                .collect { position ->
                    _gpsPosition.value = position  // TODO: 데모 후 삭제
                    if (!positionKF.isInitialized) {
                        positionKF.initialize(
                            position.coordinate.latitude,
                            position.coordinate.longitude
                        )
                    } else {
                        positionKF.correct(
                            position.coordinate.latitude,
                            position.coordinate.longitude,
                            position.accuracy
                        )
                    }
                    emitKFPosition()
                }
        }

        // ARCore Geospatial 초기화 시도
        if (geospatialService.checkAvailability()) {
            // 디버그: 녹화된 session.mp4가 있으면 Playback 모드, 없으면 Live 모드
            if (BuildConfig.DEBUG) {
                val datasetFile = geospatialService.findLatestDatasetFile()
                if (datasetFile != null) {
                    Log.i(TAG, "ARCore Playback 모드: $datasetFile")
                    geospatialService.startPlayback(datasetFile)
                } else {
                    Log.d(TAG, "ARCore Live 모드 (session.mp4 없음)")
                    geospatialService.startLive()
                }
            } else {
                geospatialService.startLive()
            }

            scope.launch {
                geospatialService.geospatialPosition
                    .filterNotNull()
                    .collect { position ->
                        _vpsPosition.value = position  // TODO: 데모 후 삭제
                        positionKF.correct(
                            position.coordinate.latitude,
                            position.coordinate.longitude,
                            position.accuracy
                        )
                        emitKFPosition()
                    }
            }
        }

        // VPS & IMU 융합 Heading 시작
        // Heading은 캐시만 해두고 position 재계산을 트리거하지 않음.
        // GPS/VPS 위치 업데이트 시 최신 heading이 자동으로 붙음.
        headingFusionService.startFusion()

        scope.launch {
            headingFusionService.fusedHeading
                .filterNotNull()
                .collect { headingData ->
                    lastFusedHeading = headingData
                }
        }

        // PDR: 걸음 감지마다 KF 예측 → GPS 주기 사이를 채움
        scope.launch {
            imuSensorService.stepDetected.collect {
                val heading = lastFusedHeading?.heading ?: return@collect
                if (!positionKF.isInitialized) return@collect
                positionKF.predictWithStep(PositionKalmanFilter.STEP_LENGTH, heading)
                emitKFPosition()
            }
        }
    }

    /**
     * 위치 추적을 중지합니다.
     */
    fun stopTracking() {
        _isTracking.value = false
        geospatialService.release()
        headingFusionService.stopFusion()
        lastFusedHeading = null
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

    /**
     * KF 상태를 FusedPosition으로 변환하여 방출합니다.
     */
    private fun emitKFPosition() {
        if (!positionKF.isInitialized) return
        val headingData = lastFusedHeading
        _fusedPosition.value = FusedPosition(
            coordinate = Coordinate(positionKF.currentLat, positionKF.currentLng),
            accuracy = positionKF.accuracyMeters,
            altitude = null,
            heading = headingData?.heading,
            source = PositionSource.FUSED_LOCATION,
            fusedHeading = headingData?.heading,
            headingConfidence = headingData?.confidence,
            isMoving = headingData?.isMoving ?: false
        )
    }

    /**
     * 현재 사용자가 바라보는 방향(heading)을 반환합니다.
     */
    fun getCurrentHeading(): Float? {
        return lastFusedHeading?.heading
            ?: _fusedPosition.value?.heading
    }

    /**
     * 사용자가 이동 중인지 확인합니다.
     */
    fun isUserMoving(): Boolean {
        return lastFusedHeading?.isMoving ?: false
    }

    companion object {
        private const val TAG = "LocationFusionService"
        private const val POSITION_CACHE_DURATION = 5000L // 5 seconds
    }
}
