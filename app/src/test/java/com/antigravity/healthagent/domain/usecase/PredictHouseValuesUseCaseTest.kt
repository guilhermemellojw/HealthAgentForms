package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.VisitAddress
import org.junit.Assert.*
import org.junit.Test

class PredictHouseValuesUseCaseTest {

    private val useCase = PredictHouseValuesUseCase()
    private val date = "13-05-2026"
    private val block = "1"
    private val street = "Rua Principal"

    private fun createHouse(
        id: Int,
        number: String,
        sequence: Int = 0,
        complement: Int = 0,
        listOrder: Int = id
    ): House {
        return House(
            id = id,
            data = date,
            address = VisitAddress(
                bairro = "Centro",
                streetName = street,
                blockNumber = block,
                number = number,
                sequence = sequence,
                complement = complement
            ),
            listOrder = listOrder.toLong(),
            propertyType = PropertyType.R,
            situation = Situation.NONE
        )
    }

    @Test
    fun `predictNextHouseValues - empty context returns default prediction`() {
        val prediction = useCase.predictNextHouseValues(emptyList(), date, block, street)
        
        assertEquals("", prediction.number)
        assertEquals(0, prediction.sequence)
        assertEquals(0, prediction.complement)
        assertEquals(PropertyType.EMPTY, prediction.propertyType)
    }

    @Test
    fun `predictNextHouseValues - single house with number returns next number`() {
        val houses = listOf(createHouse(1, "100"))
        val prediction = useCase.predictNextHouseValues(houses, date, block, street)
        
        assertEquals("101", prediction.number)
        assertEquals(0, prediction.sequence)
        assertEquals(0, prediction.complement)
    }

    @Test
    fun `predictNextHouseValues - single house with sequence returns next sequence`() {
        val houses = listOf(createHouse(1, "", sequence = 5))
        val prediction = useCase.predictNextHouseValues(houses, date, block, street)
        
        assertEquals("", prediction.number)
        assertEquals(6, prediction.sequence)
        assertEquals(0, prediction.complement)
    }

    @Test
    fun `predictNextHouseValues - arithmetic progression of even numbers`() {
        val houses = listOf(
            createHouse(1, "100", listOrder = 1),
            createHouse(2, "102", listOrder = 2)
        )
        val prediction = useCase.predictNextHouseValues(houses, date, block, street)
        
        assertEquals("104", prediction.number)
        assertEquals(0, prediction.sequence)
    }

    @Test
    fun `predictNextHouseValues - arithmetic progression of odd numbers with suffix`() {
        val houses = listOf(
            createHouse(1, "1A", listOrder = 1),
            createHouse(2, "3A", listOrder = 2)
        )
        val prediction = useCase.predictNextHouseValues(houses, date, block, street)
        
        assertEquals("5A", prediction.number)
        assertEquals(0, prediction.sequence)
    }

    @Test
    fun `predictBasedOnHistory - single house with complement returns next complement`() {
        val referenceHouse = createHouse(1, "100", complement = 3)
        val houses = listOf(referenceHouse)
        val prediction = useCase.predictBasedOnHistory(houses, referenceHouse)
        
        assertEquals("100", prediction.number)
        assertEquals(4, prediction.complement)
    }
}
