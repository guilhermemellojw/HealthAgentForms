package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow

interface HouseRepository {
    fun getAllHouses(agentName: String, agentUid: String): Flow<List<House>>
    fun getDistinctAgentNames(): Flow<List<String>>
    fun getAllHousesOrderedByBlock(agentName: String, agentUid: String): Flow<List<House>>
    suspend fun getHouseById(id: Long): House?
    suspend fun getAllHousesOnce(agentName: String, agentUid: String): List<House> // Snapshot for isolated backup
    suspend fun getAllHousesSnapshot(): List<House> // Full snapshot for auto-backup
    fun getAllHousesSnapshotFlow(): Flow<List<House>>
    suspend fun insertHouse(house: House, force: Boolean = false): Long
    suspend fun updateHouse(house: House, force: Boolean = false)
    suspend fun updateHouses(houses: List<House>, force: Boolean = false)
    suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String, agentUid: String? = null, force: Boolean = false)
    suspend fun deleteHouse(house: House, force: Boolean = false)
    suspend fun replaceAllHouses(houses: List<House>) // Restore
    suspend fun getHousesByDateAndAgent(date: String, agentName: String, agentUid: String): List<House>

    // DayActivity (Weekly Status)
    fun getDayActivities(dates: List<String>, agentName: String, agentUid: String? = null): Flow<List<DayActivity>>
    fun getDayActivityFlow(date: String, agentName: String, agentUid: String? = null): Flow<DayActivity?>
    suspend fun updateDayActivity(dayActivity: DayActivity)
    suspend fun deleteDayActivity(date: String, agentName: String, agentUid: String? = null)
    suspend fun <T> runInTransaction(block: suspend () -> T): T
    suspend fun getDayActivity(date: String, agentName: String, agentUid: String? = null): DayActivity?
    suspend fun getAllDayActivitiesOnce(agentName: String, agentUid: String): List<DayActivity>
    suspend fun getAllDayActivitiesSnapshot(): List<DayActivity>
    suspend fun replaceAllDayActivities(activities: List<DayActivity>)
    suspend fun restoreAgentData(agentName: String, houses: List<House>, activities: List<DayActivity>, agentUid: String? = null)
    suspend fun deleteProduction(date: String, agentName: String, agentUid: String? = null, force: Boolean = false) 
    suspend fun deleteByAgentAndDates(agentName: String, dates: List<String>, agentUid: String? = null, force: Boolean = false)
    suspend fun countOpenDays(agentName: String, agentUid: String? = null): Int
    suspend fun closeAllDays(agentName: String, agentUid: String? = null)
    suspend fun clearAllData()
    suspend fun migrateLocalData(agentName: String, email: String, targetUid: String)
    suspend fun deduplicateAgentData(agentName: String, agentUid: String)
    suspend fun normalizeLocalDates()
}
