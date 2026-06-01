package com.smartwalker.service.voice

import com.smartwalker.domain.model.DetectedObject
import com.smartwalker.domain.model.ObjectCategory
import com.smartwalker.domain.model.RelativeDirection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YOLO 감지 결과를 한국어 음성 경고 문장으로 변환합니다. (T071)
 *
 * 변환 예시 (spec.md: 장애물 설명 + 권장 행동):
 *   CENTER, 1.5m, "사람", "멈추세요"        → "위험! 전방 1미터 앞에 사람, 멈추세요"
 *   LEFT,   4m,   "자전거", "오른쪽으로 이동하세요" → "왼쪽 약 4미터 앞에 자전거, 오른쪽으로 이동하세요"
 *   RIGHT,  null, "볼라드", null            → "오른쪽 볼라드"
 */
@Singleton
class DetectionToSpeechConverter @Inject constructor() {

    /**
     * @param suggestedAction 권장 회피 행동(예: "오른쪽으로 이동하세요", "멈추세요").
     *   null/공백이면 장애물 설명만 읽는다.
     */
    fun convert(obj: DetectedObject, suggestedAction: String? = null): String {
        val prefix = urgencyPrefix(obj)
        val direction = directionText(obj.relativeDirection)
        val distance = distanceText(obj.estimatedDistance)
        val description = "${prefix}${direction} ${distance}${obj.className}".trimEnd()
        return if (suggestedAction.isNullOrBlank()) description
        else "$description, $suggestedAction"
    }

    private fun urgencyPrefix(obj: DetectedObject): String = when {
        obj.dangerLevel >= 0.8f -> "위험! "
        obj.category == ObjectCategory.HAZARD -> "주의! "
        else -> ""
    }

    private fun directionText(direction: RelativeDirection): String = when (direction) {
        RelativeDirection.LEFT -> "왼쪽"
        RelativeDirection.SLIGHTLY_LEFT -> "왼쪽 전방"
        RelativeDirection.CENTER -> "전방"
        RelativeDirection.SLIGHTLY_RIGHT -> "오른쪽 전방"
        RelativeDirection.RIGHT -> "오른쪽"
    }

    private fun distanceText(distanceMeters: Float?): String = when {
        distanceMeters == null -> ""
        distanceMeters < 1f -> "바로 앞에 "
        distanceMeters < 3f -> "${distanceMeters.toInt() + 1}미터 앞에 "
        distanceMeters < 10f -> "약 ${distanceMeters.toInt()}미터 앞에 "
        else -> ""
    }
}
