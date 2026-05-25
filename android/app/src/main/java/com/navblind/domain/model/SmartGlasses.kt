package com.navblind.domain.model

import java.util.UUID

data class SmartGlasses(
    val id: UUID,
    val deviceId: String,
    val deviceName: String,
    val ipAddress: String?,
    val streamPort: Int,
    val status: GlassesStatus,
    val firmwareVersion: String?,
    val batteryLevel: Int?,
    val streamUrl: String?
) {
    enum class GlassesStatus { REGISTERED, CONNECTED, DISCONNECTED }

    val isConnected: Boolean get() = status == GlassesStatus.CONNECTED
    val isBatteryLow: Boolean get() = batteryLevel != null && batteryLevel <= 20
}
