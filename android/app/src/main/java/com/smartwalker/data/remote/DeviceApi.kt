package com.smartwalker.data.remote

import retrofit2.http.*
import java.util.UUID

interface DeviceApi {

    @POST("devices")
    suspend fun register(@Body request: RegisterDeviceRequest): DeviceResponse

    @GET("devices")
    suspend fun list(): List<DeviceResponse>

    @GET("devices/{id}")
    suspend fun get(@Path("id") id: UUID): DeviceResponse

    @PATCH("devices/{id}/connection")
    suspend fun updateConnection(
        @Path("id") id: UUID,
        @Body request: UpdateConnectionRequest
    ): DeviceResponse

    @DELETE("devices/{id}/connection")
    suspend fun disconnect(@Path("id") id: UUID)

    @DELETE("devices/{id}")
    suspend fun delete(@Path("id") id: UUID)
}

data class RegisterDeviceRequest(
    val deviceId: String,
    val deviceName: String? = null,
    val ipAddress: String? = null,
    val streamPort: Int? = null,
    val firmwareVersion: String? = null
)

data class UpdateConnectionRequest(
    val ipAddress: String,
    val batteryLevel: Int? = null
)

data class DeviceResponse(
    val id: UUID,
    val deviceId: String,
    val deviceName: String,
    val ipAddress: String?,
    val streamPort: Int,
    val status: String,
    val firmwareVersion: String?,
    val batteryLevel: Int?,
    val streamUrl: String?,
    val lastConnected: String?,
    val createdAt: String?
)
