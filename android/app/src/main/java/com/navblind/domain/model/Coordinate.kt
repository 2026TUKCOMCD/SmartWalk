package com.navblind.domain.model

data class Coordinate(
    val latitude: Double,
    val longitude: Double
) {
    companion object {
        // Seoul Station (default location for demo)
        val DEFAULT = Coordinate(37.5547, 126.9706)
    }

    fun distanceTo(other: Coordinate): Double {
        val R = 6371000.0 // Earth's radius in meters

        val lat1Rad = Math.toRadians(latitude)
        val lat2Rad = Math.toRadians(other.latitude)
        val deltaLat = Math.toRadians(other.latitude - latitude)
        val deltaLng = Math.toRadians(other.longitude - longitude)

        val a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return R * c
    }
}
