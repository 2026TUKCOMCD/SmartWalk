package com.smartwalker.service.location

import android.util.Log
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 위치 추정을 위한 1D Kalman Filter (위도/경도 독립 적용).
 *
 * 상태: [lat, lng]
 * 입력:
 *  - predictWithStep(): PDR(보행 추측 항법) 예측 — 걸음 감지마다 호출
 *  - correct(): GPS/VPS 측정 보정 — 새 위치가 도착할 때마다 호출
 */
class PositionKalmanFilter {

    private var lat = 0.0
    private var lng = 0.0

    // 오차 공분산 (분산, 단위: 위도/경도 도^2)
    private var pLat = INIT_P
    private var pLng = INIT_P

    private var initialized = false

    // GPS 이상치 게이팅: 연속 거절 횟수. 너무 오래 거절하면 실제 점프로 간주.
    private var consecutiveRejects = 0

    val currentLat: Double get() = lat
    val currentLng: Double get() = lng
    val isInitialized: Boolean get() = initialized

    /** correct() 결과 — 호출부 로깅/디버그용. */
    enum class CorrectionResult { ACCEPTED, REJECTED_OUTLIER, FORCED_REACQUIRE }

    /**
     * KF 불확실성(분산)을 미터 단위 정확도로 변환.
     * 발표/디버그용 accuracy 필드에 사용.
     */
    val accuracyMeters: Float
        get() = (sqrt(pLat) * METERS_PER_DEG_LAT).toFloat()

    fun initialize(lat: Double, lng: Double) {
        this.lat = lat
        this.lng = lng
        this.pLat = INIT_P
        this.pLng = INIT_P
        this.initialized = true
        this.consecutiveRejects = 0
        Log.d(TAG, "KF initialized: ($lat, $lng)")
    }

    /**
     * PDR 예측 단계: 한 걸음 이동을 위치에 반영하고 불확실성을 증가시킨다.
     *
     * @param stepLengthMeters 한 걸음 거리 (m), 보통 0.7m
     * @param headingDeg       진행 방향 (0 = 북, 시계 방향, 도 단위)
     */
    fun predictWithStep(stepLengthMeters: Double, headingDeg: Float) {
        val headingRad = Math.toRadians(headingDeg.toDouble())
        val cosLat = cos(Math.toRadians(lat))

        val deltaLat = stepLengthMeters * cos(headingRad) / METERS_PER_DEG_LAT
        val deltaLng = if (cosLat > 1e-10) {
            stepLengthMeters * sin(headingRad) / (METERS_PER_DEG_LAT * cosLat)
        } else {
            0.0
        }

        lat += deltaLat
        lng += deltaLng

        // 프로세스 노이즈: 걸음 길이 불확실성(m) → 도^2 단위
        val q = (STEP_UNCERTAINTY / METERS_PER_DEG_LAT) * (STEP_UNCERTAINTY / METERS_PER_DEG_LAT)
        pLat += q
        pLng += q

        Log.v(TAG, "KF predict: step=${stepLengthMeters}m heading=${headingDeg}° → ($lat, $lng) P=$pLat")
    }

    /**
     * 보정 단계: GPS 또는 VPS 측정값으로 상태를 업데이트한다.
     *
     * @param measLat       측정 위도
     * @param measLng       측정 경도
     * @param accuracyMeters 측정 정확도 (m), Android Location.accuracy 값 사용
     */
    fun correct(measLat: Double, measLng: Double, accuracyMeters: Float): CorrectionResult {
        // ── 이상치 게이팅 ──
        // 현재 추정과 측정의 불일치(innovation, m)가 예측 불확실성 대비 과도하면
        // urban canyon 멀티패스로 간주하고 거절한다. 단 연속 거절이 길어지면
        // 실제 점프(터널 탈출·GPS 재획득)일 수 있으므로 측정값으로 재초기화한다.
        val cosLat = cos(Math.toRadians(lat))
        val dNorthM = (measLat - lat) * METERS_PER_DEG_LAT
        val dEastM = (measLng - lng) * METERS_PER_DEG_LAT * cosLat
        val innovationM = sqrt(dNorthM * dNorthM + dEastM * dEastM)

        val predStdM = sqrt(pLat) * METERS_PER_DEG_LAT
        val measStdM = accuracyMeters.toDouble().coerceAtLeast(1.0)
        val gateM = (GATE_SIGMA * sqrt(predStdM * predStdM + measStdM * measStdM))
            .coerceAtLeast(GATE_FLOOR_M)

        if (innovationM > gateM) {
            consecutiveRejects++
            if (consecutiveRejects < MAX_CONSECUTIVE_REJECTS) {
                Log.w(TAG, "GPS 이상치 거절: innovation=%.1fm > gate=%.1fm (#%d)".format(
                    innovationM, gateM, consecutiveRejects))
                return CorrectionResult.REJECTED_OUTLIER
            }
            Log.w(TAG, "GPS 지속 불일치(%d회) → 측정값으로 재획득".format(consecutiveRejects))
            initialize(measLat, measLng)
            return CorrectionResult.FORCED_REACQUIRE
        }
        consecutiveRejects = 0

        // 측정 노이즈 공분산 R = (accuracy_deg)^2
        val accDeg = measStdM / METERS_PER_DEG_LAT
        val r = accDeg * accDeg

        // Kalman Gain
        val kLat = pLat / (pLat + r)
        val kLng = pLng / (pLng + r)

        // 상태 업데이트
        lat += kLat * (measLat - lat)
        lng += kLng * (measLng - lng)

        // 공분산 업데이트
        pLat = (1.0 - kLat) * pLat
        pLng = (1.0 - kLng) * pLng

        Log.d(TAG, "KF correct: meas=($measLat, $measLng) acc=${accuracyMeters}m K=($kLat) → ($lat, $lng)")
        return CorrectionResult.ACCEPTED
    }

