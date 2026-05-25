package com.navblind.service.detection

import com.navblind.domain.model.DetectedObject
import com.navblind.domain.model.ObjectCategory
import com.navblind.domain.model.RelativeDirection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 검출된 장애물에 최종 경보 우선순위를 부여하고 정렬한다.
 *
 * 최종 점수 = dangerLevel(DistanceEstimator) × 0.5
 *           + collisionRisk(TrajectoryPredictor) × 0.35
 *           + categoryBonus                       × 0.15
 *
 * 이를 통해 "다가오는 자전거 1m 전방" 같은 동적+근접 위협이
 * "정지된 벤치 5m 왼쪽"보다 높은 순위를 얻는다.
 */
@Singleton
class HazardPrioritizer @Inject constructor() {

    /**
     * 객체 목록을 위험 우선순위 순으로 정렬하여 반환한다.
     *
     * @param detections   ObjectTracker가 ID를 안정화한 검출 목록
     * @param collisionRisks trackId → TrajectoryPredictor 충돌위험도 (0-1)
     * @return 우선순위 높은 것부터 정렬된 [PrioritizedHazard] 목록
     */
    fun prioritize(
        detections: List<DetectedObject>,
        collisionRisks: Map<Int, Float>
    ): List<PrioritizedHazard> {
        return detections
            .map { obj ->
                val collisionRisk = collisionRisks[obj.id] ?: 0f
                val score = computeScore(obj, collisionRisk)
                PrioritizedHazard(obj, collisionRisk, score)
            }
            .sortedByDescending { it.priorityScore }
    }

    /**
     * 경보 가치가 있는 최우선 장애물을 단일로 반환한다.
     * ObstacleAlertService의 단건 TTS 경보에 사용한다.
     *
     * @param minScore 이 점수 미만이면 null 반환
     */
    fun topHazard(
        detections: List<DetectedObject>,
        collisionRisks: Map<Int, Float>,
        minScore: Float = 0.4f
    ): PrioritizedHazard? =
        prioritize(detections, collisionRisks).firstOrNull { it.priorityScore >= minScore }

    // ─── 점수 계산 ────────────────────────────────────────────────────────────

    private fun computeScore(obj: DetectedObject, collisionRisk: Float): Float {
        val categoryBonus = categoryBonus(obj.category, obj.relativeDirection)
        return (obj.dangerLevel * 0.5f
                + collisionRisk * 0.35f
                + categoryBonus * 0.15f)
            .coerceIn(0f, 1f)
    }

    private fun categoryBonus(category: ObjectCategory, direction: RelativeDirection): Float {
        // 위험 카테고리 기본값
        val base = when (category) {
            ObjectCategory.HAZARD -> 1.0f           // 계단, 구덩이 — 항상 최우선
            ObjectCategory.MOVING_OBSTACLE -> 0.8f  // 사람, 차량
            ObjectCategory.STATIC_OBSTACLE -> 0.5f  // 벤치, 볼라드
            ObjectCategory.TRAFFIC_SIGNAL -> 0.3f   // 신호등 — 정보성
            ObjectCategory.LANDMARK -> 0.1f
            ObjectCategory.UNKNOWN -> 0.4f
        }
        // 정면에 있을수록 보정
        val directionMultiplier = when (direction) {
            RelativeDirection.CENTER -> 1.3f
            RelativeDirection.SLIGHTLY_LEFT, RelativeDirection.SLIGHTLY_RIGHT -> 1.0f
            RelativeDirection.LEFT, RelativeDirection.RIGHT -> 0.7f
        }
        return (base * directionMultiplier).coerceIn(0f, 1f)
    }

    // ─── 결과 타입 ────────────────────────────────────────────────────────────

    data class PrioritizedHazard(
        val obj: DetectedObject,
        val collisionRisk: Float,
        val priorityScore: Float
    ) {
        /** 동적 위협 여부 (TrajectoryPredictor 기반) */
        val isDynamic: Boolean get() = collisionRisk > 0.3f

        /** 즉각 경보가 필요한 수준인지 */
        val isImmediate: Boolean get() = priorityScore >= 0.7f
    }
}
