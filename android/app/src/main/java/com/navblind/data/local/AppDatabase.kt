package com.navblind.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.navblind.data.local.dao.DestinationDao
import com.navblind.data.local.dao.PreferenceDao
import com.navblind.data.local.entity.LocalDestination
import com.navblind.data.local.entity.LocalPreference

@Database(
    entities = [LocalDestination::class, LocalPreference::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun destinationDao(): DestinationDao
    abstract fun preferenceDao(): PreferenceDao
}
