package com.navblind.domain.model

data class FusedPosition(
    val coordinate: Coordinate,
    val accuracy: Float,
    val altitude: Double?,
    val heading: Float?,
    val source: PositionSource,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isHighAccuracy: Boolean
        get() = accuracy < 10f // Less than 10 meters

    val isAcceptable: Boolean
        get() = accuracy < 50f // Less than 50 meters
}

enum class PositionSource {
    GPS,
    FUSED_LOCATION,
    ARCORE_GEOSPATIAL,
    NETWORK,
    UNKNOWN
}
