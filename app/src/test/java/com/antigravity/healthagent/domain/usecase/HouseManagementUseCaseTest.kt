package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.dao.CustomStreetDao
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.model.CustomStreet
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.VisitAddress
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.every

class HouseManagementUseCaseTest {

    private val repository: HouseRepository = mockk(relaxed = true)
    private val streetRepository: StreetRepository = mockk(relaxed = true)
    private val useCase = HouseManagementUseCase(repository, streetRepository)
    private val date = "2026-01-26"

    @Test
    fun `predictNextHouseValues - empty list returns default`() {
        // When
        val prediction = useCase.predictNextHouseValues(emptyList(), date, "", "")
        
        // Then
        assertEquals("", prediction.number)
        assertEquals(0, prediction.sequence)
        assertEquals(PropertyType.EMPTY, prediction.propertyType)
        assertEquals(Situation.NONE, prediction.situation)
    }

    @Test
    fun `predictNextHouseValues - single house increments number`() {
        val houses = listOf(
            House(id = 1, address = VisitAddress(number = "10", blockNumber = "A", streetName = "B"), listOrder = 1, data = date)
        )
        val prediction = useCase.predictNextHouseValues(houses, date, "A", "B")
        assertEquals("11", prediction.number)
    }

    @Test
    fun `predictNextHouseValues - two houses with incrementing numbers`() {
        val houses = listOf(
            House(id = 1, address = VisitAddress(number = "10", blockNumber = "A", streetName = "B"), listOrder = 1, data = date),
            House(id = 2, address = VisitAddress(number = "12", blockNumber = "A", streetName = "B"), listOrder = 2, data = date)
        )
        val prediction = useCase.predictNextHouseValues(houses, date, "A", "B")
        assertEquals("14", prediction.number) // 10 -> 12 (+2), so 12+2=14
    }

    @Test
    fun `predictNextHouseValues - numbers the same, increments sequence`() {
        val houses = listOf(
            House(id = 1, address = VisitAddress(number = "10", sequence = 1, blockNumber = "A", streetName = "B"), listOrder = 1, data = date),
            House(id = 2, address = VisitAddress(number = "10", sequence = 2, blockNumber = "A", streetName = "B"), listOrder = 2, data = date)
        )
        val prediction = useCase.predictNextHouseValues(houses, date, "A", "B")
        assertEquals("10", prediction.number)
        assertEquals(3, prediction.sequence)
    }
}