    /**
     * 맵 매칭 소프트 보정: 상태를 경로상의 목표점으로 [gain]만큼 당긴다.
     *
     * 공분산을 의도적으로 줄이지 않는다 — 경로 스냅은 실제 측정이 아닌 사전 제약(prior)이므로,
     * 이를 측정처럼 다뤄 P를 축소하면 P가 0으로 붕괴해 이후 GPS 보정이 막히고
     * 게이팅이 정상 측정을 거절하게 된다. 횡방향만 부드럽게 당기는 무해한 이동으로 처리.
     *
     * @param gain 0~1, 한 번에 당기는 비율. 작을수록 부드럽다.
     */
    fun nudgeToward(targetLat: Double, targetLng: Double, gain: Double) {
        if (!initialized) return
        val g = gain.coerceIn(0.0, 1.0)
        lat += g * (targetLat - lat)
        lng += g * (targetLng - lng)
    }

    /**
     * VO 변위 예측: 횡방향(lateral)과 전방(forward) 이동을 현재 heading으로 변환하여 적용한다.
     *
     * @param lateralMeters  우측(+) / 좌측(-) 이동 (m), heading 기준
     * @param forwardMeters  전진(+) / 후진(-) 이동 (m), heading 기준
     * @param headingDeg     현재 진행 방향 (0 = 북, 시계 방향)
     * @param confidenceScale 0-1, 프로세스 노이즈 스케일 (신뢰도 낮을수록 크게)
     */
    fun predictWithDisplacement(
        lateralMeters: Float,
        forwardMeters: Float,
        headingDeg: Float,
        confidenceScale: Float = 1f
    ) {
        val headingRad = Math.toRadians(headingDeg.toDouble())
        val cosLat     = cos(Math.toRadians(lat))

        // 진행 방향(forward) 단위 벡터: (sin H, cos H) → (east, north)
        // 횡방향(lateral) 단위 벡터: (cos H, -sin H) → 90° 시계방향
        val dNorth = forwardMeters * cos(headingRad) - lateralMeters * sin(headingRad)
        val dEast  = forwardMeters * sin(headingRad) + lateralMeters * cos(headingRad)

        val deltaLat = dNorth / METERS_PER_DEG_LAT
        val deltaLng = if (cosLat > 1e-10) dEast / (METERS_PER_DEG_LAT * cosLat) else 0.0

        lat += deltaLat
        lng += deltaLng

        // VO는 PDR보다 노이즈가 크므로 프로세스 노이즈를 confidenceScale의 역수로 확대
        val displacement = sqrt(lateralMeters * lateralMeters.toDouble() + forwardMeters * forwardMeters.toDouble())
        val noiseScale   = (1f / confidenceScale.coerceIn(0.1f, 1f)).toDouble()
        val q = ((displacement * noiseScale * VO_UNCERTAINTY_FACTOR) / METERS_PER_DEG_LAT).let { it * it }
        pLat += q
        pLng += q

        Log.v(TAG, "KF VO predict: lat=$lateralMeters fwd=$forwardMeters h=$headingDeg → ($lat, $lng)")
    }

    companion object {
        private const val TAG = "PositionKalmanFilter"

        /** 초기 불확실성 (위도 도^2 단위, ~11m에 해당) */
        private const val INIT_P = 1e-4

        /** 걸음 길이 불확실성 (m) — 프로세스 노이즈 크기를 결정 */
        private const val STEP_UNCERTAINTY = 0.15

        /** 평균 걸음 길이 (m) — predictWithStep 호출 시 기본값으로 사용 */
        const val STEP_LENGTH = 0.7

        /** 위도 1도 = 약 111km */
        private const val METERS_PER_DEG_LAT = 111_000.0

        /** VO 예측의 기본 불확실성 배율 (PDR STEP_UNCERTAINTY의 2배) */
        private const val VO_UNCERTAINTY_FACTOR = 0.30

        /** 이상치 게이팅: innovation이 (예측+측정) 표준편차의 이 배수를 넘으면 거절 */
        private const val GATE_SIGMA = 3.0

        /** 게이트 하한(m): P가 작아도 이 거리 이내 측정은 항상 수용 (과도한 거절 방지) */
        private const val GATE_FLOOR_M = 10.0

        /** 연속 거절이 이 횟수에 도달하면 실제 점프로 보고 측정값으로 재초기화 */
        private const val MAX_CONSECUTIVE_REJECTS = 5
    }
}
