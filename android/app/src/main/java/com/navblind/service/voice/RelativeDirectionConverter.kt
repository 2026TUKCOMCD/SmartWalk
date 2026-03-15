package com.navblind.service.voice

import android.util.Log
import com.navblind.domain.model.Instruction
import com.navblind.domain.model.InstructionType
import com.navblind.domain.model.TurnModifier
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2

/**
 * OSRM이 제공하는 절대 방향(북쪽으로 가세요)을
 * 사용자의 현재 heading 기준 상대 방향(오른쪽으로 돌아서 직진하세요)으로 변환하는 서비스입니다.
 *
 * 시각장애인 사용자는 랜드마크를 볼 수 없으므로,
 * "OO건물 방향으로 가세요" 대신 "현재 방향에서 오른쪽으로 90도 돌아서 직진하세요"와 같이
 * 사용자가 바라보는 방향 기준으로 안내합니다.
 */
@Singleton
class RelativeDirectionConverter @Inject constructor() {

    /**
     * 절대 방향을 상대 방향으로 변환합니다.
     *
     * @param targetBearing 목표 방향 (0-360도, 북쪽 = 0)
     * @param currentHeading 사용자가 현재 바라보는 방향 (0-360도, 북쪽 = 0)
     * @return 상대 방향 정보
     */
    fun convertToRelative(
        targetBearing: Float,
        currentHeading: Float
    ): RelativeDirectionInfo {
        // 두 방향의 차이 계산 (-180 ~ 180)
        var angleDiff = targetBearing - currentHeading
        while (angleDiff > 180) angleDiff -= 360
        while (angleDiff < -180) angleDiff += 360

        // 회전 방향과 각도 결정
        val direction = when {
            abs(angleDiff) <= STRAIGHT_THRESHOLD -> TurnDirection.STRAIGHT
            abs(angleDiff) >= UTURN_THRESHOLD -> TurnDirection.UTURN
            angleDiff > 0 && angleDiff <= SLIGHT_TURN_THRESHOLD -> TurnDirection.SLIGHT_RIGHT
            angleDiff > SLIGHT_TURN_THRESHOLD && angleDiff <= SHARP_TURN_THRESHOLD -> TurnDirection.RIGHT
            angleDiff > SHARP_TURN_THRESHOLD -> TurnDirection.SHARP_RIGHT
            angleDiff < 0 && angleDiff >= -SLIGHT_TURN_THRESHOLD -> TurnDirection.SLIGHT_LEFT
            angleDiff < -SLIGHT_TURN_THRESHOLD && angleDiff >= -SHARP_TURN_THRESHOLD -> TurnDirection.LEFT
            else -> TurnDirection.SHARP_LEFT
        }

        Log.d(TAG, "Target: $targetBearing°, Current: $currentHeading°, " +
                "Diff: $angleDiff°, Direction: $direction")

        return RelativeDirectionInfo(
            direction = direction,
            angleDegrees = angleDiff,
            targetBearing = targetBearing,
            currentHeading = currentHeading
        )
    }

