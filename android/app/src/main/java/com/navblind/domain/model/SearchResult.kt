package com.navblind.domain.model

data class SearchResult(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val distance: Int?,
    val category: String?
) {
    fun toCoordinate() = Coordinate(latitude, longitude)

    val distanceFormatted: String?
        get() = distance?.let { dist ->
            when {
                dist < 1000 -> "${dist}m"
                else -> String.format("%.1fkm", dist / 1000.0)
            }
        }
}
