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

    @Query("UPDATE houses SET data = :newDate WHERE data = :oldDate AND UPPER(agentName) = UPPER(:agentName)")
    suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String)


    @Query("DELETE FROM houses WHERE data = :date AND UPPER(agentName) = UPPER(:agentName)")
    suspend fun deleteHousesByDateAndAgent(date: String, agentName: String)

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
}
