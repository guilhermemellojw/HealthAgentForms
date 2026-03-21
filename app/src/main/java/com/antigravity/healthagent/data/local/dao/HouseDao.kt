package com.antigravity.healthagent.data.local.dao

import androidx.room.*
import com.antigravity.healthagent.data.local.model.House
import kotlinx.coroutines.flow.Flow

@Dao
interface HouseDao {
    @Query("SELECT * FROM houses ORDER BY listOrder ASC")
    fun getAllHouses(): Flow<List<House>>

    @Query("SELECT * FROM houses ORDER BY bairro ASC, blockNumber ASC, listOrder ASC")
    fun getAllHousesOrderedByBlock(): Flow<List<House>>

    @Query("SELECT DISTINCT agentName FROM houses")
    fun getDistinctAgentNames(): Flow<List<String>>

    @Query("SELECT * FROM houses WHERE isSynced = 0")
    suspend fun getUnsyncedHouses(): List<House>

    @Query("UPDATE houses SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Int>)

    @Query("SELECT DISTINCT data FROM houses WHERE UPPER(agentName) = UPPER(:agentName)")
    suspend fun getDistinctDates(agentName: String): List<String>

    @Query("SELECT * FROM houses WHERE id = :id")
    suspend fun getHouseById(id: Long): House?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHouse(house: House)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(houses: List<House>)

    @Update
    suspend fun updateHouse(house: House)

    @Update
    suspend fun updateAll(houses: List<House>)

    @Delete
    suspend fun deleteHouse(house: House)

    @Query("UPDATE houses SET data = :newDate, isSynced = 0, lastUpdated = :now WHERE data = :oldDate AND UPPER(agentName) = UPPER(:agentName)")
    suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String, now: Long = System.currentTimeMillis())


    @Query("DELETE FROM houses WHERE data = :date AND UPPER(agentName) = UPPER(:agentName)")
    suspend fun deleteHousesByDateAndAgent(date: String, agentName: String)

    @Query("SELECT * FROM houses WHERE data = :date AND UPPER(agentName) = UPPER(:agentName) ORDER BY listOrder ASC")
    suspend fun getHousesByDateAndAgent(date: String, agentName: String): List<House>

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

    @Query("DELETE FROM houses WHERE UPPER(agentName) = UPPER(:agentName)")
    suspend fun deleteByAgent(agentName: String)

    @Query("DELETE FROM houses WHERE UPPER(agentName) = UPPER(:agentName) AND data IN (:dates)")
    suspend fun deleteByAgentAndDates(agentName: String, dates: List<String>)

    @Query("SELECT * FROM houses WHERE UPPER(agentName) = UPPER(:agentName) AND data IN (:dates) ORDER BY listOrder ASC")
    suspend fun getHousesByAgentAndDates(agentName: String, dates: List<String>): List<House>

    @Query("""
        UPDATE houses 
        SET number = CASE WHEN number = '0' THEN '' ELSE number END,
            sequence = CASE WHEN sequence = 0 THEN NULL ELSE sequence END,
            complement = CASE WHEN complement = 0 THEN NULL ELSE complement END
        WHERE number = '0' OR sequence = 0 OR complement = 0
    """)
    suspend fun cleanupZeroValues()

    @Query("UPDATE houses SET agentName = :newName WHERE agentName = :oldName")
    suspend fun updateAgentNameForAll(oldName: String, newName: String)
}
