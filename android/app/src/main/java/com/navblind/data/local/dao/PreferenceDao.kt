package com.navblind.data.local.dao

import androidx.room.*
import com.navblind.data.local.entity.LocalPreference
import kotlinx.coroutines.flow.Flow

@Dao
interface PreferenceDao {
    @Query("SELECT * FROM preferences WHERE id = 1")
    fun observe(): Flow<LocalPreference?>

    @Query("SELECT * FROM preferences WHERE id = 1")
    suspend fun get(): LocalPreference?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(preference: LocalPreference)
}
