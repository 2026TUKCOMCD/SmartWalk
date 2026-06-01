package com.smartwalker.data.local.dao

import androidx.room.*
import com.smartwalker.data.local.entity.LocalDestination
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface DestinationDao {
    @Query("SELECT * FROM destinations ORDER BY use_count DESC, created_at DESC")
    fun observeAll(): Flow<List<LocalDestination>>

    @Query("SELECT * FROM destinations ORDER BY use_count DESC, created_at DESC")
    suspend fun getAll(): List<LocalDestination>

    @Query("SELECT * FROM destinations WHERE id = :id")
    suspend fun getById(id: UUID): LocalDestination?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(destination: LocalDestination)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(destinations: List<LocalDestination>)

    @Update
    suspend fun update(destination: LocalDestination)

    @Query("DELETE FROM destinations WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("UPDATE destinations SET use_count = use_count + 1 WHERE id = :id")
    suspend fun incrementUseCount(id: UUID)

    @Query("SELECT * FROM destinations WHERE synced = 0")
    suspend fun getUnsynced(): List<LocalDestination>
}
