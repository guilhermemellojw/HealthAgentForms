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

@Database(
    entities = [
        House::class, 
        DayActivity::class, 
        CustomStreet::class, 
        Tombstone::class,
        com.antigravity.healthagent.data.local.model.CachedAgent::class,
        com.antigravity.healthagent.data.local.model.CachedAgentSummary::class
    ], 
    version = 39, 
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun houseDao(): HouseDao
    abstract fun dayActivityDao(): DayActivityDao
    abstract fun customStreetDao(): CustomStreetDao
    abstract fun tombstoneDao(): com.antigravity.healthagent.data.local.dao.TombstoneDao
    abstract fun agentCacheDao(): com.antigravity.healthagent.data.local.dao.AgentCacheDao

    companion object {
        val MIGRATION_37_38 = object : androidx.room.migration.Migration(37, 38) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Clean Code refactoring: @Embedded TreatmentData, DailyContext, GeoCapture.
                // No SQLite schema changes — only Room identity hash update.
                android.util.Log.i("AppDatabase", "MIGRATION 37->38: Clean Code @Embedded refactoring (no schema change).")
            }
        }

        val MIGRATION_38_39 = object : androidx.room.migration.Migration(38, 39) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // v38→39: Drop the UNIQUE constraint on the main index; recreate as non-unique.
                // This allows multiple houses with the same compound key (necessary when
                // the same house appears on different days or from different data sources).
                database.execSQL("DROP INDEX IF EXISTS `index_houses_agentUid_agentName_data_blockNumber_blockSequence_streetName_number_sequence_complement_bairro_visitSegment`")
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS `index_houses_agentUid_agentName_data_blockNumber_blockSequence_streetName_number_sequence_complement_bairro_visitSegment` 
                    ON `houses` (`agentUid`, `agentName`, `data`, `blockNumber`, `blockSequence`, `streetName`, `number`, `sequence`, `complement`, `bairro`, `visitSegment`)
                """)
            }
        }

        val MIGRATION_36_37 = object : androidx.room.migration.Migration(36, 37) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Heal uppercased agentUid from previous nuclear rebuilds.
                // Since we cannot know the correct mixed-case UID at migration time,
                // we wipe and let sync re-pull from Firestore with correct UIDs.
                android.util.Log.w("AppDatabase", "MIGRATION 36->37: Clearing houses with uppercased UIDs for re-sync...")
                database.execSQL("DELETE FROM houses WHERE agentUid != '' AND agentUid = UPPER(agentUid) AND agentUid != LOWER(agentUid)")
                android.util.Log.w("AppDatabase", "MIGRATION 36->37: Complete. Sync will restore data with correct UIDs.")
            }
        }

        val MIGRATION_35_36 = object : androidx.room.migration.Migration(35, 36) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Incremental migration replacing nuclear rebuild.
                // 1. Ensure all columns exist on houses (idempotent ALTER TABLE).
                addMissingHouseColumns(database)
                // 2. Ensure day_activities columns exist.
                addMissingDayActivityColumns(database)
                // 3. Normalize text columns in-place (UPPER/TRIM, REPLACE date slashes).
                normalizeHouseTextColumns(database)
                normalizeDayActivityTextColumns(database)
                // 4. Deduplicate houses using temp table (no DROP of main table).
                deduplicateHouses(database, includeVisitSegmentInIndex = true)
                // 5. Recreate indices with visitSegment (v36 schema).
                recreateHouseIndices(database, includeVisitSegmentInIndex = true)
                // 6. Rebuild day_activities to modern schema (temp table, no DROP main).
                rebuildDayActivitiesIncremental(database)
                // 7. Rebuild custom_streets (normalize case).
                rebuildCustomStreetsIncremental(database)
                // 8. Rebuild tombstones (add agentName/agentUid, normalize).
                rebuildTombstonesIncremental(database)
            }
        }

        // ===== Incremental migration helpers (v35→36) =====

        private fun addMissingHouseColumns(database: androidx.sqlite.db.SupportSQLiteDatabase) {
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
                "editedByAdmin" to "INTEGER NOT NULL DEFAULT 0",
                "latitude" to "REAL",
                "longitude" to "REAL",
                "focusCaptureTime" to "INTEGER",
                "lastUpdated" to "INTEGER NOT NULL DEFAULT 0"
            )
            houseColumnsToGuard.forEach { (col, type) ->
                try { database.execSQL("ALTER TABLE houses ADD COLUMN `$col` $type") }
                catch (e: Exception) { if (e.message?.contains("duplicate column name", true) != true) android.util.Log.w("AppDatabase", "Error adding house col $col: ${e.message}") }
            }
        }

        private fun addMissingDayActivityColumns(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            try { database.execSQL("ALTER TABLE day_activities ADD COLUMN agentUid TEXT NOT NULL DEFAULT ''") } catch (e: Exception) { if (e.message?.contains("duplicate column name", true) != true) android.util.Log.w("AppDatabase", "Error adding activities col agentUid: ${e.message}") }
            try { database.execSQL("ALTER TABLE day_activities ADD COLUMN isSynced INTEGER NOT NULL DEFAULT 0") } catch (e: Exception) { if (e.message?.contains("duplicate column name", true) != true) android.util.Log.w("AppDatabase", "Error adding activities col isSynced: ${e.message}") }
            try { database.execSQL("ALTER TABLE day_activities ADD COLUMN lastUpdated INTEGER NOT NULL DEFAULT 0") } catch (e: Exception) { if (e.message?.contains("duplicate column name", true) != true) android.util.Log.w("AppDatabase", "Error adding activities col lastUpdated: ${e.message}") }
            try { database.execSQL("ALTER TABLE day_activities ADD COLUMN isManualUnlock INTEGER NOT NULL DEFAULT 0") } catch (e: Exception) { if (e.message?.contains("duplicate column name", true) != true) android.util.Log.w("AppDatabase", "Error adding activities col isManualUnlock: ${e.message}") }
            try { database.execSQL("ALTER TABLE day_activities ADD COLUMN editedByAdmin INTEGER NOT NULL DEFAULT 0") } catch (e: Exception) { if (e.message?.contains("duplicate column name", true) != true) android.util.Log.w("AppDatabase", "Error adding activities col editedByAdmin: ${e.message}") }
        }

        private fun normalizeHouseTextColumns(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            val textCols = listOf("blockNumber", "blockSequence", "streetName", "number", "bairro", "agentName", "municipio", "categoria", "zona", "ciclo", "propertyType", "situation", "observation")
            textCols.forEach { col ->
                try { database.execSQL("UPDATE houses SET `$col` = UPPER(TRIM(COALESCE(`$col`, ''))) WHERE `$col` IS NOT NULL") } catch (e: Exception) { android.util.Log.w("AppDatabase", "Normalize $col failed: ${e.message}") }
            }
            try { database.execSQL("UPDATE houses SET `data` = REPLACE(COALESCE(`data`, ''), '/', '-')") } catch (e: Exception) { android.util.Log.w("AppDatabase", "Normalize data failed: ${e.message}") }
            try { database.execSQL("UPDATE houses SET agentUid = TRIM(COALESCE(agentUid, ''))") } catch (e: Exception) { android.util.Log.w("AppDatabase", "Normalize agentUid failed: ${e.message}") }
        }

        private fun normalizeDayActivityTextColumns(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            try { database.execSQL("UPDATE day_activities SET `date` = REPLACE(COALESCE(`date`, ''), '/', '-')") } catch (e: Exception) { android.util.Log.w("AppDatabase", "Normalize day_activities date failed: ${e.message}") }
            try { database.execSQL("UPDATE day_activities SET agentName = UPPER(TRIM(COALESCE(agentName, '')))") } catch (e: Exception) { android.util.Log.w("AppDatabase", "Normalize day_activities agentName failed: ${e.message}") }
        }

        private fun deduplicateHouses(database: androidx.sqlite.db.SupportSQLiteDatabase, includeVisitSegmentInIndex: Boolean) {
            // Create temp table with deduped data (same GROUP BY logic as nuclear rebuild).
            database.execSQL("DROP TABLE IF EXISTS `houses_dedup`")
            database.execSQL("""
                CREATE TABLE `houses_dedup` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `blockNumber` TEXT NOT NULL,
                    `streetName` TEXT NOT NULL,
                    `number` TEXT NOT NULL,
                    `sequence` INTEGER NOT NULL DEFAULT 0,
                    `complement` INTEGER NOT NULL DEFAULT 0,
                    `propertyType` TEXT NOT NULL DEFAULT 'EMPTY',
                    `situation` TEXT NOT NULL DEFAULT 'EMPTY',
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
                    `editedByAdmin` INTEGER NOT NULL DEFAULT 0,
                    `latitude` REAL,
                    `longitude` REAL,
                    `focusCaptureTime` INTEGER,
                    `lastUpdated` INTEGER NOT NULL DEFAULT 0
                )
            """)
            val visitSegmentSelect = if (includeVisitSegmentInIndex) "COALESCE(CAST(visitSegment AS INTEGER), 0)" else "MAX(COALESCE(CAST(visitSegment AS INTEGER), 0))"
            val visitSegmentGroupBy = if (includeVisitSegmentInIndex) ", COALESCE(CAST(visitSegment AS INTEGER), 0)" else ""
            database.execSQL("""
                INSERT INTO houses_dedup (
                    id, blockNumber, streetName, number, sequence, complement, propertyType, situation,
                    municipio, bairro, categoria, zona, tipo, `data`, ciclo, atividade, agentName,
                    a1, a2, b, c, d1, d2, e, eliminados, larvicida, comFoco, localidadeConcluida,
                    blockSequence, quarteiraoConcluido, listOrder, visitSegment, agentUid,
                    observation, createdAt, isSynced, editedByAdmin, latitude, longitude, focusCaptureTime, lastUpdated
                )
                SELECT
                    MIN(houses.id),
                    UPPER(TRIM(COALESCE(CAST(blockNumber AS TEXT), ''))),
                    UPPER(TRIM(COALESCE(CAST(streetName AS TEXT), ''))),
                    UPPER(TRIM(COALESCE(CAST(number AS TEXT), ''))),
                    COALESCE(CAST(sequence AS INTEGER), 0),
                    COALESCE(CAST(complement AS INTEGER), 0),
                    MAX(COALESCE(CAST(propertyType AS TEXT), 'EMPTY')),
                    MAX(COALESCE(CAST(situation AS TEXT), 'EMPTY')),
                    MAX(COALESCE(CAST(municipio AS TEXT), 'Bom Jardim')),
                    UPPER(TRIM(COALESCE(CAST(bairro AS TEXT), ''))),
                    MAX(COALESCE(CAST(categoria AS TEXT), 'BRR')),
                    MAX(COALESCE(CAST(zona AS TEXT), 'URB')),
                    MAX(COALESCE(CAST(tipo AS INTEGER), 2)),
                    REPLACE(COALESCE(CAST(`data` AS TEXT), ''), '/', '-'),
                    MAX(COALESCE(CAST(ciclo AS TEXT), '1º')),
                    MAX(COALESCE(CAST(atividade AS INTEGER), 4)),
                    UPPER(TRIM(COALESCE(CAST(agentName AS TEXT), ''))),
                    MAX(COALESCE(CAST(a1 AS INTEGER), 0)),
                    MAX(COALESCE(CAST(a2 AS INTEGER), 0)),
                    MAX(COALESCE(CAST(b AS INTEGER), 0)),
                    MAX(COALESCE(CAST(c AS INTEGER), 0)),
                    MAX(COALESCE(CAST(d1 AS INTEGER), 0)),
                    MAX(COALESCE(CAST(d2 AS INTEGER), 0)),
                    MAX(COALESCE(CAST(e AS INTEGER), 0)),
                    MAX(COALESCE(CAST(eliminados AS INTEGER), 0)),
                    MAX(COALESCE(CAST(larvicida AS REAL), 0.0)),
                    MAX(COALESCE(CAST(comFoco AS INTEGER), 0)),
                    MAX(COALESCE(CAST(localidadeConcluida AS INTEGER), 0)),
                    UPPER(TRIM(COALESCE(CAST(blockSequence AS TEXT), ''))),
                    MAX(COALESCE(CAST(quarteiraoConcluido AS INTEGER), 0)),
                    MAX(COALESCE(CAST(listOrder AS INTEGER), 0)),
                    $visitSegmentSelect,
                    TRIM(COALESCE(CAST(agentUid AS TEXT), '')),
                    MAX(COALESCE(CAST(observation AS TEXT), '')),
                    MAX(COALESCE(CAST(createdAt AS INTEGER), 0)),
                    MAX(COALESCE(CAST(isSynced AS INTEGER), 0)),
                    MAX(COALESCE(CAST(editedByAdmin AS INTEGER), 0)),
                    MAX(latitude),
                    MAX(longitude),
                    MAX(focusCaptureTime),
                    MAX(COALESCE(CAST(lastUpdated AS INTEGER), 0))
                FROM houses
                GROUP BY
                    TRIM(COALESCE(CAST(agentUid AS TEXT), '')),
                    UPPER(TRIM(COALESCE(CAST(agentName AS TEXT), ''))),
                    REPLACE(COALESCE(CAST(`data` AS TEXT), ''), '/', '-'),
                    UPPER(TRIM(COALESCE(CAST(blockNumber AS TEXT), ''))),
                    UPPER(TRIM(COALESCE(CAST(blockSequence AS TEXT), ''))),
                    UPPER(TRIM(COALESCE(CAST(streetName AS TEXT), ''))),
                    UPPER(TRIM(COALESCE(CAST(number AS TEXT), ''))),
                    COALESCE(CAST(sequence AS INTEGER), 0),
                    COALESCE(CAST(complement AS INTEGER), 0),
                    UPPER(TRIM(COALESCE(CAST(bairro AS TEXT), '')))
                    $visitSegmentGroupBy
            """)
            // Swap: delete rows in houses that have matching keys in houses_dedup, then insert deduped rows.
            // Use a DELETE with EXISTS on the composite key.
            database.execSQL("""
                DELETE FROM houses WHERE EXISTS (
                    SELECT 1 FROM houses_dedup hd WHERE
                        TRIM(COALESCE(CAST(houses.agentUid AS TEXT), '')) = TRIM(COALESCE(CAST(hd.agentUid AS TEXT), '')) AND
                        UPPER(TRIM(COALESCE(CAST(houses.agentName AS TEXT), ''))) = UPPER(TRIM(COALESCE(CAST(hd.agentName AS TEXT), ''))) AND
                        REPLACE(COALESCE(CAST(houses.`data` AS TEXT), ''), '/', '-') = REPLACE(COALESCE(CAST(hd.`data` AS TEXT), ''), '/', '-') AND
                        UPPER(TRIM(COALESCE(CAST(houses.blockNumber AS TEXT), ''))) = UPPER(TRIM(COALESCE(CAST(hd.blockNumber AS TEXT), ''))) AND
                        UPPER(TRIM(COALESCE(CAST(houses.blockSequence AS TEXT), ''))) = UPPER(TRIM(COALESCE(CAST(hd.blockSequence AS TEXT), ''))) AND
                        UPPER(TRIM(COALESCE(CAST(houses.streetName AS TEXT), ''))) = UPPER(TRIM(COALESCE(CAST(hd.streetName AS TEXT), ''))) AND
                        UPPER(TRIM(COALESCE(CAST(houses.number AS TEXT), ''))) = UPPER(TRIM(COALESCE(CAST(hd.number AS TEXT), ''))) AND
                        COALESCE(CAST(houses.sequence AS INTEGER), 0) = COALESCE(CAST(hd.sequence AS INTEGER), 0) AND
                        COALESCE(CAST(houses.complement AS INTEGER), 0) = COALESCE(CAST(hd.complement AS INTEGER), 0) AND
                        UPPER(TRIM(COALESCE(CAST(houses.bairro AS TEXT), ''))) = UPPER(TRIM(COALESCE(CAST(hd.bairro AS TEXT), '')))
                        ${if (includeVisitSegmentInIndex) "AND COALESCE(CAST(houses.visitSegment AS INTEGER), 0) = COALESCE(CAST(hd.visitSegment AS INTEGER), 0)" else ""}
                )
            """)
            // Explicit column list to avoid column order mismatch
            database.execSQL("""
                INSERT INTO houses (
                    id, blockNumber, streetName, number, sequence, complement, propertyType, situation,
                    municipio, bairro, categoria, zona, tipo, `data`, ciclo, atividade, agentName,
                    a1, a2, b, c, d1, d2, e, eliminados, larvicida, comFoco, localidadeConcluida,
                    blockSequence, quarteiraoConcluido, listOrder, visitSegment, agentUid,
                    observation, createdAt, isSynced, editedByAdmin, latitude, longitude, focusCaptureTime, lastUpdated
                )
                SELECT
                    id, blockNumber, streetName, number, sequence, complement, propertyType, situation,
                    municipio, bairro, categoria, zona, tipo, `data`, ciclo, atividade, agentName,
                    a1, a2, b, c, d1, d2, e, eliminados, larvicida, comFoco, localidadeConcluida,
                    blockSequence, quarteiraoConcluido, listOrder, visitSegment, agentUid,
                    observation, createdAt, isSynced, editedByAdmin, latitude, longitude, focusCaptureTime, lastUpdated
                FROM houses_dedup
            """)
            database.execSQL("DROP TABLE houses_dedup")
        }

        private fun recreateHouseIndices(database: androidx.sqlite.db.SupportSQLiteDatabase, includeVisitSegmentInIndex: Boolean) {
            database.execSQL("DROP INDEX IF EXISTS `index_houses_agentUid_agentName_data_blockNumber_blockSequence_streetName_number_sequence_complement_bairro`")
            database.execSQL("DROP INDEX IF EXISTS `index_houses_agentUid_agentName_data_blockNumber_blockSequence_streetName_number_sequence_complement_bairro_visitSegment`")
            if (includeVisitSegmentInIndex) {
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_houses_agentUid_agentName_data_blockNumber_blockSequence_streetName_number_sequence_complement_bairro_visitSegment`
                    ON `houses` (`agentUid`, `agentName`, `data`, `blockNumber`, `blockSequence`, `streetName`, `number`, `sequence`, `complement`, `bairro`, `visitSegment`)
                """)
            } else {
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_houses_agentUid_agentName_data_blockNumber_blockSequence_streetName_number_sequence_complement_bairro`
                    ON `houses` (`agentUid`, `agentName`, `data`, `blockNumber`, `blockSequence`, `streetName`, `number`, `sequence`, `complement`, `bairro`)
                """)
            }
            database.execSQL("DROP INDEX IF EXISTS `index_houses_data_agentUid`")
            database.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_houses_data_agentUid`
                ON `houses` (`data`, `agentUid`)
            """)
        }

        private fun rebuildDayActivitiesIncremental(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("DROP TABLE IF EXISTS `day_activities_new`")
            database.execSQL("""
                CREATE TABLE `day_activities_new` (
                    `date` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `isClosed` INTEGER NOT NULL DEFAULT 0,
                    `isManualUnlock` INTEGER NOT NULL DEFAULT 0,
                    `agentName` TEXT NOT NULL,
                    `agentUid` TEXT NOT NULL DEFAULT '',
                    `isSynced` INTEGER NOT NULL DEFAULT 0,
                    `editedByAdmin` INTEGER NOT NULL DEFAULT 0,
                    `lastUpdated` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`date`, `agentName`, `agentUid`)
                )
            """)
            database.execSQL("""
                INSERT OR IGNORE INTO day_activities_new (date, status, isClosed, agentName, agentUid, isSynced, lastUpdated, isManualUnlock, editedByAdmin)
                SELECT REPLACE(`date`, '/', '-'), status, isClosed, UPPER(TRIM(agentName)), agentUid, isSynced, lastUpdated, COALESCE(isManualUnlock, 0), COALESCE(editedByAdmin, 0) FROM day_activities
            """)
            database.execSQL("DELETE FROM day_activities")
            database.execSQL("""
                INSERT INTO day_activities (date, status, isClosed, isManualUnlock, agentName, agentUid, isSynced, editedByAdmin, lastUpdated)
                SELECT date, status, isClosed, isManualUnlock, agentName, agentUid, isSynced, editedByAdmin, lastUpdated FROM day_activities_new
            """)
            database.execSQL("DROP TABLE day_activities_new")
        }

        private fun rebuildCustomStreetsIncremental(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("DROP TABLE IF EXISTS `custom_streets_new`")
            database.execSQL("""
                CREATE TABLE `custom_streets_new` (
                    `name` TEXT NOT NULL,
                    `bairro` TEXT NOT NULL,
                    PRIMARY KEY(`name`)
                )
            """)
            database.execSQL("INSERT OR IGNORE INTO custom_streets_new (name, bairro) SELECT UPPER(TRIM(name)), UPPER(TRIM(bairro)) FROM custom_streets")
            database.execSQL("DELETE FROM custom_streets")
            database.execSQL("INSERT INTO custom_streets (name, bairro) SELECT name, bairro FROM custom_streets_new")
            database.execSQL("DROP TABLE custom_streets_new")
        }

        private fun rebuildTombstonesIncremental(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            try { database.execSQL("ALTER TABLE tombstones ADD COLUMN agentName TEXT NOT NULL DEFAULT ''") } catch (e: Exception) {}
            try { database.execSQL("ALTER TABLE tombstones ADD COLUMN agentUid TEXT NOT NULL DEFAULT ''") } catch (e: Exception) {}
            try { database.execSQL("ALTER TABLE tombstones ADD COLUMN dataDate TEXT NOT NULL DEFAULT ''") } catch (e: Exception) {}

            database.execSQL("DROP TABLE IF EXISTS `tombstones_new`")
            database.execSQL("""
                CREATE TABLE `tombstones_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `type` TEXT NOT NULL,
                    `naturalKey` TEXT NOT NULL,
                    `agentName` TEXT NOT NULL DEFAULT '',
                    `agentUid` TEXT NOT NULL DEFAULT '',
                    `dataDate` TEXT NOT NULL DEFAULT '',
                    `deletedAt` INTEGER NOT NULL
                )
            """)
            database.execSQL("""
                INSERT INTO tombstones_new (id, type, naturalKey, agentName, agentUid, dataDate, deletedAt)
                SELECT id, type, REPLACE(naturalKey, '/', '-'), UPPER(TRIM(COALESCE(agentName, ''))), agentUid, '', deletedAt FROM tombstones
            """)
            database.execSQL("DELETE FROM tombstones")
            database.execSQL("INSERT INTO tombstones SELECT * FROM tombstones_new")
            database.execSQL("DROP TABLE tombstones_new")
        }

        val MIGRATION_34_35 = object : androidx.room.migration.Migration(34, 35) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Incremental migration replacing nuclear rebuild.
                addMissingHouseColumns(database)
                addMissingDayActivityColumns(database)
                normalizeHouseTextColumns(database)
                normalizeDayActivityTextColumns(database)
                deduplicateHouses(database, includeVisitSegmentInIndex = true)
                recreateHouseIndices(database, includeVisitSegmentInIndex = true)
                rebuildDayActivitiesIncremental(database)
                rebuildCustomStreetsIncremental(database)
                rebuildTombstonesIncremental(database)
            }
        }

        val MIGRATION_32_33 = object : androidx.room.migration.Migration(32, 33) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Remove visitSegment from the unique index.
                recreateHouseIndices(database, includeVisitSegmentInIndex = false)
            }
        }

        val MIGRATION_33_34 = object : androidx.room.migration.Migration(33, 34) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Restore visitSegment to the unique index.
                recreateHouseIndices(database, includeVisitSegmentInIndex = true)
            }
        }

        val MIGRATION_31_32 = object : androidx.room.migration.Migration(31, 32) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    database.execSQL("ALTER TABLE tombstones ADD COLUMN `dataDate` TEXT NOT NULL DEFAULT ''")
                } catch (e: Exception) {
                    android.util.Log.e("AppDatabase", "Error adding dataDate to tombstones: ${e.message}")
                }
            }
        }

        private fun ensureModernTablesExist(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS `houses` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `propertyType` TEXT NOT NULL DEFAULT 'EMPTY',
                    `situation` TEXT NOT NULL DEFAULT 'EMPTY',
                    `data` TEXT NOT NULL,
                    `agentName` TEXT NOT NULL,
                    `localidadeConcluida` INTEGER NOT NULL DEFAULT 0,
                    `quarteiraoConcluido` INTEGER NOT NULL DEFAULT 0,
                    `listOrder` INTEGER NOT NULL DEFAULT 0,
                    `visitSegment` INTEGER NOT NULL DEFAULT 0,
                    `agentUid` TEXT NOT NULL DEFAULT '',
                    `observation` TEXT NOT NULL DEFAULT '',
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `isSynced` INTEGER NOT NULL DEFAULT 0,
                    `editedByAdmin` INTEGER NOT NULL DEFAULT 0,
                    `lastUpdated` INTEGER NOT NULL DEFAULT 0,
                    `blockNumber` TEXT NOT NULL,
                    `blockSequence` TEXT NOT NULL,
                    `streetName` TEXT NOT NULL,
                    `number` TEXT NOT NULL,
                    `sequence` INTEGER NOT NULL,
                    `complement` INTEGER NOT NULL,
                    `bairro` TEXT NOT NULL,
                    `a1` INTEGER NOT NULL,
                    `a2` INTEGER NOT NULL,
                    `b` INTEGER NOT NULL,
                    `c` INTEGER NOT NULL,
                    `d1` INTEGER NOT NULL,
                    `d2` INTEGER NOT NULL,
                    `e` INTEGER NOT NULL,
                    `eliminados` INTEGER NOT NULL,
                    `larvicida` REAL NOT NULL,
                    `comFoco` INTEGER NOT NULL,
                    `municipio` TEXT NOT NULL,
                    `categoria` TEXT NOT NULL,
                    `zona` TEXT NOT NULL,
                    `tipo` INTEGER NOT NULL,
                    `ciclo` TEXT NOT NULL,
                    `atividade` INTEGER NOT NULL,
                    `latitude` REAL,
                    `longitude` REAL,
                    `focusCaptureTime` INTEGER
                )
            """)
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS `day_activities` (
                    `date` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `isClosed` INTEGER NOT NULL DEFAULT 0,
                    `agentName` TEXT NOT NULL,
                    `agentUid` TEXT NOT NULL DEFAULT '',
                    `isSynced` INTEGER NOT NULL DEFAULT 0,
                    `lastUpdated` INTEGER NOT NULL DEFAULT 0,
                    `editedByAdmin` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`date`, `agentName`, `agentUid`)
                )
            """)
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS `custom_streets` (
                    `name` TEXT NOT NULL,
                    `bairro` TEXT NOT NULL,
                    PRIMARY KEY(`name`)
                )
            """)
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS `tombstones` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `type` TEXT NOT NULL,
                    `naturalKey` TEXT NOT NULL,
                    `deletedAt` INTEGER NOT NULL
                )
            """)
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS `cached_agents` (
                    `uid` TEXT NOT NULL,
                    `email` TEXT NOT NULL,
                    `agentName` TEXT,
                    `lastSyncTime` INTEGER NOT NULL,
                    `lastSyncError` TEXT,
                    `photoUrl` TEXT,
                    `cachedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`uid`)
                )
            """)
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS `cached_agent_summaries` (
                    `agentUid` TEXT NOT NULL,
                    `monthYear` TEXT NOT NULL,
                    `treatedCount` INTEGER NOT NULL,
                    `focusCount` INTEGER NOT NULL,
                    `totalHouses` INTEGER NOT NULL,
                    `daysWorked` INTEGER NOT NULL,
                    `lastUpdated` INTEGER NOT NULL,
                    `situationCounts` TEXT NOT NULL,
                    `propertyTypeCounts` TEXT NOT NULL,
                    PRIMARY KEY(`agentUid`, `monthYear`)
                )
            """)
        }

        private fun performIncrementalRebuild(database: androidx.sqlite.db.SupportSQLiteDatabase, includeVisitSegmentInIndex: Boolean = true) {
            ensureModernTablesExist(database)
            addMissingHouseColumns(database)
            addMissingDayActivityColumns(database)
            normalizeHouseTextColumns(database)
            normalizeDayActivityTextColumns(database)
            deduplicateHouses(database, includeVisitSegmentInIndex = includeVisitSegmentInIndex)
            recreateHouseIndices(database, includeVisitSegmentInIndex = includeVisitSegmentInIndex)
            rebuildDayActivitiesIncremental(database)
            rebuildCustomStreetsIncremental(database)
            rebuildTombstonesIncremental(database)
        }

        val MIGRATION_30_31 = object : androidx.room.migration.Migration(30, 31) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        val MIGRATION_29_30 = object : androidx.room.migration.Migration(29, 30) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        val MIGRATION_28_29 = object : androidx.room.migration.Migration(28, 29) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    database.execSQL("ALTER TABLE houses ADD COLUMN `editedByAdmin` INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                    if (e.message?.contains("duplicate column name", ignoreCase = true) != true) {
                        android.util.Log.e("AppDatabase", "Error adding editedByAdmin to houses in MIGRATION_28_29: ${e.message}")
                    }
                }
                try {
                    database.execSQL("ALTER TABLE day_activities ADD COLUMN `editedByAdmin` INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                    if (e.message?.contains("duplicate column name", ignoreCase = true) != true) {
                        android.util.Log.e("AppDatabase", "Error adding editedByAdmin to day_activities in MIGRATION_28_29: ${e.message}")
                    }
                }
            }
        }

        val MIGRATION_27_28 = object : androidx.room.migration.Migration(27, 28) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `cached_agents` (
                        `uid` TEXT NOT NULL, 
                        `email` TEXT NOT NULL, 
                        `agentName` TEXT, 
                        `lastSyncTime` INTEGER NOT NULL, 
                        `lastSyncError` TEXT, 
                        `photoUrl` TEXT, 
                        `cachedAt` INTEGER NOT NULL, 
                        PRIMARY KEY(`uid`)
                    )
                """)
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `cached_agent_summaries` (
                        `agentUid` TEXT NOT NULL, 
                        `monthYear` TEXT NOT NULL, 
                        `treatedCount` INTEGER NOT NULL, 
                        `focusCount` INTEGER NOT NULL, 
                        `totalHouses` INTEGER NOT NULL, 
                        `daysWorked` INTEGER NOT NULL, 
                        `lastUpdated` INTEGER NOT NULL, 
                        `situationCounts` TEXT NOT NULL, 
                        `propertyTypeCounts` TEXT NOT NULL, 
                        PRIMARY KEY(`agentUid`, `monthYear`)
                    )
                """)
            }
        }

        val MIGRATION_26_27 = object : androidx.room.migration.Migration(26, 27) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        val MIGRATION_25_26 = object : androidx.room.migration.Migration(25, 26) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        val MIGRATION_24_25 = object : androidx.room.migration.Migration(24, 25) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        val MIGRATION_23_25 = object : androidx.room.migration.Migration(23, 25) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        val MIGRATION_23_24 = object : androidx.room.migration.Migration(23, 24) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        val MIGRATION_22_24 = object : androidx.room.migration.Migration(22, 24) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        val MIGRATION_21_23 = object : androidx.room.migration.Migration(21, 23) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        val MIGRATION_20_23 = object : androidx.room.migration.Migration(20, 23) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        val MIGRATION_20_22 = object : androidx.room.migration.Migration(20, 22) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                performIncrementalRebuild(database)
            }
        }

        private fun performFullNuclearRebuild(database: androidx.sqlite.db.SupportSQLiteDatabase, includeVisitSegmentInIndex: Boolean = true) {
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
                    "editedByAdmin" to "INTEGER NOT NULL DEFAULT 0",
                    "latitude" to "REAL",
                    "longitude" to "REAL",
                    "focusCaptureTime" to "INTEGER",
                    "lastUpdated" to "INTEGER NOT NULL DEFAULT 0"
                )
                
                houseColumnsToGuard.forEach { (col, type) ->
                    try { 
                        database.execSQL("ALTER TABLE houses ADD COLUMN `$col` $type") 
                    } catch(e: Exception) {
                        if (e.message?.contains("duplicate column name", ignoreCase = true) != true) {
                            android.util.Log.w("AppDatabase", "Error adding house col $col: ${e.message}")
                        }
                    }
                }

                try { 
                    database.execSQL("ALTER TABLE day_activities ADD COLUMN agentUid TEXT NOT NULL DEFAULT ''") 
                } catch(e: Exception) {
                    if (e.message?.contains("duplicate column name", ignoreCase = true) != true) {
                        android.util.Log.w("AppDatabase", "Error adding activities col agentUid: ${e.message}")
                    }
                }
                try { 
                    database.execSQL("ALTER TABLE day_activities ADD COLUMN isSynced INTEGER NOT NULL DEFAULT 0") 
                } catch(e: Exception) {
                    if (e.message?.contains("duplicate column name", ignoreCase = true) != true) {
                        android.util.Log.w("AppDatabase", "Error adding activities col isSynced: ${e.message}")
                    }
                }
                try { 
                    database.execSQL("ALTER TABLE day_activities ADD COLUMN lastUpdated INTEGER NOT NULL DEFAULT 0") 
                } catch(e: Exception) {
                    if (e.message?.contains("duplicate column name", ignoreCase = true) != true) {
                        android.util.Log.w("AppDatabase", "Error adding activities col lastUpdated: ${e.message}")
                    }
                }
                try { 
                    database.execSQL("ALTER TABLE day_activities ADD COLUMN isManualUnlock INTEGER NOT NULL DEFAULT 0") 
                } catch(e: Exception) {
                    if (e.message?.contains("duplicate column name", ignoreCase = true) != true) {
                        android.util.Log.w("AppDatabase", "Error adding activities col isManualUnlock: ${e.message}")
                    }
                }

                // 2. Rebuild houses
                android.util.Log.d("AppDatabase", "Creating houses_new table...")
                database.execSQL("DROP TABLE IF EXISTS `houses_new`")
                database.execSQL("""
                    CREATE TABLE `houses_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `blockNumber` TEXT NOT NULL, 
                        `streetName` TEXT NOT NULL, 
                        `number` TEXT NOT NULL, 
                        `sequence` INTEGER NOT NULL DEFAULT 0, 
                        `complement` INTEGER NOT NULL DEFAULT 0, 
                        `propertyType` TEXT NOT NULL DEFAULT 'EMPTY', 
                        `situation` TEXT NOT NULL DEFAULT 'EMPTY', 
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
                        `editedByAdmin` INTEGER NOT NULL DEFAULT 0, 
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
                        observation, createdAt, isSynced, editedByAdmin, latitude, longitude, focusCaptureTime, lastUpdated
                    )
                    SELECT 
                        UPPER(TRIM(COALESCE(CAST(blockNumber AS TEXT), ''))), 
                        UPPER(TRIM(COALESCE(CAST(streetName AS TEXT), ''))), 
                        UPPER(TRIM(COALESCE(CAST(number AS TEXT), ''))), 
                        COALESCE(CAST(sequence AS INTEGER), 0), 
                        COALESCE(CAST(complement AS INTEGER), 0), 
                        MAX(COALESCE(CAST(propertyType AS TEXT), 'EMPTY')), 
                        MAX(COALESCE(CAST(situation AS TEXT), 'EMPTY')),
                        MAX(COALESCE(CAST(municipio AS TEXT), 'Bom Jardim')), 
                        UPPER(TRIM(COALESCE(CAST(bairro AS TEXT), ''))), 
                        MAX(COALESCE(CAST(categoria AS TEXT), 'BRR')), 
                        MAX(COALESCE(CAST(zona AS TEXT), 'URB')), 
                        MAX(COALESCE(CAST(tipo AS INTEGER), 2)), 
                        REPLACE(COALESCE(CAST(`data` AS TEXT), ''), '/', '-'), 
                        MAX(COALESCE(CAST(ciclo AS TEXT), '1º')), 
                        MAX(COALESCE(CAST(atividade AS INTEGER), 4)), 
                        UPPER(TRIM(COALESCE(CAST(agentName AS TEXT), ''))),
                        MAX(COALESCE(CAST(a1 AS INTEGER), 0)), 
                        MAX(COALESCE(CAST(a2 AS INTEGER), 0)), 
                        MAX(COALESCE(CAST(b AS INTEGER), 0)), 
                        MAX(COALESCE(CAST(c AS INTEGER), 0)), 
                        MAX(COALESCE(CAST(d1 AS INTEGER), 0)), 
                        MAX(COALESCE(CAST(d2 AS INTEGER), 0)), 
                        MAX(COALESCE(CAST(e AS INTEGER), 0)), 
                        MAX(COALESCE(CAST(eliminados AS INTEGER), 0)), 
                        MAX(COALESCE(CAST(larvicida AS REAL), 0.0)), 
                        MAX(COALESCE(CAST(comFoco AS INTEGER), 0)), 
                        MAX(COALESCE(CAST(localidadeConcluida AS INTEGER), 0)), 
                        UPPER(TRIM(COALESCE(CAST(blockSequence AS TEXT), ''))), 
                        MAX(COALESCE(CAST(quarteiraoConcluido AS INTEGER), 0)), 
                        MAX(COALESCE(CAST(listOrder AS INTEGER), 0)), 
                        ${if (includeVisitSegmentInIndex) "COALESCE(CAST(visitSegment AS INTEGER), 0)" else "MAX(COALESCE(CAST(visitSegment AS INTEGER), 0))"}, 
                        TRIM(COALESCE(CAST(agentUid AS TEXT), '')),
                        MAX(COALESCE(CAST(observation AS TEXT), '')), 
                        MAX(COALESCE(CAST(createdAt AS INTEGER), 0)), 
                        MAX(COALESCE(CAST(isSynced AS INTEGER), 0)), 
                        MAX(COALESCE(CAST(editedByAdmin AS INTEGER), 0)), 
                        MAX(latitude), 
                        MAX(longitude), 
                        MAX(focusCaptureTime), 
                        MAX(COALESCE(CAST(lastUpdated AS INTEGER), 0))
                    FROM houses
                    GROUP BY 
                        TRIM(COALESCE(CAST(agentUid AS TEXT), '')),
                        UPPER(TRIM(COALESCE(CAST(agentName AS TEXT), ''))), 
                        REPLACE(COALESCE(CAST(`data` AS TEXT), ''), '/', '-'), 
                        UPPER(TRIM(COALESCE(CAST(blockNumber AS TEXT), ''))), 
                        UPPER(TRIM(COALESCE(CAST(blockSequence AS TEXT), ''))), 
                        UPPER(TRIM(COALESCE(CAST(streetName AS TEXT), ''))), 
                        UPPER(TRIM(COALESCE(CAST(number AS TEXT), ''))), 
                        COALESCE(CAST(sequence AS INTEGER), 0), 
                        COALESCE(CAST(complement AS INTEGER), 0), 
                        UPPER(TRIM(COALESCE(CAST(bairro AS TEXT), '')))
                        ${if (includeVisitSegmentInIndex) ", COALESCE(CAST(visitSegment AS INTEGER), 0)" else ""}
                """)

                android.util.Log.d("AppDatabase", "Finalizing swap...")
                database.execSQL("DROP TABLE houses")
                database.execSQL("ALTER TABLE houses_new RENAME TO houses")
                
                database.execSQL("DROP INDEX IF EXISTS `index_houses_agentUid_agentName_data_blockNumber_blockSequence_streetName_number_sequence_complement_bairro` ")
                database.execSQL("DROP INDEX IF EXISTS `index_houses_agentUid_agentName_data_blockNumber_blockSequence_streetName_number_sequence_complement_bairro_visitSegment` ")
                
                if (includeVisitSegmentInIndex) {
                    database.execSQL("""
                        CREATE UNIQUE INDEX IF NOT EXISTS `index_houses_agentUid_agentName_data_blockNumber_blockSequence_streetName_number_sequence_complement_bairro_visitSegment` 
                        ON `houses` (`agentUid`, `agentName`, `data`, `blockNumber`, `blockSequence`, `streetName`, `number`, `sequence`, `complement`, `bairro`, `visitSegment`)
                    """)
                } else {
                    database.execSQL("""
                        CREATE UNIQUE INDEX IF NOT EXISTS `index_houses_agentUid_agentName_data_blockNumber_blockSequence_streetName_number_sequence_complement_bairro` 
                        ON `houses` (`agentUid`, `agentName`, `data`, `blockNumber`, `blockSequence`, `streetName`, `number`, `sequence`, `complement`, `bairro`)
                    """)
                }
                
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
                        `isClosed` INTEGER NOT NULL DEFAULT 0, 
                        `agentName` TEXT NOT NULL, 
                        `agentUid` TEXT NOT NULL DEFAULT '', 
                        `isSynced` INTEGER NOT NULL DEFAULT 0, 
                        `lastUpdated` INTEGER NOT NULL DEFAULT 0, 
                        `isManualUnlock` INTEGER NOT NULL DEFAULT 0, 
                        `editedByAdmin` INTEGER NOT NULL DEFAULT 0, 
                        PRIMARY KEY(`date`, `agentName`, `agentUid`)
                    )
                """)

                database.execSQL("""
                    INSERT OR IGNORE INTO day_activities_new (date, status, isClosed, agentName, agentUid, isSynced, lastUpdated, isManualUnlock, editedByAdmin)
                    SELECT REPLACE(`date`, '/', '-'), status, isClosed, UPPER(TRIM(agentName)), agentUid, isSynced, lastUpdated, COALESCE(isManualUnlock, 0), COALESCE(editedByAdmin, 0) FROM day_activities
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
                
                // Ensure columns exist before select
                try { database.execSQL("ALTER TABLE tombstones ADD COLUMN agentName TEXT NOT NULL DEFAULT ''") } catch(e: Exception) {}
                try { database.execSQL("ALTER TABLE tombstones ADD COLUMN agentUid TEXT NOT NULL DEFAULT ''") } catch(e: Exception) {}

                database.execSQL("DROP TABLE IF EXISTS `tombstones_new`")
                database.execSQL("""
                    CREATE TABLE `tombstones_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `type` TEXT NOT NULL, 
                        `naturalKey` TEXT NOT NULL, 
                        `agentName` TEXT NOT NULL DEFAULT '', 
                        `agentUid` TEXT NOT NULL DEFAULT '', 
                        `dataDate` TEXT NOT NULL DEFAULT '', 
                        `deletedAt` INTEGER NOT NULL
                    )
                """)
                database.execSQL("""
                    INSERT INTO tombstones_new (id, type, naturalKey, agentName, agentUid, dataDate, deletedAt) 
                    SELECT id, type, REPLACE(naturalKey, '/', '-'), UPPER(TRIM(agentName)), agentUid, '', deletedAt FROM tombstones
                """)
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