    /**
     * Instruction을 상대 방향 음성 안내로 변환합니다.
     *
     * @param instruction 원본 instruction
     * @param currentHeading 사용자가 현재 바라보는 방향
     * @param nextWaypointBearing 다음 웨이포인트 방향 (instruction에서 계산)
     * @return 상대 방향 기반 음성 안내 텍스트
     */
    fun convertInstructionToRelative(
        instruction: Instruction,
        currentHeading: Float?,
        nextWaypointBearing: Float?
    ): String {
        // heading 정보가 없으면 원본 텍스트 반환
        if (currentHeading == null) {
            return instruction.text
        }

        return when (instruction.type) {
            InstructionType.DEPART -> {
                // 출발: 현재 방향에서 목적지 방향으로 몸을 돌려야 하는지 안내
                if (nextWaypointBearing != null) {
                    val relativeInfo = convertToRelative(nextWaypointBearing, currentHeading)
                    generateDepartureMessage(relativeInfo, instruction.distance)
                } else {
                    instruction.text
                }
            }

            InstructionType.TURN -> {
                // 회전: OSRM modifier가 "그 지점에서 어느 방향으로 도는가"를 이미 알고 있으므로 우선 사용.
                // bearing은 "회전 지점이 어디 있는가"라서 실제 회전 방향과 다를 수 있음.
                if (instruction.modifier != null) {
                    generateTurnMessageFromModifier(instruction.modifier, instruction.distance)
                } else if (nextWaypointBearing != null) {
                    val relativeInfo = convertToRelative(nextWaypointBearing, currentHeading)
                    generateTurnMessage(relativeInfo, instruction.distance)
                } else {
                    "${instruction.distance}미터 앞에서 방향을 바꾸세요"
                }
            }

            InstructionType.CONTINUE -> {
                // 직진 유지
                if (nextWaypointBearing != null) {
                    val relativeInfo = convertToRelative(nextWaypointBearing, currentHeading)
                    if (relativeInfo.direction == TurnDirection.STRAIGHT) {
                        "${instruction.distance}미터 직진하세요"
                    } else {
                        // 약간의 방향 조정이 필요한 경우
                        generateContinueMessage(relativeInfo, instruction.distance)
                    }
                } else {
                    "${instruction.distance}미터 직진하세요"
                }
            }

            InstructionType.CROSSWALK -> {
                // 횡단보도: 방향 정보 추가
                if (nextWaypointBearing != null) {
                    val relativeInfo = convertToRelative(nextWaypointBearing, currentHeading)
                    generateCrosswalkMessage(relativeInfo)
                } else {
                    "횡단보도를 건너세요"
                }
            }

            InstructionType.ARRIVE -> {
                // 도착: 목적지가 어느 방향에 있는지 안내
                if (nextWaypointBearing != null) {
                    val relativeInfo = convertToRelative(nextWaypointBearing, currentHeading)
                    generateArrivalMessage(relativeInfo)
                } else {
                    "목적지에 도착했습니다"
                }
            }
        }
    }

    /**
     * 두 좌표 사이의 방위각(bearing)을 계산합니다.
     */
    fun calculateBearing(
        fromLat: Double, fromLng: Double,
        toLat: Double, toLng: Double
    ): Float {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLng = Math.toRadians(toLng - fromLng)

        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)

