package com.navblind.service.location

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

    val currentLat: Double get() = lat
    val currentLng: Double get() = lng
    val isInitialized: Boolean get() = initialized

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
    fun correct(measLat: Double, measLng: Double, accuracyMeters: Float) {
        // 측정 노이즈 공분산 R = (accuracy_deg)^2
        val accDeg = accuracyMeters.toDouble() / METERS_PER_DEG_LAT
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
    }
}
