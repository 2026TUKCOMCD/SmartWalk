package com.navblind.service.location

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2

/**
 * VPS(Visual Positioning System)와 IMU 센서의 heading을 융합하여
 * 안정적이고 정확한 방위각을 산출하는 서비스입니다.
 *
 * 확장 칼만 필터(Extended Kalman Filter)를 사용하여:
 * - VPS heading: 낮은 주파수, 높은 정확도 (drift 없음)
 * - IMU heading: 높은 주파수, drift 있음 (자이로스코프)
 *
 * 두 소스를 융합하여 빠른 응답성과 안정성을 모두 확보합니다.
 */
@Singleton
class HeadingFusionService @Inject constructor(
    private val imuSensorService: IMUSensorService,
    private val geospatialService: GeospatialService
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 칼만 필터 상태
    private var kalmanState = KalmanState()

    // 융합된 heading 출력
    private val _fusedHeading = MutableStateFlow<FusedHeadingData?>(null)
    val fusedHeading: StateFlow<FusedHeadingData?> = _fusedHeading.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // 마지막 센서 값들
    private var lastIMUHeading: IMUHeadingData? = null
    private var lastVPSHeading: Float? = null
    private var lastVPSHeadingAccuracy: Float? = null
    private var lastUpdateTime: Long = 0

    /**
     * Heading 융합을 시작합니다.
     */
    fun startFusion() {
        if (_isRunning.value) return

        _isRunning.value = true
        Log.d(TAG, "Starting heading fusion")

        // IMU 센서 시작
        imuSensorService.startSensors()

        // IMU heading 수집
        scope.launch {
            imuSensorService.imuHeading.collect { imuData ->
                imuData?.let {
                    lastIMUHeading = it
                    updateFusedHeading()
                }
            }
        }

        // VPS heading 수집
        scope.launch {
            geospatialService.geospatialPosition.collect { position ->
                position?.heading?.let { heading ->
                    lastVPSHeading = heading
                    // VPS heading accuracy는 GeospatialService에서 headingAccuracy를 가져와야 함
                    // 현재는 고정값 사용
                    lastVPSHeadingAccuracy = DEFAULT_VPS_HEADING_ACCURACY
                    updateFusedHeading()
                }
            }
        }
    }

    /**
     * Heading 융합을 중지합니다.
     */
    fun stopFusion() {
        _isRunning.value = false
        imuSensorService.stopSensors()
        lastIMUHeading = null
        lastVPSHeading = null
        kalmanState = KalmanState()
        Log.d(TAG, "Stopped heading fusion")
    }

    private fun updateFusedHeading() {
        val imuData = lastIMUHeading ?: return
        val currentTime = System.currentTimeMillis()

        // 시간 간격 계산
        val dt = if (lastUpdateTime == 0L) {
            0.05f // 초기값 50ms
        } else {
            (currentTime - lastUpdateTime) / 1000f
        }
        lastUpdateTime = currentTime

        // 칼만 필터 예측 단계 (IMU 기반)
        predict(imuData, dt)

        // VPS 데이터가 있으면 보정 단계 수행
        lastVPSHeading?.let { vpsHeading ->
            lastVPSHeadingAccuracy?.let { accuracy ->
                correct(vpsHeading, accuracy)
            }
        }

        // 융합된 heading 출력
        val fusedData = FusedHeadingData(
            heading = normalizeAngle(kalmanState.heading),
            headingVelocity = kalmanState.headingVelocity,
            confidence = calculateConfidence(),
            source = determineSource(),
            imuHeading = imuData.heading,
            vpsHeading = lastVPSHeading,
            isMoving = imuData.isMoving,
            timestamp = currentTime
        )

        _fusedHeading.value = fusedData

        if (LOG_HEADING) {
            Log.d(TAG, "Fused heading: ${fusedData.heading}° " +
                    "(IMU: ${imuData.heading}°, VPS: ${lastVPSHeading}°)")
        }
    }

    /**
     * 칼만 필터 예측 단계
     * IMU의 회전 속도를 사용하여 heading을 예측합니다.
     */
    private fun predict(imuData: IMUHeadingData, dt: Float) {
        // IMU heading 변화량으로 각속도 추정 (heading 변화 / 시간)
        val imuHeadingChange = angleDifference(imuData.heading, kalmanState.heading)
        if (dt > 0f) {
            kalmanState.headingVelocity = imuHeadingChange / dt
        }

        // 상태 전이 (등속 모델): heading += velocity * dt
        kalmanState.heading += kalmanState.headingVelocity * dt

        // 오차 공분산 예측: P = P + Q (프로세스 노이즈 추가)
        kalmanState.errorCovariance += PROCESS_NOISE * dt

        // IMU heading으로 직접 보정 (낮은 가중치)
        // 이것은 자이로 drift를 보완하기 위해 IMU의 절대 heading을 참조
        val imuWeight = IMU_DIRECT_WEIGHT
        kalmanState.heading = weightedAngleMean(
            kalmanState.heading, 1 - imuWeight,
            imuData.heading, imuWeight
        )
    }

    /**
     * 칼만 필터 보정 단계
     * VPS의 정확한 heading으로 상태를 보정합니다.
     */
    private fun correct(vpsHeading: Float, vpsAccuracy: Float) {
        // 측정 노이즈 (VPS accuracy 기반)
        val measurementNoise = vpsAccuracy * vpsAccuracy

        // 칼만 이득 계산
        // K = P / (P + R)
        val kalmanGain = kalmanState.errorCovariance /
                (kalmanState.errorCovariance + measurementNoise)

        // 혁신(innovation): 측정값과 예측값의 차이
        val innovation = angleDifference(vpsHeading, kalmanState.heading)

        // 상태 보정
        kalmanState.heading += kalmanGain * innovation
        kalmanState.heading = normalizeAngle(kalmanState.heading)

        // 오차 공분산 보정
        kalmanState.errorCovariance *= (1 - kalmanGain)

        // VPS를 사용했으므로 초기화
        lastVPSHeading = null
    }

    /**
     * 현재 heading의 신뢰도를 계산합니다.
     */
    private fun calculateConfidence(): Float {
        // 오차 공분산이 작을수록 높은 신뢰도
        val errorBasedConfidence = 1f / (1f + kalmanState.errorCovariance / 100f)

        // VPS가 최근에 사용되었으면 높은 신뢰도
        val vpsBonus = if (lastVPSHeading != null) 0.2f else 0f

        return (errorBasedConfidence + vpsBonus).coerceIn(0f, 1f)
    }

    private fun determineSource(): FusedHeadingSource {
        return when {
            lastVPSHeading != null -> FusedHeadingSource.VPS_IMU_FUSED
            lastIMUHeading != null -> FusedHeadingSource.IMU_ONLY
            else -> FusedHeadingSource.UNKNOWN
        }
    }

    /**
     * 두 각도의 차이를 계산합니다 (-180 ~ 180).
     */
    private fun angleDifference(angle1: Float, angle2: Float): Float {
        var diff = angle1 - angle2
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        return diff
    }

    /**
     * 두 각도의 가중 평균을 계산합니다.
     */
    private fun weightedAngleMean(angle1: Float, weight1: Float,
                                   angle2: Float, weight2: Float): Float {
        // 단위 벡터로 변환하여 평균
        val rad1 = Math.toRadians(angle1.toDouble())
        val rad2 = Math.toRadians(angle2.toDouble())

        val x = weight1 * cos(rad1) + weight2 * cos(rad2)
        val y = weight1 * sin(rad1) + weight2 * sin(rad2)

        val meanRad = atan2(y, x)
        return normalizeAngle(Math.toDegrees(meanRad).toFloat())
    }

    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360
        if (normalized < 0) normalized += 360
        return normalized
    }

    /**
     * 현재 융합된 heading을 즉시 반환합니다.
     */
    fun getCurrentHeading(): Float? {
        return _fusedHeading.value?.heading
    }

    /**
     * 사용자가 이동 중인지 반환합니다.
     */
    fun isUserMoving(): Boolean {
        return lastIMUHeading?.isMoving ?: false
    }

    companion object {
        private const val TAG = "HeadingFusionService"
        private const val LOG_HEADING = false // 디버깅용

        // 칼만 필터 파라미터
        private const val PROCESS_NOISE = 5f // 프로세스 노이즈 (도^2/초)
        private const val DEFAULT_VPS_HEADING_ACCURACY = 10f // VPS 기본 정확도 (도)
        private const val IMU_DIRECT_WEIGHT = 0.1f // IMU 직접 보정 가중치
    }

    /**
     * 칼만 필터 내부 상태
     */
    private data class KalmanState(
        var heading: Float = 0f,
        var headingVelocity: Float = 0f,
        var errorCovariance: Float = 180f // 초기에는 불확실성 높음
    )
}

/**
 * 융합된 heading 데이터
 */
data class FusedHeadingData(
    /** 융합된 방위각 (0-360도, 북쪽 = 0) */
    val heading: Float,
    /** 방위각 변화 속도 (도/초) */
    val headingVelocity: Float,
    /** 신뢰도 (0-1) */
    val confidence: Float,
    /** 데이터 소스 */
    val source: FusedHeadingSource,
    /** IMU 원시 heading */
    val imuHeading: Float?,
    /** VPS 원시 heading */
    val vpsHeading: Float?,
    /** 사용자 이동 중 여부 */
    val isMoving: Boolean,
    /** 타임스탬프 */
    val timestamp: Long
) {
    /**
     * 신뢰할 수 있는 heading인지 확인합니다.
     */
    val isReliable: Boolean
        get() = confidence > 0.5f
}

enum class FusedHeadingSource {
    VPS_IMU_FUSED,
    IMU_ONLY,
    VPS_ONLY,
    UNKNOWN
}
