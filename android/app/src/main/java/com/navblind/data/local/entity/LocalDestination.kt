package com.navblind.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "destinations")
data class LocalDestination(
    @PrimaryKey val id: UUID,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "address") val address: String?,
    @ColumnInfo(name = "label") val label: String?,
    @ColumnInfo(name = "use_count") val useCount: Int = 0,
    @ColumnInfo(name = "synced") val synced: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
