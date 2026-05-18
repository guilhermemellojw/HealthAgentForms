package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.VisitAddress
import org.junit.Assert.*
import org.junit.Test

class RecalculateVisitSegmentsUseCaseTest {

    private val useCase = RecalculateVisitSegmentsUseCase()

    private fun createHouse(id: Int, street: String, listOrder: Int): House {
        return House(
            id = id,
            data = "13-05-2026",
            address = VisitAddress(
                bairro = "Centro",
                streetName = street,
                blockNumber = "1",
                number = id.toString()
            ),
            listOrder = listOrder.toLong(),
            propertyType = PropertyType.R,
            situation = Situation.NONE
        )
    }

    @Test
    fun `recalculateVisitSegments - empty list returns empty list`() {
        val result = useCase.recalculateVisitSegments(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `recalculateVisitSegments - single street keeps same segment`() {
        val houses = listOf(
            createHouse(1, "Rua A", 1),
            createHouse(2, "Rua A", 2)
        )
        val result = useCase.recalculateVisitSegments(houses)
        
        assertEquals(2, result.size)
        assertEquals(0, result[0].visitSegment)
        assertEquals(0, result[1].visitSegment)
    }

    @Test
    fun `recalculateVisitSegments - changing street increments segment`() {
        val houses = listOf(
            createHouse(1, "Rua A", 1),
            createHouse(2, "Rua B", 2),
            createHouse(3, "Rua B", 3),
            createHouse(4, "Rua A", 4)
        )
        val result = useCase.recalculateVisitSegments(houses)
        
        assertEquals(4, result.size)
        assertEquals(0, result[0].visitSegment) // Rua A
        assertEquals(1, result[1].visitSegment) // Rua B
        assertEquals(1, result[2].visitSegment) // Rua B
        assertEquals(2, result[3].visitSegment) // Rua A (changed back)
    }

    @Test
    fun `recalculateVisitSegments - sorting by listOrder is enforced`() {
        // Out of order input list
        val houses = listOf(
            createHouse(2, "Rua B", 2),
            createHouse(1, "Rua A", 1)
        )
        val result = useCase.recalculateVisitSegments(houses)
        
        assertEquals(2, result.size)
        // Should sort Rua A (listOrder 1) first, then Rua B (listOrder 2)
        assertEquals(1, result[0].id)
        assertEquals(0, result[0].visitSegment)
        
        assertEquals(2, result[1].id)
        assertEquals(1, result[1].visitSegment)
    }
}
