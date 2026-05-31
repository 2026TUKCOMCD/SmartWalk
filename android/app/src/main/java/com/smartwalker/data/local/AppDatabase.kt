package com.smartwalker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.smartwalker.data.local.dao.DestinationDao
import com.smartwalker.data.local.dao.PreferenceDao
import com.smartwalker.data.local.entity.LocalDestination
import com.smartwalker.data.local.entity.LocalPreference

@Database(
    entities = [LocalDestination::class, LocalPreference::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun destinationDao(): DestinationDao
    abstract fun preferenceDao(): PreferenceDao
}
