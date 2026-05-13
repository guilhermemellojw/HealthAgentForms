package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow

interface HouseRepository {
    fun getAllHouses(agentUid: String): Flow<List<House>>
    val allActivitiesFlow: Flow<List<DayActivity>>
    fun getDistinctAgentNames(): Flow<List<String>>
    fun getAllHousesOrderedByBlock(agentUid: String): Flow<List<House>>
    suspend fun getHouseById(id: Long): House?
    suspend fun getAllHousesOnce(agentUid: String): List<House> // Snapshot for isolated backup
    suspend fun getAllHousesSnapshot(): List<House> // Full snapshot for auto-backup
    suspend fun getHousesByAgentSnapshot(agentUid: String): List<House>
    fun getAllHousesSnapshotFlow(): Flow<List<House>>
    fun getHousesByAgentSnapshotFlow(agentUid: String): Flow<List<House>>
    fun getParticipatoryHousesFlow(agentUid: String): Flow<List<House>>
    suspend fun insertHouse(house: House, force: Boolean = false): Long
    suspend fun updateHouse(house: House, force: Boolean = false)
    suspend fun updateHouses(houses: List<House>, force: Boolean = false)
    suspend fun updateHousesDate(oldDate: String, newDate: String, agentUid: String, force: Boolean = false)
    suspend fun deleteHouse(house: House, force: Boolean = false)
    suspend fun replaceAllHouses(houses: List<House>) // Restore
    suspend fun getHousesByDateAndAgent(date: String, agentUid: String): List<House>
    suspend fun getLastHouseForAgent(agentUid: String): House?
    suspend fun getLastHouseForAgentOnDate(agentUid: String, date: String): House?

    // DayActivity (Weekly Status)
    fun getDayActivities(dates: List<String>, agentUid: String? = null): Flow<List<DayActivity>>
    fun getDayActivityFlow(date: String, agentUid: String? = null): Flow<DayActivity?>
    suspend fun updateDayActivity(dayActivity: DayActivity, force: Boolean = false)
    suspend fun deleteDayActivity(date: String, agentUid: String? = null)
    suspend fun <T> runInTransaction(block: suspend () -> T): T
    suspend fun getDayActivity(date: String, agentUid: String? = null): DayActivity?
    suspend fun getAllDayActivitiesOnce(agentUid: String): List<DayActivity>
    suspend fun getAllDayActivitiesSnapshot(): List<DayActivity>
    suspend fun getDayActivitiesByAgentSnapshot(agentUid: String): List<DayActivity>
    suspend fun replaceAllDayActivities(activities: List<DayActivity>)
    suspend fun restoreAgentData(houses: List<House>, activities: List<DayActivity>, agentUid: String? = null)
    suspend fun deleteProduction(date: String, agentUid: String? = null, force: Boolean = false) 
    suspend fun deleteByAgentAndDates(dates: List<String>, agentUid: String? = null, force: Boolean = false)
    suspend fun countOpenDays(agentUid: String? = null): Int
    suspend fun closeAllDays(agentUid: String? = null)
    suspend fun clearAllData()
    suspend fun clearAgentData(agentUid: String)
    suspend fun migrateLocalData(agentName: String, email: String, targetUid: String, isCurrentAgent: Boolean = false)
    suspend fun deduplicateAgentData(agentUid: String)
    suspend fun cleanMisattributedData(inspectedUid: String, adminUid: String)
    suspend fun normalizeLocalDates()
    suspend fun fixEmailNamesForUid(uid: String, properName: String)
}
