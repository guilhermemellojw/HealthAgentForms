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
import com.antigravity.healthagent.data.local.model.Tombstone

@Database(entities = [House::class, DayActivity::class, CustomStreet::class, Tombstone::class], version = 27, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun houseDao(): HouseDao
    abstract fun dayActivityDao(): DayActivityDao
    abstract fun customStreetDao(): CustomStreetDao
    abstract fun tombstoneDao(): com.antigravity.healthagent.data.local.dao.TombstoneDao

    companion object {
        val MIGRATION_26_27 = object : androidx.room.migration.Migration(26, 27) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performFullNuclearRebuild(database)
            }
        }

        val MIGRATION_25_26 = object : androidx.room.migration.Migration(25, 26) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performFullNuclearRebuild(database)
            }
        }

        val MIGRATION_24_25 = object : androidx.room.migration.Migration(24, 25) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performFullNuclearRebuild(database)
            }
        }

        val MIGRATION_23_25 = object : androidx.room.migration.Migration(23, 25) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performFullNuclearRebuild(database)
            }
        }

        val MIGRATION_23_24 = object : androidx.room.migration.Migration(23, 24) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Perform the same full nuclear rebuild to ensure v24 schema is clean and hardened
                performFullNuclearRebuild(database)
            }
        }

        val MIGRATION_22_24 = object : androidx.room.migration.Migration(22, 24) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performFullNuclearRebuild(database)
            }
        }

        val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performFullNuclearRebuild(database)
            }
        }

        val MIGRATION_21_23 = object : androidx.room.migration.Migration(21, 23) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performFullNuclearRebuild(database)
            }
        }

        val MIGRATION_20_23 = object : androidx.room.migration.Migration(20, 23) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performFullNuclearRebuild(database)
            }
        }

        val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performFullNuclearRebuild(database)
            }
        }

        val MIGRATION_20_22 = object : androidx.room.migration.Migration(20, 22) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performFullNuclearRebuild(database)
            }
        }

        val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performFullNuclearRebuild(database)
            }
        }

        private fun performFullNuclearRebuild(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            android.util.Log.w("AppDatabase", "NUCLEAR REBUILD STARTING (Schema hardening)...")
            database.execSQL("PRAGMA foreign_keys=OFF")
            
            try {
                 // 1. Safeguard all columns (v3 definitive logic)
                 val houseColumnsToGuard = listOf(
                    "sequence" to "INTEGER NOT NULL DEFAULT 0",
                    "complement" to "INTEGER NOT NULL DEFAULT 0",
                    "propertyType" to "TEXT NOT NULL DEFAULT 'EMPTY'",
                    "situation" to "TEXT NOT NULL DEFAULT 'EMPTY'",
                    "municipio" to "TEXT NOT NULL DEFAULT 'Bom Jardim'",
                    "categoria" to "TEXT NOT NULL DEFAULT 'BRR'",
                    "zona" to "TEXT NOT NULL DEFAULT 'URB'",
                    "tipo" to "INTEGER NOT NULL DEFAULT 2",
                    "ciclo" to "TEXT NOT NULL DEFAULT '1º'",
                    "atividade" to "INTEGER NOT NULL DEFAULT 4",
                    "a1" to "INTEGER NOT NULL DEFAULT 0",
                    "a2" to "INTEGER NOT NULL DEFAULT 0",
                    "b" to "INTEGER NOT NULL DEFAULT 0",
                    "c" to "INTEGER NOT NULL DEFAULT 0",
                    "d1" to "INTEGER NOT NULL DEFAULT 0",
                    "d2" to "INTEGER NOT NULL DEFAULT 0",
                    "e" to "INTEGER NOT NULL DEFAULT 0",
                    "eliminados" to "INTEGER NOT NULL DEFAULT 0",
                    "larvicida" to "REAL NOT NULL DEFAULT 0.0",
                    "comFoco" to "INTEGER NOT NULL DEFAULT 0",
                    "localidadeConcluida" to "INTEGER NOT NULL DEFAULT 0",
                    "blockSequence" to "TEXT NOT NULL DEFAULT ''",
                    "quarteiraoConcluido" to "INTEGER NOT NULL DEFAULT 0",
                    "listOrder" to "INTEGER NOT NULL DEFAULT 0",
                    "visitSegment" to "INTEGER NOT NULL DEFAULT 0",
                    "agentUid" to "TEXT NOT NULL DEFAULT ''",
                    "observation" to "TEXT NOT NULL DEFAULT ''",
                    "createdAt" to "INTEGER NOT NULL DEFAULT 0",
                    "isSynced" to "INTEGER NOT NULL DEFAULT 0",
                    "latitude" to "REAL",
                    "longitude" to "REAL",
                    "focusCaptureTime" to "INTEGER",
                    "lastUpdated" to "INTEGER NOT NULL DEFAULT 0"
                )
                
                houseColumnsToGuard.forEach { (col, type) ->
                    try { database.execSQL("ALTER TABLE houses ADD COLUMN `$col` $type") } catch(e: Exception) {}
                }

                try { database.execSQL("ALTER TABLE day_activities ADD COLUMN agentUid TEXT NOT NULL DEFAULT ''") } catch(e: Exception) {}
                try { database.execSQL("ALTER TABLE day_activities ADD COLUMN isSynced INTEGER NOT NULL DEFAULT 0") } catch(e: Exception) {}
                try { database.execSQL("ALTER TABLE day_activities ADD COLUMN lastUpdated INTEGER NOT NULL DEFAULT 0") } catch(e: Exception) {}
                try { database.execSQL("ALTER TABLE day_activities ADD COLUMN isManualUnlock INTEGER NOT NULL DEFAULT 0") } catch(e: Exception) {}

                // 2. Rebuild houses
                android.util.Log.d("AppDatabase", "Creating houses_new table...")
                database.execSQL("DROP TABLE IF EXISTS `houses_new`")
                database.execSQL("""
                    CREATE TABLE `houses_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `blockNumber` TEXT NOT NULL, 
                        `streetName` TEXT NOT NULL, 
                        `number` TEXT NOT NULL, 
                        `sequence` INTEGER NOT NULL, 
                        `complement` INTEGER NOT NULL, 
                        `propertyType` TEXT NOT NULL, 
                        `situation` TEXT NOT NULL, 
                        `municipio` TEXT NOT NULL DEFAULT 'Bom Jardim', 
                        `bairro` TEXT NOT NULL, 
                        `categoria` TEXT NOT NULL DEFAULT 'BRR', 
                        `zona` TEXT NOT NULL DEFAULT 'URB', 
                        `tipo` INTEGER NOT NULL DEFAULT 2, 
                        `data` TEXT NOT NULL, 
                        `ciclo` TEXT NOT NULL DEFAULT '1º', 
                        `atividade` INTEGER NOT NULL DEFAULT 4, 
                        `agentName` TEXT NOT NULL, 
                        `a1` INTEGER NOT NULL DEFAULT 0, 
                        `a2` INTEGER NOT NULL DEFAULT 0, 
                        `b` INTEGER NOT NULL DEFAULT 0, 
                        `c` INTEGER NOT NULL DEFAULT 0, 
                        `d1` INTEGER NOT NULL DEFAULT 0, 
                        `d2` INTEGER NOT NULL DEFAULT 0, 
                        `e` INTEGER NOT NULL DEFAULT 0, 
                        `eliminados` INTEGER NOT NULL DEFAULT 0, 
                        `larvicida` REAL NOT NULL DEFAULT 0.0, 
                        `comFoco` INTEGER NOT NULL DEFAULT 0, 
                        `localidadeConcluida` INTEGER NOT NULL DEFAULT 0, 
                        `blockSequence` TEXT NOT NULL DEFAULT '', 
                        `quarteiraoConcluido` INTEGER NOT NULL DEFAULT 0, 
                        `listOrder` INTEGER NOT NULL DEFAULT 0, 
                        `visitSegment` INTEGER NOT NULL DEFAULT 0, 
                        `agentUid` TEXT NOT NULL DEFAULT '', 
                        `observation` TEXT NOT NULL DEFAULT '', 
                        `createdAt` INTEGER NOT NULL DEFAULT 0, 
                        `isSynced` INTEGER NOT NULL DEFAULT 0, 
                        `latitude` REAL, 
                        `longitude` REAL, 
                        `focusCaptureTime` INTEGER, 
                        `lastUpdated` INTEGER NOT NULL DEFAULT 0
                    )
                """)

                android.util.Log.d("AppDatabase", "Migrating data with deduplication...")
                database.execSQL("""
                    INSERT INTO houses_new (
                        blockNumber, streetName, number, sequence, complement, propertyType, situation,
                        municipio, bairro, categoria, zona, tipo, `data`, ciclo, atividade, agentName,
                        a1, a2, b, c, d1, d2, e, eliminados, larvicida, comFoco, localidadeConcluida,
                        blockSequence, quarteiraoConcluido, listOrder, visitSegment, agentUid,
                        observation, createdAt, isSynced, latitude, longitude, focusCaptureTime, lastUpdated
                    )
                    SELECT 
                        UPPER(TRIM(COALESCE(CAST(blockNumber AS TEXT), ''))), UPPER(TRIM(COALESCE(CAST(streetName AS TEXT), ''))), UPPER(TRIM(COALESCE(CAST(number AS TEXT), ''))), COALESCE(CAST(sequence AS INTEGER), 0), COALESCE(CAST(complement AS INTEGER), 0), COALESCE(CAST(propertyType AS TEXT), 'EMPTY'), COALESCE(CAST(situation AS TEXT), 'EMPTY'),
                        COALESCE(CAST(municipio AS TEXT), 'Bom Jardim'), UPPER(TRIM(COALESCE(CAST(bairro AS TEXT), ''))), COALESCE(CAST(categoria AS TEXT), 'BRR'), COALESCE(CAST(zona AS TEXT), 'URB'), COALESCE(CAST(tipo AS INTEGER), 2), COALESCE(CAST(`data` AS TEXT), ''), COALESCE(CAST(ciclo AS TEXT), '1º'), COALESCE(CAST(atividade AS INTEGER), 4), UPPER(TRIM(COALESCE(CAST(agentName AS TEXT), ''))),
                        COALESCE(CAST(a1 AS INTEGER), 0), COALESCE(CAST(a2 AS INTEGER), 0), COALESCE(CAST(b AS INTEGER), 0), COALESCE(CAST(c AS INTEGER), 0), COALESCE(CAST(d1 AS INTEGER), 0), COALESCE(CAST(d2 AS INTEGER), 0), COALESCE(CAST(e AS INTEGER), 0), COALESCE(CAST(eliminados AS INTEGER), 0), COALESCE(CAST(larvicida AS REAL), 0.0), COALESCE(CAST(comFoco AS INTEGER), 0), COALESCE(CAST(localidadeConcluida AS INTEGER), 0), 
                        UPPER(TRIM(COALESCE(CAST(blockSequence AS TEXT), ''))), COALESCE(CAST(quarteiraoConcluido AS INTEGER), 0), COALESCE(CAST(listOrder AS INTEGER), 0), COALESCE(CAST(visitSegment AS INTEGER), 0), COALESCE(MAX(CAST(agentUid AS TEXT)), ''),
                        COALESCE(CAST(observation AS TEXT), ''), COALESCE(CAST(createdAt AS INTEGER), 0), COALESCE(MAX(CAST(isSynced AS INTEGER)), 0), latitude, longitude, focusCaptureTime, COALESCE(MAX(CAST(lastUpdated AS INTEGER)), 0)
                    FROM houses
                    GROUP BY 
                        UPPER(TRIM(COALESCE(CAST(agentName AS TEXT), ''))), COALESCE(CAST(`data` AS TEXT), ''), UPPER(TRIM(COALESCE(CAST(blockNumber AS TEXT), ''))), UPPER(TRIM(COALESCE(CAST(blockSequence AS TEXT), ''))), 
                        UPPER(TRIM(COALESCE(CAST(streetName AS TEXT), ''))), UPPER(TRIM(COALESCE(CAST(number AS TEXT), ''))), COALESCE(CAST(sequence AS INTEGER), 0), COALESCE(CAST(complement AS INTEGER), 0), UPPER(TRIM(COALESCE(CAST(bairro AS TEXT), ''))), COALESCE(CAST(visitSegment AS INTEGER), 0)
                """)

                android.util.Log.d("AppDatabase", "Finalizing swap...")
                database.execSQL("DROP TABLE houses")
                database.execSQL("ALTER TABLE houses_new RENAME TO houses")
                
                database.execSQL("DROP INDEX IF EXISTS `index_houses_agentUid_agentName_data_blockNumber_blockSequence_streetName_number_sequence_complement_bairro_visitSegment` ")
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_houses_agentUid_agentName_data_blockNumber_blockSequence_streetName_number_sequence_complement_bairro_visitSegment` 
                    ON `houses` (`agentUid`, `agentName`, `data`, `blockNumber`, `blockSequence`, `streetName`, `number`, `sequence`, `complement`, `bairro`, `visitSegment`)
                """)
                
                database.execSQL("DROP INDEX IF EXISTS `index_houses_data_agentUid` ")
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS `index_houses_data_agentUid` 
                    ON `houses` (`data`, `agentUid`)
                """)

                // 3. Rebuild day_activities
                android.util.Log.d("AppDatabase", "Rebuilding day_activities...")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `day_activities_new` (
                        `date` TEXT NOT NULL, 
                        `status` TEXT NOT NULL, 
                        `isClosed` INTEGER NOT NULL, 
                        `agentName` TEXT NOT NULL, 
                        `agentUid` TEXT NOT NULL DEFAULT '', 
                        `isSynced` INTEGER NOT NULL DEFAULT 0, 
                        `lastUpdated` INTEGER NOT NULL DEFAULT 0, 
                        `isManualUnlock` INTEGER NOT NULL DEFAULT 0, 
                        PRIMARY KEY(`date`, `agentName`, `agentUid`)
                    )
                """)

                database.execSQL("""
                    INSERT OR IGNORE INTO day_activities_new (date, status, isClosed, agentName, agentUid, isSynced, lastUpdated, isManualUnlock)
                    SELECT `date`, status, isClosed, UPPER(TRIM(agentName)), agentUid, isSynced, lastUpdated, COALESCE(isManualUnlock, 0) FROM day_activities
                """)

                database.execSQL("DROP TABLE day_activities")
                database.execSQL("ALTER TABLE day_activities_new RENAME TO day_activities")

                // 4. Rebuild custom_streets
                android.util.Log.d("AppDatabase", "Rebuilding custom_streets...")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `custom_streets_new` (
                        `name` TEXT NOT NULL, 
                        `bairro` TEXT NOT NULL, 
                        PRIMARY KEY(`name`)
                    )
                """)
                database.execSQL("INSERT OR IGNORE INTO custom_streets_new (name, bairro) SELECT UPPER(TRIM(name)), UPPER(TRIM(bairro)) FROM custom_streets")
                database.execSQL("DROP TABLE custom_streets")
                database.execSQL("ALTER TABLE custom_streets_new RENAME TO custom_streets")

                // 5. Rebuild tombstones
                android.util.Log.d("AppDatabase", "Rebuilding tombstones...")
                database.execSQL("DROP TABLE IF EXISTS `tombstones_new`")
                database.execSQL("""
                    CREATE TABLE `tombstones_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `type` TEXT NOT NULL, 
                        `naturalKey` TEXT NOT NULL, 
                        `agentName` TEXT NOT NULL DEFAULT '', 
                        `agentUid` TEXT NOT NULL DEFAULT '', 
                        `deletedAt` INTEGER NOT NULL
                    )
                """)
                database.execSQL("INSERT INTO tombstones_new (id, type, naturalKey, deletedAt) SELECT id, type, naturalKey, deletedAt FROM tombstones")
                database.execSQL("DROP TABLE tombstones")
                database.execSQL("ALTER TABLE tombstones_new RENAME TO tombstones")

                android.util.Log.i("AppDatabase", "NUCLEAR REBUILD COMPLETED SUCCESSFULLY.")
            } catch (e: Exception) {
                android.util.Log.e("AppDatabase", "CRITICAL: NUCLEAR REBUILD FAILED", e)
                throw e
            } finally {
                database.execSQL("PRAGMA foreign_keys=ON")
            }
        }
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
        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // We use a fixed timestamp for existing records to ensure stability, 
                // but since these are unique in the local DB already, they won't collide with each other.
                // Using 0L or a fixed old timestamp is safer for historical records.
                database.execSQL("ALTER TABLE houses ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE houses ADD COLUMN visitSegment INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Add sync fields to houses
                database.execSQL("ALTER TABLE houses ADD COLUMN isSynced INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE houses ADD COLUMN lastUpdated INTEGER NOT NULL DEFAULT 0")
                
                // 2. Add sync fields to day_activities
                database.execSQL("ALTER TABLE day_activities ADD COLUMN isSynced INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE day_activities ADD COLUMN lastUpdated INTEGER NOT NULL DEFAULT 0")
                
                // 3. Create tombstones table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `tombstones` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `type` TEXT NOT NULL, 
                        `naturalKey` TEXT NOT NULL, 
                        `deletedAt` INTEGER NOT NULL
                    )
                """)
            }
        }
        val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE day_activities ADD COLUMN agentUid TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Add agentUid to houses table
                database.execSQL("ALTER TABLE houses ADD COLUMN agentUid TEXT NOT NULL DEFAULT ''")
                
                // 2. Change Primary Key of day_activities (Requires rebuild)
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `day_activities_new` (
                        `date` TEXT NOT NULL, 
                        `status` TEXT NOT NULL, 
                        `isClosed` INTEGER NOT NULL, 
                        `agentName` TEXT NOT NULL, 
                        `agentUid` TEXT NOT NULL DEFAULT '', 
                        `isSynced` INTEGER NOT NULL DEFAULT 0,
                        `lastUpdated` INTEGER NOT NULL,
                        PRIMARY KEY(`date`, `agentName`, `agentUid`)
                    )
                """)
                database.execSQL("""
                    INSERT INTO day_activities_new (date, status, isClosed, agentName, agentUid, isSynced, lastUpdated)
                    SELECT date, status, isClosed, agentName, agentUid, isSynced, lastUpdated FROM day_activities
                """)
                database.execSQL("DROP TABLE day_activities")
                database.execSQL("ALTER TABLE day_activities_new RENAME TO day_activities")
            }
        }
        val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE houses ADD COLUMN observation TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE houses ADD COLUMN latitude REAL")
                database.execSQL("ALTER TABLE houses ADD COLUMN longitude REAL")
                database.execSQL("ALTER TABLE houses ADD COLUMN focusCaptureTime INTEGER")
            }
        }
    }
}
