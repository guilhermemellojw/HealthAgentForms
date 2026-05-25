package com.antigravity.healthagent.domain.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.Tombstone
import kotlinx.coroutines.flow.Flow

/**
 * Interface contract for the House and DayActivity data layer.
 * Resides in the Domain Layer (Clean Architecture) and acts as the unique entry point
 * for all database-related operations, queries, and atomic modifications.
 */
interface HouseRepository {
    // Métodos de Consulta do Sync
    suspend fun getUnsyncedHouses(agentUid: String): List<House>
    suspend fun getUnsyncedActivities(agentUid: String): List<DayActivity>
    suspend fun countHouses(): Int
    suspend fun getActiveBairros(agentUid: String): List<String>
    suspend fun getActiveBlockNumbers(): List<String>
    suspend fun getHousesByBlocks(blocks: List<String>): List<House>
    suspend fun getHousesByMonth(agentUid: String, monthYear: String): List<House>
    suspend fun getDayActivitiesByMonth(agentUid: String, monthYear: String): List<DayActivity>
    suspend fun getEmptyHouses(agentUid: String): List<House>

    // Métodos de Mutação Direta (Ignoram fechamento de dias para Reconciliação do Sync)
    suspend fun upsertHousesRaw(houses: List<House>)
    suspend fun upsertDayActivitiesRaw(activities: List<DayActivity>)
    suspend fun deleteHousesByDateAndAgent(date: String, agentUid: String)
    suspend fun deleteHouseById(id: Int)
    suspend fun markHouseAsSynced(id: Int, lastUpdated: Long, agentUid: String, agentName: String)
    suspend fun markActivityAsSynced(date: String, agentName: String, agentUid: String, lastUpdated: Long)
    suspend fun cleanupZeroValues()

    // Métodos do TombstoneDao
    suspend fun getAllTombstones(agentUid: String): List<Tombstone>
    suspend fun insertTombstones(tombstones: List<Tombstone>)
    suspend fun insertTombstone(tombstone: Tombstone)
    suspend fun deleteTombstoneByNaturalKey(naturalKey: String, agentUid: String)
    suspend fun deleteTombstones(ids: List<Int>)
    suspend fun deleteTombstonesByAgent(agentUid: String)
    suspend fun pruneOldTombstones(threshold: Long)

    fun getAllHouses(agentUid: String): Flow<List<House>>
    fun getPersonalHousesFlow(agentUid: String, agentName: String): Flow<List<House>>
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
