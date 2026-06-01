package com.smartwalker.service.location

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

/**
 * IMU 센서로 안정적이고 정확한 방위각을 산출하는 서비스입니다.
 *
 * 칼만 필터로:
 * - 자이로 각속도로 heading을 예측(빠른 응답)하고,
 * - 회전벡터 절대 heading으로 보정(자이로 적분 드리프트 제거)합니다.
 */
@Singleton
class HeadingFusionService @Inject constructor(
    private val imuSensorService: IMUSensorService
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
    private var lastUpdateTime: Long = 0
    private var lastGyroHeading: Float = 0f
    private var kfInitialized = false

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
    }

    /**
     * Heading 융합을 중지합니다.
     */
    fun stopFusion() {
        _isRunning.value = false
        imuSensorService.stopSensors()
        lastIMUHeading = null
        lastGyroHeading = 0f
        lastUpdateTime = 0
        kfInitialized = false
        kalmanState = KalmanState()
        Log.d(TAG, "Stopped heading fusion")
    }

    private fun updateFusedHeading() {
        val imuData = lastIMUHeading ?: return
        val now = System.currentTimeMillis()

        // 초기화: 첫 IMU 절대 heading(회전벡터)으로 상태 시작
        if (!kfInitialized) {
            kalmanState.heading = imuData.heading
            kalmanState.headingVelocity = 0f
            kalmanState.errorCovariance = INIT_COVARIANCE
            lastGyroHeading = imuData.gyroHeading
            lastUpdateTime = now
            kfInitialized = true
            emitFused(imuData, now)
            return
        }

        val dt = ((now - lastUpdateTime) / 1000f).coerceIn(MIN_DT, MAX_DT)
        lastUpdateTime = now

        // ── 예측: 자이로 각속도로 heading 전진 (진짜 운동 모델) ──
        // gyroHeading 증분을 사용 — 이전 구현처럼 IMU 절대값에 스냅하지 않는다.
        val gyroDelta = angleDifference(imuData.gyroHeading, lastGyroHeading)
        lastGyroHeading = imuData.gyroHeading
        kalmanState.heading = normalizeAngle(kalmanState.heading + gyroDelta)
        kalmanState.headingVelocity = if (dt > 0f) gyroDelta / dt else 0f
        kalmanState.errorCovariance =
            (kalmanState.errorCovariance + PROCESS_NOISE * dt).coerceAtMost(MAX_COVARIANCE)

        // ── 보정: IMU 절대 heading(회전벡터, drift 없음)으로 자이로 적분 드리프트 교정 ──
        correctWith(imuData.heading, IMU_HEADING_NOISE_DEG)

        kalmanState.errorCovariance =
            kalmanState.errorCovariance.coerceIn(MIN_COVARIANCE, MAX_COVARIANCE)
        emitFused(imuData, now)
    }

    /** 측정 heading으로 1D 칼만 보정 (각도 wrap-around 처리). */
    private fun correctWith(measHeadingDeg: Float, measNoiseStdDeg: Float) {
        val r = measNoiseStdDeg * measNoiseStdDeg
        val k = kalmanState.errorCovariance / (kalmanState.errorCovariance + r)
        val innovation = angleDifference(measHeadingDeg, kalmanState.heading)
        kalmanState.heading = normalizeAngle(kalmanState.heading + k * innovation)
        kalmanState.errorCovariance *= (1f - k)
    }

    private fun emitFused(imuData: IMUHeadingData, now: Long) {
        val fusedData = FusedHeadingData(
            heading = normalizeAngle(kalmanState.heading),
            headingVelocity = kalmanState.headingVelocity,
            confidence = calculateConfidence(),
            source = determineSource(),
            imuHeading = imuData.heading,
            vpsHeading = null,
            isMoving = imuData.isMoving,
            timestamp = now
        )
        _fusedHeading.value = fusedData
        if (LOG_HEADING) {
            Log.d(TAG, "Fused heading: ${fusedData.heading}° (IMU: ${imuData.heading}°, " +
                    "conf: ${"%.2f".format(fusedData.confidence)})")
        }
    }

    /**
     * 현재 heading의 신뢰도를 계산합니다.
     */
    private fun calculateConfidence(): Float {
        // 공분산이 [MIN,MAX]로 유계이므로 0~1 신뢰도가 의미를 가진다 (VPS 없어도 붕괴하지 않음).
        return (1f / (1f + kalmanState.errorCovariance / CONFIDENCE_SCALE)).coerceIn(0f, 1f)
    }

    private fun determineSource(): FusedHeadingSource {
        return if (lastIMUHeading != null) FusedHeadingSource.IMU_ONLY
        else FusedHeadingSource.UNKNOWN
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
        private const val MIN_DT = 0.001f
        private const val MAX_DT = 0.5f
        private const val PROCESS_NOISE = 8f                  // 자이로 모델 노이즈 (도^2/초)
        private const val IMU_HEADING_NOISE_DEG = 12f         // 회전벡터 절대 heading 측정 std (도)
        private const val INIT_COVARIANCE = 100f
        private const val MIN_COVARIANCE = 1f
        private const val MAX_COVARIANCE = 300f
        private const val CONFIDENCE_SCALE = 50f
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
