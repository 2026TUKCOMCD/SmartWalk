package com.navblind.data.remote

import retrofit2.http.Body
import retrofit2.http.POST
import java.util.UUID

interface NavigationApi {

    @POST("navigation/route")
    suspend fun calculateRoute(@Body request: RouteRequest): RouteResponse

    @POST("navigation/reroute")
    suspend fun reroute(@Body request: RerouteRequest): RouteResponse
}

data class RouteRequest(
    val originLat: Double,
    val originLng: Double,
    val destLat: Double,
    val destLng: Double,
    val destName: String? = null,
    val usePreferences: Boolean = true
)

data class RerouteRequest(
    val sessionId: UUID,
    val currentLat: Double,
    val currentLng: Double
)

data class RouteResponse(
    val sessionId: UUID,
    val distance: Int,
    val duration: Int,
    val waypoints: List<WaypointDto>,
    val instructions: List<InstructionDto>
)

data class WaypointDto(
    val lat: Double,
    val lng: Double,
    val name: String?
)

data class InstructionDto(
    val step: Int,
    val type: String?,
    val modifier: String?,
    val text: String,
    val distance: Int,
    val location: WaypointDto
)
