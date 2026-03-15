package com.navblind.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.UUID

interface NavigationApi {

    @POST("navigation/route")
    suspend fun calculateRoute(@Body request: RouteRequest): RouteResponse

    @POST("navigation/reroute")
    suspend fun reroute(@Body request: RerouteRequest): RouteResponse

    /**
     * 좌표를 가장 가까운 도로에 snap합니다.
     * VPS/GPS 좌표를 OSM 도로망에 정합하는 데 사용됩니다.
     */
    @GET("navigation/nearest")
    suspend fun getNearestRoad(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double
    ): NearestResponse
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

/**
 * OSRM nearest API 응답
 */
data class NearestResponse(
    /** 원래 요청한 위도 */
    val originalLat: Double,
    /** 원래 요청한 경도 */
    val originalLng: Double,
    /** 도로에 snap된 위도 */
    val snappedLat: Double,
    /** 도로에 snap된 경도 */
    val snappedLng: Double,
    /** 원래 좌표에서 snap된 좌표까지의 거리 (미터) */
    val distance: Double,
    /** 도로 이름 (있는 경우) */
    val roadName: String?,
    /** 도로 위에 있는지 여부 */
    val isOnRoad: Boolean
)
