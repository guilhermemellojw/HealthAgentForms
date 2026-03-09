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
        override fun getAllHouses(): Flow<List<House>> = flowOf(emptyList())
        override fun getAllHousesOrderedByBlock(): Flow<List<House>> = flowOf(emptyList())
        override fun getDayActivities(dates: List<String>, agentName: String): Flow<List<DayActivity>> = flowOf(emptyList())
        override fun getDayActivityFlow(date: String, agentName: String): Flow<DayActivity?> = flowOf(null)
        override suspend fun getHouseById(id: Long): House? = null
        override suspend fun getAllHousesOnce(): List<House> = emptyList()
        override suspend fun insertHouse(house: House) {}
        override suspend fun updateHouse(house: House) {}
        override suspend fun updateHouses(houses: List<House>) {}
        override suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String) {}
        override suspend fun deleteHouse(house: House) {}
        override suspend fun replaceAllHouses(houses: List<House>) {}
        override suspend fun updateDayActivity(dayActivity: DayActivity) {}
        override suspend fun getDayActivity(date: String, agentName: String): DayActivity? = null
        override suspend fun getAllDayActivitiesOnce(): List<DayActivity> = emptyList()
        override suspend fun replaceAllDayActivities(activities: List<DayActivity>) {}
        override suspend fun restoreAgentData(agentName: String, houses: List<House>, activities: List<DayActivity>) {}
        override suspend fun deleteProduction(date: String, agentName: String) {}
        override suspend fun countOpenDays(agentName: String): Int = 0
        override suspend fun closeAllDays(agentName: String) {}
        override suspend fun clearAllData() {}
    }

    private val dummyHouseDao = object : HouseDao {
        override fun getAllHouses(): Flow<List<House>> = flowOf(emptyList())
        override fun getAllHousesOrderedByBlock(): Flow<List<House>> = flowOf(emptyList())
        override suspend fun getDistinctDates(agentName: String): List<String> = emptyList()
        override suspend fun getHouseById(id: Long): House? = null
        override suspend fun insertHouse(house: House) {}
        override suspend fun insertAll(houses: List<House>) {}
        override suspend fun updateHouse(house: House) {}
        override suspend fun updateAll(houses: List<House>) {}
        override suspend fun deleteHouse(house: House) {}
        override suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String) {}
        override suspend fun deleteHousesByDateAndAgent(date: String, agentName: String) {}
        override suspend fun deleteAll() {}
        override suspend fun deleteByAgent(agentName: String) {}
    }

    private val dummyCustomStreetDao = object : CustomStreetDao {
        override fun getAllCustomStreets(): Flow<List<CustomStreet>> = flowOf(emptyList())
        override suspend fun insertCustomStreet(street: CustomStreet) {}
        override suspend fun deleteCustomStreet(street: CustomStreet) {}
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
