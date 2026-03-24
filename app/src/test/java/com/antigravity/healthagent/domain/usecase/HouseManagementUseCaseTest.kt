package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.dao.CustomStreetDao
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.model.CustomStreet
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HouseManagementUseCaseTest {

    // Dummy HouseRepository since predictNextHouseValues doesn't use it
    private val dummyRepository = object : HouseRepository {
        override fun getAllHouses(agentName: String, agentUid: String): Flow<List<House>> = flowOf(emptyList())
        override fun getDistinctAgentNames(): Flow<List<String>> = flowOf(emptyList())
        override fun getAllHousesOrderedByBlock(agentName: String, agentUid: String): Flow<List<House>> = flowOf(emptyList())
        override suspend fun getHouseById(id: Long): House? = null
        override suspend fun getAllHousesOnce(agentName: String, agentUid: String): List<House> = emptyList()
        override suspend fun getAllHousesSnapshot(): List<House> = emptyList()
        override suspend fun insertHouse(house: House) {}
        override suspend fun updateHouse(house: House) {}
        override suspend fun updateHouses(houses: List<House>) {}
        override suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String, agentUid: String?) {}
        override suspend fun deleteHouse(house: House) {}
        override suspend fun replaceAllHouses(houses: List<House>) {}
        override fun getDayActivities(dates: List<String>, agentName: String, agentUid: String?): Flow<List<DayActivity>> = flowOf(emptyList())
        override fun getDayActivityFlow(date: String, agentName: String, agentUid: String?): Flow<DayActivity?> = flowOf(null)
        override suspend fun updateDayActivity(dayActivity: DayActivity) {}
        override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        override suspend fun getDayActivity(date: String, agentName: String, agentUid: String?): DayActivity? = null
        override suspend fun getAllDayActivitiesOnce(agentName: String, agentUid: String): List<DayActivity> = emptyList()
        override suspend fun getAllDayActivitiesSnapshot(): List<DayActivity> = emptyList()
        override suspend fun replaceAllDayActivities(activities: List<DayActivity>) {}
        override suspend fun restoreAgentData(agentName: String, houses: List<House>, activities: List<DayActivity>, agentUid: String?) {}
        override suspend fun deleteProduction(date: String, agentName: String, agentUid: String?) {}
        override suspend fun deleteByAgentAndDates(agentName: String, dates: List<String>, agentUid: String?) {}
        override suspend fun countOpenDays(agentName: String, agentUid: String?): Int = 0
        override suspend fun closeAllDays(agentName: String, agentUid: String?) {}
        override suspend fun clearAllData() {}
        override suspend fun migrateLocalData(agentName: String, email: String, targetUid: String) {}
    }

    private val dummyHouseDao = object : HouseDao {
        override fun getAllHousesSnapshot(): List<House> = emptyList()
        override fun getAllHousesSnapshotFlow(): Flow<List<House>> = flowOf(emptyList())
        override suspend fun count(): Int = 0
        override fun getAllHouses(agentName: String, agentUid: String): Flow<List<House>> = flowOf(emptyList())
        override fun getAllHousesOrderedByBlock(agentName: String, agentUid: String): Flow<List<House>> = flowOf(emptyList())
        override fun getDistinctAgentNames(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun getUnsyncedHouses(agentName: String, agentUid: String): List<House> = emptyList()
        override suspend fun markAsSynced(ids: List<Int>) {}
        override suspend fun getDistinctDates(agentName: String, agentUid: String): List<String> = emptyList()
        override suspend fun getHouseById(id: Long): House? = null
        override suspend fun insertHouse(house: House) {}
        override suspend fun insertAll(houses: List<House>) {}
        override suspend fun updateHouse(house: House) {}
        override suspend fun updateAll(houses: List<House>) {}
        override suspend fun deleteHouse(house: House) {}
        override suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String, agentUid: String, now: Long) {}
        override suspend fun deleteHousesByDateAndAgent(date: String, agentName: String, agentUid: String) {}
        override suspend fun getHousesByDateAndAgent(date: String, agentName: String, agentUid: String): List<House> = emptyList()
        override suspend fun deleteAll() {}
        override suspend fun deleteByAgent(agentName: String, agentUid: String) {}
        override suspend fun deleteByAgentAndDates(agentName: String, agentUid: String, dates: List<String>) {}
        override suspend fun getHousesByAgentAndDates(agentName: String, agentUid: String, dates: List<String>): List<House> = emptyList()
        override suspend fun cleanupZeroValues() {}
        override suspend fun updateAgentNameForAll(oldName: String, newName: String, agentUid: String) {}
        override suspend fun updateAgentUidForAll(agentName: String, email: String, targetUid: String) {}
    }

    private val dummyCustomStreetDao = object : CustomStreetDao {
        override fun getAllCustomStreets(): Flow<List<CustomStreet>> = flowOf(emptyList())
        override suspend fun insertCustomStreet(street: CustomStreet) {}
        override suspend fun deleteCustomStreet(street: CustomStreet) {}
    }

    private val dummySyncRepository = object : com.antigravity.healthagent.domain.repository.SyncRepository {
        override suspend fun pushLocalDataToCloud(houses: List<House>, activities: List<DayActivity>, targetUid: String?, shouldReplace: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun fetchAllAgentsData(): Result<List<com.antigravity.healthagent.domain.repository.AgentData>> = Result.success(emptyList())
        override suspend fun pullCloudDataToLocal(targetUid: String?): Result<Unit> = Result.success(Unit)
        override suspend fun createAgent(email: String, agentName: String?): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAgent(uid: String): Result<Unit> = Result.success(Unit)
        override suspend fun fetchAgentNames(): Result<List<String>> = Result.success(emptyList())
        override suspend fun addAgentName(name: String): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAgentName(name: String): Result<Unit> = Result.success(Unit)
        override suspend fun clearLocalData(): Result<Unit> = Result.success(Unit)
        override suspend fun restoreLocalData(agentName: String, houses: List<House>, activities: List<DayActivity>, agentUid: String?): Result<Unit> = Result.success(Unit)
        override suspend fun fetchBairros(): Result<List<String>> = Result.success(emptyList())
        override suspend fun addBairro(name: String): Result<Unit> = Result.success(Unit)
        override suspend fun deleteBairro(name: String): Result<Unit> = Result.success(Unit)
        override suspend fun fetchSystemSettings(): Result<Map<String, Any>> = Result.success(emptyMap())
        override suspend fun updateSystemSetting(key: String, value: Any): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAgentHouse(agentUid: String, houseId: String): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAgentActivity(agentUid: String, activityDate: String): Result<Unit> = Result.success(Unit)
        override suspend fun recordHouseDeletion(house: House): Result<Unit> = Result.success(Unit)
        override suspend fun recordActivityDeletion(date: String, agentName: String): Result<Unit> = Result.success(Unit)
        override suspend fun recordBulkDeletions(houseKeys: List<String>, activityDates: List<String>, targetUid: String?): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAllCloudData(): Result<Unit> = Result.success(Unit)
        override suspend fun performDataCleanup(): Result<Unit> = Result.success(Unit)
        override suspend fun clearSyncError(uid: String): Result<Unit> = Result.success(Unit)
    }

    // Real StreetRepository with dummy DAOs
    private val streetRepository = StreetRepository(dummyHouseDao, dummyCustomStreetDao)

    private val useCase = HouseManagementUseCase(dummyRepository, streetRepository)
    private val date = "2026-01-26"

    @Test
    fun `predictNextHouseValues - empty list returns default`() {
        // When
        val prediction = useCase.predictNextHouseValues(emptyList(), date, "", "")
        
        // Then
        assertEquals("", prediction.number)
        assertNull(prediction.sequence)
        assertEquals(PropertyType.EMPTY, prediction.propertyType)
        assertEquals(Situation.NONE, prediction.situation)
    }

    @Test
    fun `predictNextHouseValues - single house increments number`() {
        val houses = listOf(
            House(id = 1, number = "10", listOrder = 1, data = date, blockNumber = "A", streetName = "B")
        )
        val prediction = useCase.predictNextHouseValues(houses, date, "A", "B")
        assertEquals("11", prediction.number)
    }

    @Test
    fun `predictNextHouseValues - two houses with incrementing numbers`() {
        val houses = listOf(
            House(id = 1, number = "10", listOrder = 1, data = date, blockNumber = "A", streetName = "B"),
            House(id = 2, number = "12", listOrder = 2, data = date, blockNumber = "A", streetName = "B")
        )
        val prediction = useCase.predictNextHouseValues(houses, date, "A", "B")
        assertEquals("14", prediction.number) // 10 -> 12 (+2), so 12+2=14
    }

    @Test
    fun `predictNextHouseValues - numbers the same, increments sequence`() {
        val houses = listOf(
            House(id = 1, number = "10", sequence = 1, listOrder = 1, data = date, blockNumber = "A", streetName = "B"),
            House(id = 2, number = "10", sequence = 2, listOrder = 2, data = date, blockNumber = "A", streetName = "B")
        )
        val prediction = useCase.predictNextHouseValues(houses, date, "A", "B")
        assertEquals("10", prediction.number)
        assertEquals(3, prediction.sequence)
    }
}
