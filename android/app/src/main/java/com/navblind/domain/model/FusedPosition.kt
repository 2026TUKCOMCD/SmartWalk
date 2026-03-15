package com.navblind.domain.model

data class FusedPosition(
    val coordinate: Coordinate,
    val accuracy: Float,
    val altitude: Double?,
    /** GPS/VPS에서 제공하는 원시 heading (이동 방향 기준) */
    val heading: Float?,
    val source: PositionSource,
    val timestamp: Long = System.currentTimeMillis(),
    /** VPS & IMU 센서 융합으로 산출된 방위각 (사용자가 바라보는 방향) */
    val fusedHeading: Float? = null,
    /** 융합된 heading의 신뢰도 (0-1) */
    val headingConfidence: Float? = null,
    /** 사용자가 현재 이동 중인지 여부 */
    val isMoving: Boolean = false
) {
    val isHighAccuracy: Boolean
        get() = accuracy < 5f // Less than 5 meters (SC-003 requirement)

    val isAcceptable: Boolean
        get() = accuracy < 20f // Less than 20 meters

    /**
     * 사용 가능한 가장 신뢰할 수 있는 heading을 반환합니다.
     * 융합된 heading이 있으면 우선 사용, 없으면 GPS heading 사용
     */
    val bestHeading: Float?
        get() = fusedHeading ?: heading

    /**
     * heading이 신뢰할 수 있는지 확인합니다.
     */
    val hasReliableHeading: Boolean
        get() = (fusedHeading != null && (headingConfidence ?: 0f) > 0.5f) ||
                (heading != null && isMoving)
}

enum class PositionSource {
    GPS,
    FUSED_LOCATION,
    ARCORE_GEOSPATIAL,
    NETWORK,
    UNKNOWN
}