        val bearing = Math.toDegrees(atan2(y, x)).toFloat()
        return (bearing + 360) % 360 // 0-360 범위로 정규화
    }

    // 메시지 생성 함수들

    private fun generateDepartureMessage(info: RelativeDirectionInfo, distance: Int): String {
        return when (info.direction) {
            TurnDirection.STRAIGHT -> "${distance}미터 앞으로 출발하세요"
            TurnDirection.SLIGHT_LEFT -> "왼쪽으로 약간 돌아서 ${distance}미터 직진하세요"
            TurnDirection.SLIGHT_RIGHT -> "오른쪽으로 약간 돌아서 ${distance}미터 직진하세요"
            TurnDirection.LEFT -> "왼쪽으로 돌아서 ${distance}미터 직진하세요"
            TurnDirection.RIGHT -> "오른쪽으로 돌아서 ${distance}미터 직진하세요"
            TurnDirection.SHARP_LEFT -> "왼쪽으로 크게 돌아서 ${distance}미터 직진하세요"
            TurnDirection.SHARP_RIGHT -> "오른쪽으로 크게 돌아서 ${distance}미터 직진하세요"
            TurnDirection.UTURN -> "뒤로 돌아서 ${distance}미터 직진하세요"
        }
    }

    private fun generateTurnMessage(info: RelativeDirectionInfo, distance: Int): String {
        val distanceText = if (distance > 0) "${distance}미터 앞에서 " else ""
        return when (info.direction) {
            TurnDirection.STRAIGHT -> "${distanceText}계속 직진하세요"
            TurnDirection.SLIGHT_LEFT -> "${distanceText}왼쪽으로 약간 꺾으세요"
            TurnDirection.SLIGHT_RIGHT -> "${distanceText}오른쪽으로 약간 꺾으세요"
            TurnDirection.LEFT -> "${distanceText}좌회전하세요"
            TurnDirection.RIGHT -> "${distanceText}우회전하세요"
            TurnDirection.SHARP_LEFT -> "${distanceText}왼쪽으로 크게 돌아가세요"
            TurnDirection.SHARP_RIGHT -> "${distanceText}오른쪽으로 크게 돌아가세요"
            TurnDirection.UTURN -> "${distanceText}유턴하세요"
        }
    }

    private fun generateTurnMessageFromModifier(modifier: TurnModifier?, distance: Int): String {
        val distanceText = if (distance > 0) "${distance}미터 앞에서 " else ""
        return when (modifier) {
            TurnModifier.LEFT -> "${distanceText}좌회전하세요"
            TurnModifier.RIGHT -> "${distanceText}우회전하세요"
            TurnModifier.STRAIGHT -> "${distanceText}직진하세요"
            TurnModifier.SLIGHT_LEFT -> "${distanceText}왼쪽으로 약간 꺾으세요"
            TurnModifier.SLIGHT_RIGHT -> "${distanceText}오른쪽으로 약간 꺾으세요"
            TurnModifier.UTURN -> "${distanceText}유턴하세요"
            null -> "${distanceText}방향을 바꾸세요"
        }
    }

    private fun generateContinueMessage(info: RelativeDirectionInfo, distance: Int): String {
        val adjustment = when (info.direction) {
            TurnDirection.SLIGHT_LEFT -> "약간 왼쪽으로 방향을 조정하며 "
            TurnDirection.SLIGHT_RIGHT -> "약간 오른쪽으로 방향을 조정하며 "
            else -> ""
        }
        return "${adjustment}${distance}미터 직진하세요"
    }

    private fun generateCrosswalkMessage(info: RelativeDirectionInfo): String {
        return when (info.direction) {
            TurnDirection.STRAIGHT -> "횡단보도를 건너세요"
            TurnDirection.SLIGHT_LEFT, TurnDirection.LEFT, TurnDirection.SHARP_LEFT ->
                "왼쪽 방향으로 횡단보도를 건너세요"
            TurnDirection.SLIGHT_RIGHT, TurnDirection.RIGHT, TurnDirection.SHARP_RIGHT ->
                "오른쪽 방향으로 횡단보도를 건너세요"
            TurnDirection.UTURN -> "뒤쪽 횡단보도를 건너세요"
        }
    }

    private fun generateArrivalMessage(info: RelativeDirectionInfo): String {
        return when (info.direction) {
            TurnDirection.STRAIGHT -> "목적지가 바로 앞에 있습니다"
            TurnDirection.SLIGHT_LEFT, TurnDirection.LEFT ->
                "목적지가 왼쪽에 있습니다"
            TurnDirection.SLIGHT_RIGHT, TurnDirection.RIGHT ->
                "목적지가 오른쪽에 있습니다"
            TurnDirection.SHARP_LEFT -> "목적지가 왼쪽 뒤에 있습니다"
            TurnDirection.SHARP_RIGHT -> "목적지가 오른쪽 뒤에 있습니다"
            TurnDirection.UTURN -> "목적지가 뒤에 있습니다"
        }
    }

    companion object {
        private const val TAG = "RelativeDirectionConverter"

        // 각도 임계값 (도)
        private const val STRAIGHT_THRESHOLD = 15f       // ±15도 이내는 직진
        private const val SLIGHT_TURN_THRESHOLD = 45f    // ±45도 이내는 약간 회전
        private const val SHARP_TURN_THRESHOLD = 120f    // ±120도 이내는 회전
        private const val UTURN_THRESHOLD = 160f         // ±160도 이상은 유턴
    }
}

/**
 * 상대 방향 정보
 */
data class RelativeDirectionInfo(
    /** 회전 방향 */
    val direction: TurnDirection,
    /** 현재 방향에서의 각도 차이 (양수: 오른쪽, 음수: 왼쪽) */
    val angleDegrees: Float,
    /** 목표 방향 (절대) */
    val targetBearing: Float,
    /** 현재 방향 (절대) */
    val currentHeading: Float
) {
    /**
     * 사용자에게 읽어줄 각도 텍스트를 생성합니다.
     */
    fun getAngleText(): String {
        val absAngle = abs(angleDegrees).toInt()
        return when {
            absAngle <= 15 -> "직진"
            absAngle <= 30 -> "약 30도"
            absAngle <= 60 -> "약 45도"
            absAngle <= 100 -> "약 90도"
            absAngle <= 150 -> "약 135도"
            else -> "180도"
        }
    }
}

/**
 * 회전 방향
 */
enum class TurnDirection {
    STRAIGHT,      // 직진
    SLIGHT_LEFT,   // 약간 왼쪽
    LEFT,          // 왼쪽
    SHARP_LEFT,    // 크게 왼쪽
    SLIGHT_RIGHT,  // 약간 오른쪽
    RIGHT,         // 오른쪽
    SHARP_RIGHT,   // 크게 오른쪽
    UTURN          // 유턴
}
