package com.navblind.service.voice

import android.util.Log
import com.navblind.domain.model.Instruction
import com.navblind.domain.model.InstructionType
import com.navblind.domain.model.Route
import com.navblind.domain.model.TurnModifier
import com.navblind.service.location.HeadingFusionService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 네비게이션 Instruction을 시각장애인을 위한
 * 자연스럽고 명확한 한국어 음성 안내로 변환하는 서비스입니다.
 *
 * 주요 기능:
 * 1. OSRM instruction을 한국어로 변환
 * 2. 사용자 heading 기준 상대 방향 안내 (RelativeDirectionConverter 사용)
 * 3. 거리에 따른 표현 조정 (10m 앞, 약 2m 전방 등)
 * 4. 위험 상황에 맞는 긴급 안내 톤
 */
@Singleton
class InstructionToSpeechConverter @Inject constructor(
    private val relativeDirectionConverter: RelativeDirectionConverter,
    private val headingFusionService: HeadingFusionService
) {
    /**
     * Instruction을 음성 안내 텍스트로 변환합니다.
     * 사용자의 현재 heading을 고려하여 상대 방향으로 안내합니다.
     *
     * @param instruction 변환할 instruction
     * @param route 전체 경로 (다음 waypoint 계산용)
     * @param currentLat 현재 위도
     * @param currentLng 현재 경도
     * @return 음성 안내 텍스트
     */
    @Suppress("UNUSED_PARAMETER")
    fun convert(
        instruction: Instruction,
        route: Route? = null,
        currentLat: Double? = null,
        currentLng: Double? = null
    ): String {
        // 현재 heading 가져오기
        val currentHeading = headingFusionService.getCurrentHeading()

        // 다음 waypoint 방향 계산
        val nextWaypointBearing = if (currentLat != null && currentLng != null) {
            relativeDirectionConverter.calculateBearing(
                currentLat, currentLng,
                instruction.location.lat, instruction.location.lng
            )
        } else {
            null
        }

        // heading이 있으면 상대 방향 안내, 없으면 기본 안내
        return if (currentHeading != null) {
            Log.d(TAG, "Converting with relative direction. Heading: $currentHeading°")
            relativeDirectionConverter.convertInstructionToRelative(
                instruction, currentHeading, nextWaypointBearing
            )
        } else {
            Log.d(TAG, "No heading available, using default conversion")
            convertToDefaultKorean(instruction)
        }
    }

    /**
     * Instruction을 기본 한국어 안내로 변환합니다 (heading 없을 때).
     */
    private fun convertToDefaultKorean(instruction: Instruction): String {
        val distanceText = formatDistance(instruction.distance)

        return when (instruction.type) {
            InstructionType.DEPART -> {
                val directionText = instruction.modifier?.toKorean() ?: "앞으로"
                "$directionText 출발하세요. $distanceText 직진합니다."
            }

            InstructionType.TURN -> {
                val directionText = instruction.modifier?.toKorean() ?: "방향을 바꾸세요"
                "$distanceText 앞에서 $directionText"
            }

            InstructionType.CONTINUE -> {
                "$distanceText 직진하세요"
            }

            InstructionType.CROSSWALK -> {
                "횡단보도를 건너세요. $distanceText 후 다음 안내가 있습니다."
            }

            InstructionType.ARRIVE -> {
                "목적지에 도착했습니다"
            }
        }
    }

    /**
     * 경로 시작 안내 메시지를 생성합니다.
     */
    fun generateRouteStartMessage(
        route: Route,
        destinationName: String,
        currentLat: Double,
        currentLng: Double
    ): String {
        val distanceText = formatDistance(route.distance)
        val timeText = formatDuration(route.duration)

        val firstInstruction = route.instructions.firstOrNull()
        val currentHeading = headingFusionService.getCurrentHeading()

        val directionGuide = if (firstInstruction != null && currentHeading != null) {
            val bearing = relativeDirectionConverter.calculateBearing(
                currentLat, currentLng,
                firstInstruction.location.lat, firstInstruction.location.lng
            )
            val relativeInfo = relativeDirectionConverter.convertToRelative(bearing, currentHeading)

            when (relativeInfo.direction) {
                TurnDirection.STRAIGHT -> "바로 앞으로"
                TurnDirection.SLIGHT_LEFT -> "왼쪽 전방으로"
                TurnDirection.SLIGHT_RIGHT -> "오른쪽 전방으로"
                TurnDirection.LEFT -> "왼쪽으로 돌아서"
                TurnDirection.RIGHT -> "오른쪽으로 돌아서"
                TurnDirection.SHARP_LEFT -> "왼쪽 뒤로 돌아서"
                TurnDirection.SHARP_RIGHT -> "오른쪽 뒤로 돌아서"
                TurnDirection.UTURN -> "뒤로 돌아서"
            }
        } else {
            ""
        }

        return buildString {
            append("${destinationName}까지 ")
            append(distanceText)
            append(", 약 $timeText 경로를 안내합니다. ")
            if (directionGuide.isNotEmpty()) {
                append("$directionGuide 출발하세요.")
            }
        }
    }

    /**
     * 다음 instruction 예고 메시지를 생성합니다.
     */
    fun generateUpcomingTurnMessage(
        instruction: Instruction,
        distanceToInstruction: Int,
        currentLat: Double,
        currentLng: Double
    ): String {
        val currentHeading = headingFusionService.getCurrentHeading()

        if (currentHeading != null) {
            val bearing = relativeDirectionConverter.calculateBearing(
                currentLat, currentLng,
                instruction.location.lat, instruction.location.lng
            )
            val relativeInfo = relativeDirectionConverter.convertToRelative(bearing, currentHeading)

            val distanceText = formatDistance(distanceToInstruction)
            val directionText = when (relativeInfo.direction) {
                TurnDirection.LEFT, TurnDirection.SHARP_LEFT -> "좌회전"
                TurnDirection.RIGHT, TurnDirection.SHARP_RIGHT -> "우회전"
                TurnDirection.SLIGHT_LEFT -> "왼쪽으로 꺾기"
                TurnDirection.SLIGHT_RIGHT -> "오른쪽으로 꺾기"
                TurnDirection.UTURN -> "유턴"
                TurnDirection.STRAIGHT -> "직진"
            }

            return "$distanceText 앞에서 $directionText 합니다"
        }

        return "${formatDistance(distanceToInstruction)} 앞에서 ${instruction.modifier?.toKorean() ?: "방향 전환"}"
    }

    /**
     * 재탐색 시작 안내를 생성합니다.
     */
    fun generateRerouteMessage(): String {
        return "경로를 재탐색합니다. 잠시만 기다려주세요."
    }

    /**
     * 재탐색 완료 후 새 경로 안내를 생성합니다.
     */
    fun generateNewRouteMessage(
        route: Route,
        currentLat: Double,
        currentLng: Double
    ): String {
        val firstInstruction = route.instructions.firstOrNull() ?: return "새로운 경로입니다"

        val speechText = convert(firstInstruction, route, currentLat, currentLng)
        return "새로운 경로입니다. $speechText"
    }

    /**
     * 거리를 자연스러운 한국어로 포맷합니다.
     */
    private fun formatDistance(meters: Int): String {
        return when {
            meters < 10 -> "바로 앞"
            meters < 30 -> "${meters}미터"
            meters < 100 -> "${(meters / 10) * 10}미터"
            meters < 1000 -> "${(meters / 50) * 50}미터"
            else -> String.format("%.1f킬로미터", meters / 1000.0)
        }
    }

    /**
     * 시간을 자연스러운 한국어로 포맷합니다.
     */
    private fun formatDuration(seconds: Int): String {
        return when {
            seconds < 60 -> "1분 미만"
            seconds < 3600 -> "${seconds / 60}분"
            else -> "${seconds / 3600}시간 ${(seconds % 3600) / 60}분"
        }
    }

    companion object {
        private const val TAG = "InstructionToSpeech"
    }
}

/**
 * TurnModifier를 한국어로 변환합니다.
 */
private fun TurnModifier.toKorean(): String {
    return when (this) {
        TurnModifier.LEFT -> "좌회전하세요"
        TurnModifier.RIGHT -> "우회전하세요"
        TurnModifier.STRAIGHT -> "직진하세요"
        TurnModifier.SLIGHT_LEFT -> "왼쪽으로 약간 꺾으세요"
        TurnModifier.SLIGHT_RIGHT -> "오른쪽으로 약간 꺾으세요"
        TurnModifier.UTURN -> "유턴하세요"
    }
}
