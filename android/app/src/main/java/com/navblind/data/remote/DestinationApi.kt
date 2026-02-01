package com.navblind.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface DestinationApi {

    @GET("destinations/search")
    suspend fun search(
        @Query("query") query: String,
        @Query("lat") lat: Double? = null,
        @Query("lng") lng: Double? = null,
        @Query("limit") limit: Int = 10
    ): SearchResponse
}

data class SearchResponse(
    val results: List<SearchResultDto>
)

data class SearchResultDto(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val distance: Int?,
    val category: String?
)
