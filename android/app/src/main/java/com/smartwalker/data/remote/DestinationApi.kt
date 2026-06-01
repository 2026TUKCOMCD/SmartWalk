package com.smartwalker.data.remote

import retrofit2.http.*
import java.util.UUID

interface DestinationApi {

    @GET("destinations/search")
    suspend fun search(
        @Query("query") query: String,
        @Query("lat") lat: Double? = null,
        @Query("lng") lng: Double? = null,
        @Query("limit") limit: Int = 10
    ): SearchResponse

    @GET("destinations")
    suspend fun getDestinations(): List<SavedDestinationDto>

    @POST("destinations")
    suspend fun createDestination(@Body request: CreateDestinationRequest): SavedDestinationDto

    @DELETE("destinations/{id}")
    suspend fun deleteDestination(@Path("id") id: UUID)
}

data class SearchResponse(val results: List<SearchResultDto>)

data class SearchResultDto(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val distance: Int?,
    val category: String?
)

data class SavedDestinationDto(
    val id: UUID,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val label: String?,
    val useCount: Int
)

data class CreateDestinationRequest(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val label: String? = null
)
