package com.navblind.service.voice

import com.navblind.domain.model.DetectedObject
import com.navblind.domain.model.ObjectCategory
import com.navblind.domain.model.RelativeDirection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YOLO 감지 결과를 한국어 음성 경고 문장으로 변환합니다. (T071)
 *
 * 변환 예시:
 *   dangerLevel=0.9, CENTER, 1.5m, "사람" → "위험! 전방 1미터 앞에 사람"
 *   dangerLevel=0.6, LEFT,   4m,   "자전거" → "왼쪽 약 4미터 앞에 자전거"
 *   dangerLevel=0.5, RIGHT,  null, "볼라드" → "오른쪽 볼라드"
 */
@Singleton
class DetectionToSpeechConverter @Inject constructor() {

    fun convert(obj: DetectedObject): String {
        val prefix = urgencyPrefix(obj)
        val direction = directionText(obj.relativeDirection)
        val distance = distanceText(obj.estimatedDistance)
        return "${prefix}${direction} ${distance}${obj.className}".trimEnd()
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
