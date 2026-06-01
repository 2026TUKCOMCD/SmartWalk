package com.smartwalker.service.detection

import com.smartwalker.domain.model.DetectedObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 검출된 장애물에 최종 경보 우선순위를 부여하고 정렬한다.
 *
 * 최종 점수 = dangerLevel(DistanceEstimator) × 0.6
 *           + collisionRisk(TrajectoryPredictor) × 0.4
 *
 * dangerLevel 은 DistanceEstimator 에서 이미 `거리 × 카테고리 × 방향`으로 산출되므로,
 * 여기서 카테고리·방향을 다시 더하면 이중가중이 된다. 따라서 이 단계에서는
 * dangerLevel(정적 위험도)과 collisionRisk(동적 충돌 위험도)라는 서로 독립적인 두 신호만
 * 합산한다. 이를 통해 "다가오는 자전거 1m 전방" 같은 동적+근접 위협이
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
        // dangerLevel 에 카테고리·방향이 이미 반영돼 있으므로 여기서 다시 더하지 않는다.
        // 정적 위험도(dangerLevel)와 동적 충돌 위험도(collisionRisk)만 합산.
        return (obj.dangerLevel * DANGER_WEIGHT + collisionRisk * COLLISION_WEIGHT)
            .coerceIn(0f, 1f)
    }

    private companion object {
        const val DANGER_WEIGHT = 0.6f     // 정적 위험도(거리×카테고리×방향) 가중치
        const val COLLISION_WEIGHT = 0.4f  // 동적 충돌 위험도(궤적) 가중치
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
