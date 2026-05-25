package com.navblind.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "preferences")
data class LocalPreference(
    @PrimaryKey val id: Int = 1,  // 단일 로우
    @ColumnInfo(name = "speech_rate") val speechRate: Float = 1.0f,
    @ColumnInfo(name = "speech_pitch") val speechPitch: Float = 1.0f,
    @ColumnInfo(name = "alert_distance_meters") val alertDistanceMeters: Float = 3.0f,
    @ColumnInfo(name = "vibration_enabled") val vibrationEnabled: Boolean = true,
    @ColumnInfo(name = "avoid_stairs") val avoidStairs: Boolean = false,
    @ColumnInfo(name = "avoid_steep_slopes") val avoidSteepSlopes: Boolean = false,
    @ColumnInfo(name = "high_contrast_mode") val highContrastMode: Boolean = false,
    @ColumnInfo(name = "language") val language: String = "ko"
)
