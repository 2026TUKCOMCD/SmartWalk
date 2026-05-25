package com.navblind.data.remote

import retrofit2.http.*

interface UserApi {

    @GET("users/me")
    suspend fun getMe(): UserResponse

    @PATCH("users/me")
    suspend fun updateMe(@Body request: UpdateUserRequest): UserResponse

    @DELETE("users/me")
    suspend fun deleteMe()

    @GET("users/me/preferences")
    suspend fun getPreferences(): PreferenceDto

    @PUT("users/me/preferences")
    suspend fun updatePreferences(@Body dto: PreferenceDto): PreferenceDto
}

data class UserResponse(
    val id: String,
    val phoneNumber: String?,
    val displayName: String?,
    val createdAt: String?,
    val lastLogin: String?,
    val preference: PreferenceDto?
)

data class UpdateUserRequest(val displayName: String)

data class PreferenceDto(
    val speechRate: Float? = null,
    val speechPitch: Float? = null,
    val alertDistanceMeters: Float? = null,
    val vibrationEnabled: Boolean? = null,
    val avoidStairs: Boolean? = null,
    val avoidSteepSlopes: Boolean? = null,
    val highContrastMode: Boolean? = null,
    val language: String? = null
)
