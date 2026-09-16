package com.antigravity.healthagent.domain.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.Tombstone

interface HouseWriteRepository {
    suspend fun upsertHousesRaw(houses: List<House>)
    suspend fun upsertDayActivitiesRaw(activities: List<DayActivity>)
    suspend fun deleteHousesByDateAndAgent(date: String, agentUid: String)
    suspend fun deleteHouseById(id: Int)
    suspend fun markHouseAsSynced(id: Int, lastUpdated: Long, agentUid: String, agentName: String)
    suspend fun markActivityAsSynced(date: String, agentName: String, agentUid: String, lastUpdated: Long)
    suspend fun cleanupZeroValues()
    suspend fun insertTombstones(tombstones: List<Tombstone>)
    suspend fun insertTombstone(tombstone: Tombstone)
    suspend fun deleteTombstoneByNaturalKey(naturalKey: String, agentUid: String)
    suspend fun deleteTombstones(ids: List<Int>)
    suspend fun deleteTombstonesByAgent(agentUid: String)
    suspend fun pruneOldTombstones(threshold: Long)
    suspend fun insertHouse(house: House, force: Boolean = false): Long
    suspend fun updateHouse(house: House, force: Boolean = false)
    suspend fun updateHouses(houses: List<House>, force: Boolean = false)
    suspend fun updateHousesDate(oldDate: String, newDate: String, agentUid: String, force: Boolean = false)
    suspend fun deleteHouse(house: House, force: Boolean = false)
    suspend fun replaceAllHouses(houses: List<House>)
    suspend fun updateDayActivity(dayActivity: DayActivity, force: Boolean = false)
    suspend fun deleteDayActivity(date: String, agentUid: String? = null)
    suspend fun <T> runInTransaction(block: suspend () -> T): T
    suspend fun replaceAllDayActivities(activities: List<DayActivity>)
    suspend fun updateBatchSegments(updates: List<Pair<Int, Int>>)
    suspend fun updateBatchOrders(updates: List<Triple<Int, Long, Int>>)
    suspend fun restoreAgentData(houses: List<House>, activities: List<DayActivity>, agentUid: String? = null)
    suspend fun deleteProduction(date: String, agentUid: String? = null, force: Boolean = false)
    suspend fun deleteByAgentAndDates(dates: List<String>, agentUid: String? = null, force: Boolean = false)
    suspend fun closeAllDays(agentUid: String? = null)
    suspend fun clearAllData()
    suspend fun clearAgentData(agentUid: String)
    suspend fun migrateLocalData(agentName: String, email: String, targetUid: String, isCurrentAgent: Boolean = false)
    suspend fun deduplicateAgentData(agentUid: String)
    suspend fun cleanMisattributedData(inspectedUid: String, adminUid: String)
    suspend fun normalizeLocalDates()
    suspend fun fixEmailNamesForUid(uid: String, properName: String)
}
