package com.antigravity.healthagent.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.DayActivityDao
import com.antigravity.healthagent.data.local.dao.CustomStreetDao
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.CustomStreet

@Database(entities = [House::class, DayActivity::class, CustomStreet::class], version = 14, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun houseDao(): HouseDao
    abstract fun dayActivityDao(): DayActivityDao
    abstract fun customStreetDao(): CustomStreetDao

    companion object {
        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Create the new table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `day_activities_new` (
                        `date` TEXT NOT NULL, 
                        `status` TEXT NOT NULL, 
                        `isClosed` INTEGER NOT NULL, 
                        `agentName` TEXT NOT NULL DEFAULT '', 
                        PRIMARY KEY(`date`, `agentName`)
                    )
                """)
                // 2. Copy data
                database.execSQL("""
                    INSERT INTO day_activities_new (date, status, isClosed, agentName)
                    SELECT date, status, isClosed, '' FROM day_activities
                """)
                // 3. Remove old table
                database.execSQL("DROP TABLE day_activities")
                // 4. Rename new table
                database.execSQL("ALTER TABLE day_activities_new RENAME TO day_activities")
            }
        }
        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `custom_streets` (
                        `name` TEXT NOT NULL, 
                        `bairro` TEXT NOT NULL, 
                        PRIMARY KEY(`name`)
                    )
                """)
            }
        }
    }
}
