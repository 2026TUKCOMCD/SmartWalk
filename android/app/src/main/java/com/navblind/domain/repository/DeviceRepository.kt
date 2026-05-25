package com.navblind.domain.repository

import com.navblind.domain.model.SmartGlasses
import java.util.UUID

interface DeviceRepository {
    suspend fun register(deviceId: String, ipAddress: String? = null): Result<SmartGlasses>
    suspend fun list(): Result<List<SmartGlasses>>
    suspend fun updateConnection(id: UUID, ipAddress: String, batteryLevel: Int?): Result<SmartGlasses>
    suspend fun disconnect(id: UUID): Result<Unit>
    suspend fun delete(id: UUID): Result<Unit>
}
