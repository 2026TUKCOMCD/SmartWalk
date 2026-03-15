package com.navblind.domain.model

import android.graphics.RectF

/**
 * YOLO 객체 인식 결과를 나타내는 도메인 모델입니다.
 */
data class DetectedObject(
    /** 객체 ID (트래킹용) */
    val id: Int,
    /** 인식된 객체 클래스 */
    val classId: Int,
    /** 클래스 이름 (한국어) */
    val className: String,
    /** 인식 신뢰도 (0-1) */
    val confidence: Float,
    /** 바운딩 박스 (이미지 내 위치) */
    val boundingBox: RectF,
    /** 추정 거리 (미터), 계산되지 않은 경우 null */
    val estimatedDistance: Float? = null,
    /** 위험도 (0-1) */
    val dangerLevel: Float = 0f,
    /** 상대적 방향 (사용자 기준) */
    val relativeDirection: RelativeDirection = RelativeDirection.CENTER,
    /** 객체 카테고리 */
    val category: ObjectCategory = ObjectCategory.UNKNOWN,
    /** 감지 타임스탬프 */
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 객체가 위험한 거리에 있는지 확인합니다.
     */
    fun isInDangerZone(thresholdMeters: Float = 3f): Boolean {
        return estimatedDistance != null && estimatedDistance < thresholdMeters
    }

    /**
     * 경고 메시지를 생성합니다.
     */
    fun toWarningMessage(): String {
        val directionText = when (relativeDirection) {
            RelativeDirection.LEFT -> "왼쪽에"
            RelativeDirection.RIGHT -> "오른쪽에"
            RelativeDirection.CENTER -> "전방에"
            RelativeDirection.SLIGHTLY_LEFT -> "왼쪽 전방에"
            RelativeDirection.SLIGHTLY_RIGHT -> "오른쪽 전방에"
        }

        val distanceText = estimatedDistance?.let {
            when {
                it < 1f -> "바로 앞"
                it < 2f -> "약 1미터"
                it < 3f -> "약 2미터"
                it < 5f -> "약 ${it.toInt()}미터"
                else -> "전방"
            }
        } ?: ""

        return "$directionText $distanceText $className"
    }
}

/**
 * 상대적 방향 (사용자 기준)
 */
enum class RelativeDirection {
    LEFT,
    SLIGHTLY_LEFT,
    CENTER,
    SLIGHTLY_RIGHT,
    RIGHT
}

/**
 * 객체 카테고리
 */
enum class ObjectCategory {
    /** 이동하는 장애물 (사람, 자전거, 차량 등) */
    MOVING_OBSTACLE,
    /** 정지 장애물 (기둥, 벤치, 쓰레기통 등) */
    STATIC_OBSTACLE,
    /** 위험 요소 (계단, 구덩이, 공사 구역 등) */
    HAZARD,
    /** 교통 신호 (신호등, 횡단보도 등) */
    TRAFFIC_SIGNAL,
    /** 랜드마크 (건물, 표지판 등) */
    LANDMARK,
    /** 알 수 없음 */
    UNKNOWN;

    companion object {
        fun fromClassName(className: String): ObjectCategory {
            return when (className.lowercase()) {
                "person", "bicycle", "car", "motorcycle", "bus", "truck" -> MOVING_OBSTACLE
                "bench", "chair", "potted plant", "fire hydrant", "parking meter",
                "traffic cone", "bollard", "pole" -> STATIC_OBSTACLE
                "stairs", "curb", "hole", "construction" -> HAZARD
                "traffic light", "stop sign", "crosswalk" -> TRAFFIC_SIGNAL
                "building", "sign" -> LANDMARK
                else -> UNKNOWN
            }
        }
    }
}

/**
 * 객체 인식 결과 전체
 */
data class ObjectDetectionResult(
    /** 감지된 객체 목록 */
    val objects: List<DetectedObject>,
    /** 처리된 프레임의 타임스탬프 */
    val frameTimestamp: Long,
    /** 추론 시간 (밀리초) */
    val inferenceTimeMs: Long,
    /** 프레임 너비 */
    val frameWidth: Int,
    /** 프레임 높이 */
    val frameHeight: Int
) {
    /**
     * 가장 위험한 객체를 반환합니다.
     */
    fun getMostDangerousObject(): DetectedObject? {
        return objects.maxByOrNull { it.dangerLevel }
    }

    /**
     * 위험한 거리에 있는 객체들을 반환합니다.
     */
    fun getObjectsInDangerZone(thresholdMeters: Float = 3f): List<DetectedObject> {
        return objects.filter { it.isInDangerZone(thresholdMeters) }
            .sortedByDescending { it.dangerLevel }
    }
}
