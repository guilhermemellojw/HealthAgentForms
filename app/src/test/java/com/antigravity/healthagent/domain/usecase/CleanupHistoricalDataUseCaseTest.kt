package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.SyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.*

class CleanupHistoricalDataUseCaseTest {

    private val deletedHouses = mutableListOf<House>()
    private val deletedActivityDates = mutableListOf<String>()
    private var bulkDeletionsCalled = false

    private val mockHouseRepository = object : HouseRepository {
        override fun getAllHouses(agentName: String, agentUid: String): Flow<List<House>> = flowOf(emptyList())
        override fun getDistinctAgentNames(): Flow<List<String>> = flowOf(emptyList())
        override fun getAllHousesOrderedByBlock(agentName: String, agentUid: String): Flow<List<House>> = flowOf(emptyList())
        override suspend fun getHouseById(id: Long): House? = null
        override suspend fun getAllHousesOnce(agentName: String, agentUid: String): List<House> {
            return listOf(
                House(id = 1, number = "1", data = "01-01-2026", agentName = "TEST"),
                House(id = 2, number = "2", data = "05/01/2026", agentName = "TEST"), // Mixed format
                House(id = 3, number = "3", data = "10-01-2026", agentName = "TEST")
            )
        }
        override suspend fun getAllHousesSnapshot(): List<House> = emptyList()
        override fun getAllHousesSnapshotFlow(): Flow<List<House>> = flowOf(emptyList())
        override suspend fun insertHouse(house: House): Long = 0L
        override suspend fun updateHouse(house: House) {}
        override suspend fun updateHouses(houses: List<House>) {}
        override suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String, agentUid: String?) {}
        override suspend fun deleteHouse(house: House) { deletedHouses.add(house) }
        override suspend fun replaceAllHouses(houses: List<House>) {}
        override fun getDayActivities(dates: List<String>, agentName: String, agentUid: String?): Flow<List<DayActivity>> = flowOf(emptyList())
        override fun getDayActivityFlow(date: String, agentName: String, agentUid: String?): Flow<DayActivity?> = flowOf(null)
        override suspend fun updateDayActivity(dayActivity: DayActivity) {}
        override suspend fun deleteDayActivity(date: String, agentName: String, agentUid: String?) {}
        override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        override suspend fun getDayActivity(date: String, agentName: String, agentUid: String?): DayActivity? = null
        override suspend fun getAllDayActivitiesOnce(agentName: String, agentUid: String): List<DayActivity> {
            return listOf(
                DayActivity(date = "01-01-2026", agentName = "TEST"),
                DayActivity(date = "10-01-2026", agentName = "TEST")
            )
        }
        override suspend fun getAllDayActivitiesSnapshot(): List<DayActivity> = emptyList()
        override suspend fun replaceAllDayActivities(activities: List<DayActivity>) {}
        override suspend fun restoreAgentData(agentName: String, houses: List<House>, activities: List<DayActivity>, agentUid: String?) {}
        override suspend fun deleteProduction(date: String, agentName: String, agentUid: String?) {}
        override suspend fun deleteByAgentAndDates(agentName: String, dates: List<String>, agentUid: String?) {
            deletedActivityDates.addAll(dates)
        }
        override suspend fun countOpenDays(agentName: String, agentUid: String?): Int = 0
        override suspend fun closeAllDays(agentName: String, agentUid: String?) {}
        override suspend fun clearAllData() {}
        override suspend fun migrateLocalData(agentName: String, email: String, targetUid: String) {}
        override suspend fun deduplicateAgentData(agentName: String, agentUid: String) {}
        override suspend fun normalizeLocalDates() {}
        override suspend fun getHousesByDateAndAgent(date: String, agentName: String, agentUid: String): List<House> = emptyList()
    }

    private val mockSyncRepository = object : SyncRepository {
        override suspend fun pushLocalDataToCloud(houses: List<House>, activities: List<DayActivity>, targetUid: String?, shouldReplace: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun pullCloudDataToLocal(targetUid: String?, force: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun clearLocalData(): Result<Unit> = Result.success(Unit)
        override suspend fun restoreLocalData(agentName: String, houses: List<House>, activities: List<DayActivity>, agentUid: String?): Result<Unit> = Result.success(Unit)
        override suspend fun fetchSystemSettings(): Result<Map<String, Any>> = Result.success(emptyMap())
        override suspend fun updateSystemSetting(key: String, value: Any): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAgentHouse(agentUid: String, houseId: String): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAgentActivity(agentUid: String, activityDate: String): Result<Unit> = Result.success(Unit)
        override suspend fun recordHouseDeletion(house: House): Result<Unit> = Result.success(Unit)
        override suspend fun recordActivityDeletion(date: String, agentName: String, agentUid: String): Result<Unit> = Result.success(Unit)
        override suspend fun recordBulkDeletions(houseKeys: List<String>, activityDates: List<String>, targetUid: String?): Result<Unit> {
            bulkDeletionsCalled = true
            return Result.success(Unit)
        }
        override suspend fun deleteAllCloudData(): Result<Unit> = Result.success(Unit)
        override suspend fun performDataCleanup(): Result<Unit> = Result.success(Unit)
        override suspend fun clearSyncError(uid: String): Result<Unit> = Result.success(Unit)
    }

    private val mockAgentRepository = object : AgentRepository {
        override suspend fun createAgent(email: String, agentName: String?): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAgent(uid: String): Result<Unit> = Result.success(Unit)
        override suspend fun fetchAgentNames(): Result<List<String>> = Result.success(emptyList())
        override suspend fun addAgentName(name: String): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAgentName(name: String): Result<Unit> = Result.success(Unit)
        override suspend fun fetchAllAgentsData(sinceTimestamp: Long, untilTimestamp: Long, datePattern: String?): Result<List<AgentData>> = Result.success(emptyList())
        override suspend fun deleteAgentHouse(uid: String, houseId: String): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAgentActivity(uid: String, activityDate: String): Result<Unit> = Result.success(Unit)
        override suspend fun clearSyncError(uid: String): Result<Unit> = Result.success(Unit)
        override suspend fun transferAgentData(fromUid: String, toUid: String): Result<Unit> = Result.success(Unit)
    }

    private val useCase = CleanupHistoricalDataUseCase(mockHouseRepository, mockSyncRepository, mockAgentRepository)

    @Test
    fun `cleanup - deletes houses and activities before limit date handling mixed formats`() = runBlocking {
        // Given: Limit date is 06-01-2026
        // Houses: 01-01-2026 (Before), 05/01/2026 (Before), 10-01-2026 (After)
        // Activities: 01-01-2026 (Before), 10-01-2026 (After)

        // When
        val result = useCase("06-01-2026", "TEST", "uid123", isGlobal = false)

        // Then
        assertEquals(true, result.isSuccess)
        // Should delete 2 houses (01-01 and 05-01)
        assertEquals(2, deletedHouses.size)
        assertEquals(1, deletedHouses[0].id)
        assertEquals(2, deletedHouses[1].id)

        // Should delete 1 activity (01-01-2026)
        assertEquals(1, deletedActivityDates.size)
        assertEquals("01-01-2026", deletedActivityDates[0])
    }
}
