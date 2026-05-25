package com.navblind.data.repository

import com.navblind.data.remote.DeviceApi
import com.navblind.data.remote.DeviceResponse
import com.navblind.data.remote.RegisterDeviceRequest
import com.navblind.data.remote.UpdateConnectionRequest
import com.navblind.domain.model.SmartGlasses
import com.navblind.domain.repository.DeviceRepository
import java.util.UUID
import javax.inject.Inject

class DeviceRepositoryImpl @Inject constructor(
    private val api: DeviceApi
) : DeviceRepository {

    override suspend fun register(deviceId: String, ipAddress: String?): Result<SmartGlasses> =
        runCatching { api.register(RegisterDeviceRequest(deviceId, ipAddress = ipAddress)).toDomain() }

    override suspend fun list(): Result<List<SmartGlasses>> =
        runCatching { api.list().map { it.toDomain() } }

    override suspend fun updateConnection(id: UUID, ipAddress: String, batteryLevel: Int?): Result<SmartGlasses> =
        runCatching { api.updateConnection(id, UpdateConnectionRequest(ipAddress, batteryLevel)).toDomain() }

    override suspend fun disconnect(id: UUID): Result<Unit> =
        runCatching { api.disconnect(id) }

    override suspend fun delete(id: UUID): Result<Unit> =
        runCatching { api.delete(id) }

    private fun DeviceResponse.toDomain() = SmartGlasses(
        id = id,
        deviceId = deviceId,
        deviceName = deviceName,
        ipAddress = ipAddress,
        streamPort = streamPort,
        status = when (status) {
            "CONNECTED" -> SmartGlasses.GlassesStatus.CONNECTED
            "DISCONNECTED" -> SmartGlasses.GlassesStatus.DISCONNECTED
            else -> SmartGlasses.GlassesStatus.REGISTERED
        },
        firmwareVersion = firmwareVersion,
        batteryLevel = batteryLevel,
        streamUrl = streamUrl
    )
}
