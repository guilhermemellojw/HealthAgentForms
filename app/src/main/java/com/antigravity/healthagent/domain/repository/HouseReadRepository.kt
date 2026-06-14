package com.antigravity.healthagent.domain.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.Tombstone
import kotlinx.coroutines.flow.Flow

interface HouseReadRepository {
    suspend fun getUnsyncedHouses(agentUid: String): List<House>
    suspend fun getUnsyncedActivities(agentUid: String): List<DayActivity>
    suspend fun countHouses(): Int
    suspend fun getActiveBairros(agentUid: String): List<String>
    suspend fun getActiveBlockNumbers(): List<String>
    suspend fun getHousesByBlocks(blocks: List<String>): List<House>
    suspend fun getHousesByMonth(agentUid: String, monthYear: String): List<House>
    suspend fun getDayActivitiesByMonth(agentUid: String, monthYear: String): List<DayActivity>
    suspend fun getEmptyHouses(agentUid: String): List<House>
    suspend fun getAllTombstones(agentUid: String): List<Tombstone>
    fun getAllHouses(agentUid: String): Flow<List<House>>
    fun getPersonalHousesFlow(agentUid: String, agentName: String): Flow<List<House>>
    val allActivitiesFlow: Flow<List<DayActivity>>
    fun getDistinctAgentNames(): Flow<List<String>>
    fun getAllHousesOrderedByBlock(agentUid: String): Flow<List<House>>
    suspend fun getHouseById(id: Long): House?
    suspend fun getAllHousesOnce(agentUid: String): List<House>
    suspend fun getAllHousesSnapshot(): List<House>
    suspend fun getHousesByAgentSnapshot(agentUid: String): List<House>
    fun getAllHousesSnapshotFlow(): Flow<List<House>>
    fun getHousesByAgentSnapshotFlow(agentUid: String): Flow<List<House>>
    fun getParticipatoryHousesFlow(agentUid: String): Flow<List<House>>
    suspend fun getHousesByDateAndAgent(date: String, agentUid: String): List<House>
    suspend fun getLastHouseForAgent(agentUid: String): House?
    suspend fun getLastHouseForAgentOnDate(agentUid: String, date: String): House?
    fun getDayActivities(dates: List<String>, agentUid: String? = null): Flow<List<DayActivity>>
    fun getDayActivityFlow(date: String, agentUid: String? = null): Flow<DayActivity?>
    suspend fun getDayActivity(date: String, agentUid: String? = null): DayActivity?
    suspend fun getAllDayActivitiesOnce(agentUid: String): List<DayActivity>
    suspend fun getAllDayActivitiesSnapshot(): List<DayActivity>
    suspend fun getDayActivitiesByAgentSnapshot(agentUid: String): List<DayActivity>
    suspend fun countOpenDays(agentUid: String? = null): Int
}
