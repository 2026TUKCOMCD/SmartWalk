package com.smartwalker.service.location

import android.util.Log
import com.smartwalker.BuildConfig
import com.smartwalker.data.local.BodyMetricsStore
import com.smartwalker.domain.model.Coordinate
import com.smartwalker.domain.model.FusedPosition
import com.smartwalker.domain.model.PositionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPS, FusedLocationProvider, IMU를 결합하여
 * 최적의 위치 및 방위각 정보를 제공하는 서비스입니다.
 *
 * 위치 융합 (Kalman Filter + PDR):
 * - GPS/FusedLocation: KF 보정 소스 (1Hz)
 * - PDR: 걸음 감지마다 KF 예측 (~3~5Hz), GPS 주기 사이의 위치 갱신
 * - VO: ESP32 영상 기반 변위 예측
 * - 맵매칭: 경로 위로 부드럽게 스냅
 *
 * 방위각 융합:
 * - IMU heading(회전벡터 + 자이로)을 Kalman Filter로 안정화
 */
@Singleton
class LocationFusionService @Inject constructor(
    private val locationService: LocationService,
    private val headingFusionService: HeadingFusionService,
    private val imuSensorService: IMUSensorService,
    private val visualOdometryService: VisualOdometryService,
    private val bodyMetricsStore: BodyMetricsStore,
    private val roadSnappingService: RoadSnappingService,
    private val trackRecorder: TrackRecorder
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _fusedPosition = MutableStateFlow<FusedPosition?>(null)
    val fusedPosition: StateFlow<FusedPosition?> = _fusedPosition.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    // TODO: 데모 후 삭제 - 원본 GPS 위치를 별도로 노출 (디버그용)
    private val _gpsPosition = MutableStateFlow<FusedPosition?>(null)
    val gpsPosition: StateFlow<FusedPosition?> = _gpsPosition.asStateFlow()
    // TODO: 데모 후 삭제 끝

    private var lastFusedHeading: FusedHeadingData? = null

    // Position Kalman Filter 인스턴스
    private val positionKF = PositionKalmanFilter()

    /**
     * 위치 추적을 시작합니다. (GPS + PDR + VO + IMU heading, 카메라 측위 없음)
     */
    fun startTracking() {
        if (!_isTracking.value) {
            _isTracking.value = true
            Log.d(TAG, "Starting location tracking")
            startCoreTracking()
            // 디버그: 원본 GPS vs 보정 동선을 GeoJSON으로 기록 (분석/튜닝용)
            if (BuildConfig.DEBUG) trackRecorder.start(gpsPosition, fusedPosition)
        }
    }

    /** GPS + Heading + PDR + VO 추적 시작 (카메라 불필요). */
    private fun startCoreTracking() {
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

        // PDR: 걸음 감지마다 KF 예측 → GPS 주기 사이를 채움.
        // heading 신뢰도가 낮으면(센서 워밍업·강한 자기 간섭) dead-reckoning을 생략해
        // 잘못된 방향으로 위치가 옆으로 새는 것을 방지한다 (GPS 보정은 계속 동작).
        // 보폭은 사용자 키 기반(BodyMetricsStore)으로 개인화.
        scope.launch {
            imuSensorService.stepDetected.collect {
                val headingData = lastFusedHeading ?: return@collect
                if (headingData.confidence < MIN_HEADING_CONFIDENCE) return@collect
                if (!positionKF.isInitialized) return@collect
                positionKF.predictWithStep(bodyMetricsStore.stepLengthMeters, headingData.heading)
                emitKFPosition()
            }
        }

        // VO: 비주얼 오도메트리 변위를 KF 예측에 반영
        // PDR 이후에 실행되므로 동일 걸음 주기의 두 번째 예측으로 작동.
        // 신뢰도가 낮을 때는 프로세스 노이즈를 키워 GPS 보정이 쉽게 덮어쓰도록 함.
        scope.launch {
            visualOdometryService.displacement
                .filterNotNull()
                .collect { vo ->
                    if (!vo.isSignificant) return@collect
                    val headingData = lastFusedHeading ?: return@collect
                    if (headingData.confidence < MIN_HEADING_CONFIDENCE) return@collect
                    if (!positionKF.isInitialized) return@collect

                    positionKF.predictWithDisplacement(
                        lateralMeters  = vo.lateralMeters,
                        forwardMeters  = vo.forwardMeters,
                        headingDeg     = headingData.heading,
                        confidenceScale = vo.confidence
                    )
                    emitKFPosition()
                    Log.d(TAG, "VO applied: lateral=%.3fm fwd=%.3fm conf=%.2f".format(
                        vo.lateralMeters, vo.forwardMeters, vo.confidence))
                }
        }
    }

    /**
     * 위치 추적을 중지합니다.
     */
    fun stopTracking() {
        _isTracking.value = false
        if (BuildConfig.DEBUG) trackRecorder.stop()
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
        applyMapMatching()
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
     * 맵 매칭: KF 위치를 경로에 부드럽게 스냅한다.
     *
     * GPS 횡오차(인도 폭~차도 침범)를 경로 기하로 보정한다. 다만:
     *  - MIN 미만(이미 경로 위): 손대지 않음.
     *  - MAX 초과(실제 이탈): 손대지 않음 — RouteDeviationDetector가 재탐색을 판단해야 하므로
     *    여기서 당기면 이탈을 가린다. MAX는 이탈 경고 임계(20m)보다 작게 둔다.
     *  - 그 사이 밴드: nudgeToward로 횡방향만 부드럽게 당김(공분산 불변 → GPS 보정 유지).
     *
     * 경로가 설정돼 있을 때만(=내비게이션 중) 동작한다. 검색/대기 화면에선 경로가 없어 no-op.
     */
    private fun applyMapMatching() {
        val proj = roadSnappingService.projectOntoRoute(
            Coordinate(positionKF.currentLat, positionKF.currentLng)
        ) ?: return
        if (proj.offsetMeters < MAP_MATCH_MIN_OFFSET_M) return
        if (proj.offsetMeters > MAP_MATCH_MAX_OFFSET_M) return
        positionKF.nudgeToward(proj.snapped.latitude, proj.snapped.longitude, MAP_MATCH_GAIN)
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
        // 이 신뢰도 미만이면 PDR/VO dead-reckoning을 생략 (heading 불확실 시 옆으로 새는 것 방지).
        // HeadingFusionService가 공분산을 유계로 유지하므로 정상 보행 중엔 이 값을 넘는다.
        private const val MIN_HEADING_CONFIDENCE = 0.3f

        // 맵 매칭(경로 스냅) 밴드 — 횡오차가 이 구간일 때만 경로로 당긴다.
        private const val MAP_MATCH_MIN_OFFSET_M = 1.0   // 이미 경로 위면 손대지 않음
        private const val MAP_MATCH_MAX_OFFSET_M = 15.0  // 이탈 경고(20m) 미만 — 실제 이탈은 가리지 않음
        private const val MAP_MATCH_GAIN = 0.2           // 한 업데이트에 횡오차의 20%를 부드럽게 당김
    }
}
