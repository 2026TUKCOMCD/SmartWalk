package com.navblind.service.detection

import android.graphics.PointF
import android.graphics.RectF
import com.navblind.domain.model.DetectedObject
import com.navblind.domain.model.RelativeDirection
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 트랙 이력을 바탕으로 객체의 이동 궤적을 분석하고
 * 사용자와의 충돌 위험도(0-1)를 예측한다.
 *
 * 위험도는 다음 세 가지 요소로 구성된다:
 *  1. 접근 속도 — 바운딩박스가 커지는 속도 (확대율/프레임)
 *  2. 방향 벡터 — 화면 중앙(= 사용자 진행 방향)으로 향하는 정도
 *  3. 이동 거리 — 절대 이동량이 클수록 동적 장애물로 간주
 */
@Singleton
class TrajectoryPredictor @Inject constructor(
    private val tracker: ObjectTracker
) {
    /**
     * 검출 목록에 대해 충돌 위험도를 계산한다.
     *
     * @param detections ObjectTracker가 안정적인 trackId를 할당한 목록
     * @param frameWidth  현재 프레임 가로 크기 (픽셀)
     * @param frameHeight 현재 프레임 세로 크기 (픽셀)
     * @return trackId → 충돌위험도(0-1) 맵
     */
    fun predict(
        detections: List<DetectedObject>,
        frameWidth: Int,
        frameHeight: Int
    ): Map<Int, Float> {
        if (frameWidth == 0 || frameHeight == 0) return emptyMap()

        val frameCenterX = frameWidth / 2f
        val frameCenterY = frameHeight / 2f

        return detections.associate { obj ->
            val trackId = obj.id
            val history = tracker.getHistory(trackId)
            val velocity = tracker.getVelocity(trackId)

            val risk = when {
                history.size < 2 -> 0f  // 이력 부족 — 위험도 미적용
                else -> computeRisk(history, velocity, frameCenterX, frameCenterY)
            }

            trackId to risk.coerceIn(0f, 1f)
        }
    }

    private fun computeRisk(
        history: List<RectF>,
        velocity: PointF,
        cx: Float,
        cy: Float
    ): Float {
        val oldest = history.first()
        val newest = history.last()
        val frames = (history.size - 1).coerceAtLeast(1)

        // 1. 접근 속도 — 박스 면적 증가율
        val oldArea = oldest.width() * oldest.height()
        val newArea = newest.width() * newest.height()
        val approachRate = if (oldArea > 0f) {
            ((newArea - oldArea) / oldArea / frames).coerceIn(-1f, 1f)
        } else 0f
        // 다가오는 경우(양수)만 위험, 멀어지면 0
        val approachScore = maxOf(0f, approachRate) * 2f  // 0-1로 스케일

        // 2. 방향 벡터 — 화면 중앙을 향하는 성분
        val moveDist = hypot(velocity.x, velocity.y)
        val headingScore = if (moveDist > 0.5f) {
            // 중앙 방향 단위벡터와 속도 벡터의 코사인 유사도
            val toCenterX = cx - newest.centerX()
            val toCenterY = cy - newest.centerY()
            val toCenterDist = hypot(toCenterX, toCenterY).coerceAtLeast(1f)
            val cosine = (velocity.x * toCenterX + velocity.y * toCenterY) /
                    (moveDist * toCenterDist)
            maxOf(0f, cosine)  // 반대 방향이면 0
        } else 0f

        // 3. 절대 이동량 — 정적/동적 구분
        val motionScore = (moveDist / MAX_STATIC_VELOCITY_PX).coerceIn(0f, 1f)

        // 가중 합산
        return approachScore * 0.5f + headingScore * 0.3f + motionScore * 0.2f
    }

    companion object {
        // 이 속도(픽셀/프레임) 이하면 정적 장애물로 간주
        private const val MAX_STATIC_VELOCITY_PX = 5f
    }
}
