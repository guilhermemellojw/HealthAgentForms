package com.antigravity.healthagent.data.local.dao

import androidx.room.*
import com.antigravity.healthagent.data.local.model.House
import kotlinx.coroutines.flow.Flow

@Dao
interface HouseDao {
    @Query("SELECT * FROM houses ORDER BY listOrder ASC, id ASC")
    suspend fun getAllHousesSnapshot(): List<House>

    @Query("SELECT * FROM houses ORDER BY listOrder ASC, id ASC")
    fun getAllHousesSnapshotFlow(): Flow<List<House>>

    @Query("SELECT COUNT(*) FROM houses")
    suspend fun count(): Int

    @Query("SELECT * FROM houses WHERE (agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName)) ORDER BY listOrder ASC, id ASC")
    fun getAllHouses(agentName: String, agentUid: String): Flow<List<House>>

    @Query("SELECT * FROM houses WHERE ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName))) ORDER BY bairro ASC, blockNumber ASC, listOrder ASC, id ASC")
    fun getAllHousesOrderedByBlock(agentName: String, agentUid: String): Flow<List<House>>

    @Query("SELECT DISTINCT agentName FROM houses")
    fun getDistinctAgentNames(): Flow<List<String>>

    @Query("SELECT * FROM houses WHERE isSynced = 0 AND ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName)))")
    suspend fun getUnsyncedHouses(agentName: String, agentUid: String): List<House>

    @Query("UPDATE houses SET isSynced = 1 WHERE id = :id AND lastUpdated = :timestamp")
    suspend fun markAsSyncedWithTimestamp(id: Int, timestamp: Long)

    @Query("SELECT DISTINCT data FROM houses WHERE (agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName))")
    suspend fun getDistinctDates(agentName: String, agentUid: String): List<String>

    @Query("SELECT * FROM houses WHERE id = :id")
    suspend fun getHouseById(id: Long): House?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHouse(house: House): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(houses: List<House>)

    @Update
    suspend fun updateHouse(house: House)

    @Update
    suspend fun updateAll(houses: List<House>)

    @Delete
    suspend fun deleteHouse(house: House)

    @Query("UPDATE houses SET data = :newDate, isSynced = 0, lastUpdated = :now WHERE REPLACE(data, '/', '-') = REPLACE(:oldDate, '/', '-') AND ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName)))")
    suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String, agentUid: String, now: Long = System.currentTimeMillis())


    @Query("DELETE FROM houses WHERE REPLACE(data, '/', '-') = REPLACE(:date, '/', '-') AND ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName)))")
    suspend fun deleteHousesByDateAndAgent(date: String, agentName: String, agentUid: String)

    @Query("SELECT * FROM houses WHERE REPLACE(data, '/', '-') = REPLACE(:date, '/', '-') AND ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName))) ORDER BY listOrder ASC")
    suspend fun getHousesByDateAndAgent(date: String, agentName: String, agentUid: String): List<House>

    @Transaction
    suspend fun upsertHouses(houses: List<House>) {
        // We use Upsert (insertAll with REPLACE) to update cloud data locally 
        // without deleting local records that haven't been synced yet.
        insertAll(houses)
    }

    @Transaction
    suspend fun replaceHouses(houses: List<House>) {
        deleteAll()
        insertAll(houses)
    }

    @Query("DELETE FROM houses")
    suspend fun deleteAll()

    @Query("DELETE FROM houses WHERE (agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName))")
    suspend fun deleteByAgent(agentName: String, agentUid: String)

    @Query("DELETE FROM houses WHERE ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName))) AND REPLACE(data, '/', '-') IN (:dates)")
    suspend fun deleteByAgentAndDates(agentName: String, agentUid: String, dates: List<String>)

    @Query("SELECT * FROM houses WHERE ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName))) AND REPLACE(data, '/', '-') IN (:dates) ORDER BY listOrder ASC")
    suspend fun getHousesByAgentAndDates(agentName: String, agentUid: String, dates: List<String>): List<House>

    @Query("""
        UPDATE houses 
        SET number = CASE WHEN number = '0' THEN '' ELSE number END,
            sequence = CASE WHEN sequence = 0 THEN NULL ELSE sequence END,
            complement = CASE WHEN complement = 0 THEN NULL ELSE complement END
        WHERE number = '0' OR sequence = 0 OR complement = 0
    """)
    suspend fun cleanupZeroValues()

    @Query("UPDATE houses SET agentName = :newName, isSynced = 0, lastUpdated = :now WHERE ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:oldName)))")
    suspend fun updateAgentNameForAll(oldName: String, newName: String, agentUid: String, now: Long = System.currentTimeMillis())

    @Query("""
        SELECT * FROM houses 
        WHERE (agentUid = '' OR agentUid = :targetUid) 
        AND UPPER(agentName) != UPPER(:properName) 
        AND (UPPER(agentName) = UPPER(:email) OR UPPER(agentName) = UPPER(:emailPrefix))
    """)
    suspend fun getOrphanHouses(email: String, emailPrefix: String, targetUid: String, properName: String): List<House>

    @Query("SELECT * FROM houses WHERE agentUid = '' OR agentUid IS NULL")
    suspend fun getAllOrphanHouses(): List<House>

    @Query("""
        SELECT COUNT(*) FROM houses 
        WHERE agentUid = :uid AND UPPER(agentName) = UPPER(:name) AND data = :date 
        AND UPPER(blockNumber) = UPPER(:blockNum) AND UPPER(blockSequence) = UPPER(:blockSeq) 
        AND UPPER(streetName) = UPPER(:street) AND UPPER(number) = UPPER(:num) AND sequence = :seq 
        AND complement = :compl AND UPPER(bairro) = UPPER(:bairro) AND visitSegment = :segment
    """)
    suspend fun checkClash(uid: String, name: String, date: String, blockNum: String, blockSeq: String, street: String, num: String, seq: Int, compl: Int, bairro: String, segment: Int): Int

    @Query("DELETE FROM houses WHERE id = :id")
    suspend fun deleteHouseById(id: Int)

    @Query("UPDATE houses SET agentUid = :uid, agentName = :name WHERE id = :id")
    suspend fun updateHouseIdentity(id: Int, uid: String, name: String)

    @Query("""
        SELECT COUNT(*) FROM houses 
        WHERE id != :excludeId 
        AND data = :date 
        AND UPPER(agentName) = UPPER(:agentName) 
        AND agentUid = :agentUid
        AND UPPER(blockNumber) = UPPER(:blockNumber) 
        AND UPPER(blockSequence) = UPPER(:blockSequence) 
        AND UPPER(streetName) = UPPER(:streetName) 
        AND UPPER(number) = UPPER(:number) 
        AND sequence = :sequence 
        AND complement = :complement
        AND UPPER(bairro) = UPPER(:bairro)
        AND visitSegment = :visitSegment
    """)
    suspend fun checkNaturalKeyConflict(
        excludeId: Int,
        date: String,
        agentName: String,
        agentUid: String,
        blockNumber: String,
        blockSequence: String,
        streetName: String,
        number: String,
        sequence: Int,
        complement: Int,
        bairro: String,
        visitSegment: Int
    ): Int
    @Query("SELECT * FROM houses WHERE ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName))) AND data LIKE '%-' || :monthYearSuffix")
    suspend fun getHousesByMonth(agentName: String, agentUid: String, monthYearSuffix: String): List<House>
}
