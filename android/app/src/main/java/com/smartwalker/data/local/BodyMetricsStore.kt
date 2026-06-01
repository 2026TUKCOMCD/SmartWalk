package com.smartwalker.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 사용자 신체 정보(키)를 로컬에 저장하고, 이를 PDR 보폭(step length)으로 변환한다.
 *
 * 고정 보폭(0.7m)은 개인차(±20%)가 누적 drift가 되므로, 사용자 키로 보폭을 개인화한다.
 * 보폭 ≈ 0.415 × 키 는 보행 연구에서 널리 쓰이는 근사식이다.
 *
 * Room/원격 환경설정 파이프라인과 분리해 로컬 전용으로만 보관한다
 * (DB 마이그레이션·API 계약 변경 없이 측위 튜닝값만 다룬다).
 */
@Singleton
class BodyMetricsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 사용자 키(cm). 미설정 시 기본 170cm. */
    var heightCm: Int
        get() = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT_CM)
        set(value) {
            prefs.edit().putInt(KEY_HEIGHT, value.coerceIn(MIN_HEIGHT_CM, MAX_HEIGHT_CM)).apply()
        }

    /** 키에서 추정한 한 걸음 거리(m). PDR 예측에 사용. 합리적 범위로 제한. */
    val stepLengthMeters: Double
        get() = (STRIDE_FACTOR * heightCm / 100.0).coerceIn(MIN_STEP_M, MAX_STEP_M)

    companion object {
        private const val PREFS_NAME = "body_metrics"
        private const val KEY_HEIGHT = "height_cm"

        const val DEFAULT_HEIGHT_CM = 170
        const val MIN_HEIGHT_CM = 130
        const val MAX_HEIGHT_CM = 210

        private const val STRIDE_FACTOR = 0.415  // 보폭/키 근사 계수
        private const val MIN_STEP_M = 0.5
        private const val MAX_STEP_M = 0.85
    }
}
