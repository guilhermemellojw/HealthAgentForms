package com.antigravity.healthagent.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class Migration36To37Test {

    private val TEST_DB = "migration-36-37-test.db"
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migration36To37_deletesUppercasedUidHouses() {
        createV36WithData {
            // agentUid is all uppercase → should be deleted
            execSQL("""
                INSERT INTO houses (
                    propertyType, situation, data, agentName, agentUid, observation,
                    listOrder, visitSegment, blockNumber, blockSequence, streetName,
                    number, sequence, complement, bairro, a1, a2, b, c, d1, d2, e,
                    eliminados, larvicida, comFoco, municipio, categoria, zona, tipo,
                    ciclo, atividade
                ) VALUES (
                    'R', 'NONE', '01-01-2026', 'Agent', 'ABC123', '',
                    0, 1, '001', 'A', 'RUA A',
                    '10', 1, 0, 'CENTRO', 0, 0, 0, 0, 0, 0, 0,
                    0, 0.0, 0, 'MUNICIPIO', 'CATEGORIA', 'ZONA', 1,
                    'CICLO', 0
                )
            """)
            // agentUid is all uppercase → should be deleted
            execSQL("""
                INSERT INTO houses (
                    propertyType, situation, data, agentName, agentUid, observation,
                    listOrder, visitSegment, blockNumber, blockSequence, streetName,
                    number, sequence, complement, bairro, a1, a2, b, c, d1, d2, e,
                    eliminados, larvicida, comFoco, municipio, categoria, zona, tipo,
                    ciclo, atividade
                ) VALUES (
                    'R', 'NONE', '01-01-2026', 'Agent', 'UID_UPPER', '',
                    0, 1, '001', 'A', 'RUA A',
                    '20', 1, 0, 'CENTRO', 0, 0, 0, 0, 0, 0, 0,
                    0, 0.0, 0, 'MUNICIPIO', 'CATEGORIA', 'ZONA', 1,
                    'CICLO', 0
                )
            """)
            // agentUid is lowercase → preserved
            execSQL("""
                INSERT INTO houses (
                    propertyType, situation, data, agentName, agentUid, observation,
                    listOrder, visitSegment, blockNumber, blockSequence, streetName,
                    number, sequence, complement, bairro, a1, a2, b, c, d1, d2, e,
                    eliminados, larvicida, comFoco, municipio, categoria, zona, tipo,
                    ciclo, atividade
                ) VALUES (
                    'R', 'NONE', '01-01-2026', 'Agent', 'abc123', '',
                    0, 1, '001', 'A', 'RUA A',
                    '30', 1, 0, 'CENTRO', 0, 0, 0, 0, 0, 0, 0,
                    0, 0.0, 0, 'MUNICIPIO', 'CATEGORIA', 'ZONA', 1,
                    'CICLO', 0
                )
            """)
            // agentUid is mixed case → preserved (UPPER(agentUid) != agentUid)
            execSQL("""
                INSERT INTO houses (
                    propertyType, situation, data, agentName, agentUid, observation,
                    listOrder, visitSegment, blockNumber, blockSequence, streetName,
                    number, sequence, complement, bairro, a1, a2, b, c, d1, d2, e,
                    eliminados, larvicida, comFoco, municipio, categoria, zona, tipo,
                    ciclo, atividade
                ) VALUES (
                    'R', 'NONE', '01-01-2026', 'Agent', 'Abc123', '',
                    0, 1, '001', 'A', 'RUA A',
                    '40', 1, 0, 'CENTRO', 0, 0, 0, 0, 0, 0, 0,
                    0, 0.0, 0, 'MUNICIPIO', 'CATEGORIA', 'ZONA', 1,
                    'CICLO', 0
                )
            """)
            // agentUid is empty → preserved
            execSQL("""
                INSERT INTO houses (
                    propertyType, situation, data, agentName, agentUid, observation,
                    listOrder, visitSegment, blockNumber, blockSequence, streetName,
                    number, sequence, complement, bairro, a1, a2, b, c, d1, d2, e,
                    eliminados, larvicida, comFoco, municipio, categoria, zona, tipo,
                    ciclo, atividade
                ) VALUES (
                    'R', 'NONE', '01-01-2026', 'Agent', '', '',
                    0, 1, '001', 'A', 'RUA A',
                    '50', 1, 0, 'CENTRO', 0, 0, 0, 0, 0, 0, 0,
                    0, 0.0, 0, 'MUNICIPIO', 'CATEGORIA', 'ZONA', 1,
                    'CICLO', 0
                )
            """)
            // agentUid is numeric (no alphabetic chars) → UPPER == agentUid == LOWER → preserved
            execSQL("""
                INSERT INTO houses (
                    propertyType, situation, data, agentName, agentUid, observation,
                    listOrder, visitSegment, blockNumber, blockSequence, streetName,
                    number, sequence, complement, bairro, a1, a2, b, c, d1, d2, e,
                    eliminados, larvicida, comFoco, municipio, categoria, zona, tipo,
                    ciclo, atividade
                ) VALUES (
                    'R', 'NONE', '01-01-2026', 'Agent', '123456', '',
                    0, 1, '001', 'A', 'RUA A',
                    '60', 1, 0, 'CENTRO', 0, 0, 0, 0, 0, 0, 0,
                    0, 0.0, 0, 'MUNICIPIO', 'CATEGORIA', 'ZONA', 1,
                    'CICLO', 0
                )
            """)
        }

        migrateAndVerify { db ->
            // Verify total count: 6 inserted - 2 deleted = 4 remaining
            val countCursor = db.rawQuery("SELECT COUNT(*) FROM houses", null)
            countCursor.moveToFirst()
            assertEquals("Expected 4 houses (2 uppercased UIDs deleted)", 4, countCursor.getInt(0))
            countCursor.close()

            // Verify the preserved houses have correct UIDs
            val cursor = db.rawQuery("SELECT agentUid, number FROM houses ORDER BY number ASC", null)
            cursor.moveToFirst()

            assertEquals("abc123", cursor.getString(cursor.getColumnIndexOrThrow("agentUid")))
            cursor.moveToNext()
            assertEquals("Abc123", cursor.getString(cursor.getColumnIndexOrThrow("agentUid")))
            cursor.moveToNext()
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("agentUid")))
            cursor.moveToNext()
            assertEquals("123456", cursor.getString(cursor.getColumnIndexOrThrow("agentUid")))
            cursor.close()
        }
    }

    @Test
    fun migration36To37_preservesDayActivities() {
        createV36WithData {
            execSQL("""
                INSERT INTO day_activities (date, status, isClosed, agentName, agentUid)
                VALUES ('01-01-2026', 'OPEN', 0, 'Agent A', 'abc123')
            """)
        }

        migrateAndVerify { db ->
            val cursor = db.rawQuery("SELECT * FROM day_activities", null)
            cursor.moveToFirst()
            assertEquals("Agent A", cursor.getString(cursor.getColumnIndexOrThrow("agentName")))
            assertEquals("abc123", cursor.getString(cursor.getColumnIndexOrThrow("agentUid")))
            cursor.close()
        }
    }

    @Test
    fun migration36To37_preservesTombstones() {
        createV36WithData {
            execSQL("""
                INSERT INTO tombstones (type, naturalKey, agentName, agentUid, dataDate, deletedAt)
                VALUES ('HOUSE', 'key_1', 'Agent A', 'abc123', '01-01-2026', 1234567890)
            """)
        }

        migrateAndVerify { db ->
            val cursor = db.rawQuery("SELECT * FROM tombstones", null)
            cursor.moveToFirst()
            assertEquals("HOUSE", cursor.getString(cursor.getColumnIndexOrThrow("type")))
            assertEquals("key_1", cursor.getString(cursor.getColumnIndexOrThrow("naturalKey")))
            cursor.close()
        }
    }

    @Test
    fun migration36To37_preservesCustomStreets() {
        createV36WithData {
            execSQL("INSERT INTO custom_streets (name, bairro) VALUES ('RUA TESTE', 'CENTRO')")
        }

        migrateAndVerify { db ->
            val cursor = db.rawQuery("SELECT * FROM custom_streets", null)
            cursor.moveToFirst()
            assertEquals("RUA TESTE", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            cursor.close()
        }
    }

    @Test
    fun migration36To37_opensSuccessfullyWithRoomDao() = runBlocking {
        createV36WithData {
            execSQL("""
                INSERT INTO houses (
                    propertyType, situation, data, agentName, agentUid, observation,
                    listOrder, visitSegment, blockNumber, blockSequence, streetName,
                    number, sequence, complement, bairro, a1, a2, b, c, d1, d2, e,
                    eliminados, larvicida, comFoco, municipio, categoria, zona, tipo,
                    ciclo, atividade
                ) VALUES (
                    'R', 'NONE', '01-01-2026', 'Agent', 'abc123', '',
                    0, 1, '001', 'A', 'RUA TESTE',
                    '10', 1, 0, 'CENTRO', 0, 0, 0, 0, 0, 0, 0,
                    0, 0.0, 0, 'MUNICIPIO', 'CATEGORIA', 'ZONA', 1,
                    'CICLO', 0
                )
            """)
        }

        val database = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            TEST_DB
        ).addMigrations(AppDatabase.MIGRATION_36_37, AppDatabase.MIGRATION_37_38, AppDatabase.MIGRATION_38_39, AppDatabase.MIGRATION_39_40)
            .build()

        val house = database.houseDao().getHouseById(1)
        assertNotNull(house)
        assertEquals("abc123", house!!.agentUid)

        val houses = database.houseDao().getHousesByAgentSnapshot("abc123")
        assertEquals(1, houses.size)

        database.close()
    }

    private fun createV36WithData(block: SQLiteDatabase.() -> Unit) {
        context.deleteDatabase(TEST_DB)
        val db = context.openOrCreateDatabase(TEST_DB, Context.MODE_PRIVATE, null)
        db.version = 36
        db.execSQL("CREATE TABLE IF NOT EXISTS `houses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `propertyType` TEXT NOT NULL DEFAULT 'EMPTY', `situation` TEXT NOT NULL DEFAULT 'EMPTY', `data` TEXT NOT NULL, `agentName` TEXT NOT NULL, `localidadeConcluida` INTEGER NOT NULL DEFAULT 0, `quarteiraoConcluido` INTEGER NOT NULL DEFAULT 0, `listOrder` INTEGER NOT NULL DEFAULT 0, `visitSegment` INTEGER NOT NULL DEFAULT 0, `agentUid` TEXT NOT NULL DEFAULT '', `observation` TEXT NOT NULL DEFAULT '', `createdAt` INTEGER NOT NULL DEFAULT 0, `isSynced` INTEGER NOT NULL DEFAULT 0, `editedByAdmin` INTEGER NOT NULL DEFAULT 0, `lastUpdated` INTEGER NOT NULL DEFAULT 0, `blockNumber` TEXT NOT NULL, `blockSequence` TEXT NOT NULL, `streetName` TEXT NOT NULL, `number` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `complement` INTEGER NOT NULL, `bairro` TEXT NOT NULL, `a1` INTEGER NOT NULL, `a2` INTEGER NOT NULL, `b` INTEGER NOT NULL, `c` INTEGER NOT NULL, `d1` INTEGER NOT NULL, `d2` INTEGER NOT NULL, `e` INTEGER NOT NULL, `eliminados` INTEGER NOT NULL, `larvicida` REAL NOT NULL, `comFoco` INTEGER NOT NULL, `municipio` TEXT NOT NULL, `categoria` TEXT NOT NULL, `zona` TEXT NOT NULL, `tipo` INTEGER NOT NULL, `ciclo` TEXT NOT NULL, `atividade` INTEGER NOT NULL, `latitude` REAL, `longitude` REAL, `focusCaptureTime` INTEGER)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_houses_agentUid_agentName_data_blockNumber_blockSequence_streetName_number_sequence_complement_bairro_visitSegment` ON `houses` (`agentUid`, `agentName`, `data`, `blockNumber`, `blockSequence`, `streetName`, `number`, `sequence`, `complement`, `bairro`, `visitSegment`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_houses_data_agentUid` ON `houses` (`data`, `agentUid`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `day_activities` (`date` TEXT NOT NULL, `status` TEXT NOT NULL, `isClosed` INTEGER NOT NULL DEFAULT 0, `isManualUnlock` INTEGER NOT NULL DEFAULT 0, `agentName` TEXT NOT NULL, `agentUid` TEXT NOT NULL DEFAULT '', `isSynced` INTEGER NOT NULL DEFAULT 0, `editedByAdmin` INTEGER NOT NULL DEFAULT 0, `lastUpdated` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`date`, `agentName`, `agentUid`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `custom_streets` (`name` TEXT NOT NULL, `bairro` TEXT NOT NULL, PRIMARY KEY(`name`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `tombstones` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `naturalKey` TEXT NOT NULL, `agentName` TEXT NOT NULL DEFAULT '', `agentUid` TEXT NOT NULL DEFAULT '', `dataDate` TEXT NOT NULL DEFAULT '', `deletedAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `cached_agents` (`uid` TEXT NOT NULL, `email` TEXT NOT NULL, `agentName` TEXT, `lastSyncTime` INTEGER NOT NULL, `lastSyncError` TEXT, `photoUrl` TEXT, `cachedAt` INTEGER NOT NULL, PRIMARY KEY(`uid`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `cached_agent_summaries` (`agentUid` TEXT NOT NULL, `monthYear` TEXT NOT NULL, `treatedCount` INTEGER NOT NULL, `focusCount` INTEGER NOT NULL, `totalHouses` INTEGER NOT NULL, `daysWorked` INTEGER NOT NULL, `lastUpdated` INTEGER NOT NULL, `situationCounts` TEXT NOT NULL, `propertyTypeCounts` TEXT NOT NULL, PRIMARY KEY(`agentUid`, `monthYear`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '5d483e12e669701b1c612b95d2a76bbf')")
        block(db)
        db.close()
    }

    private fun migrateAndVerify(block: (SQLiteDatabase) -> Unit) {
        val roomDb = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            TEST_DB
        ).addMigrations(AppDatabase.MIGRATION_36_37, AppDatabase.MIGRATION_37_38, AppDatabase.MIGRATION_38_39, AppDatabase.MIGRATION_39_40)
            .build()
        roomDb.openHelper.writableDatabase
        roomDb.close()

        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DB).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        block(db)
        if (db.isOpen) db.close()
    }
}
