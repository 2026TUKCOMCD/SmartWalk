package com.smartwalker.data.remote

fun interface RoadSnapApi {
    suspend fun getNearestRoad(lat: Double, lng: Double): NearestResponse
}
