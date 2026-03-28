package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class HouseManagementUseCaseRecalculateTest {

    private val repository: HouseRepository = mockk()
    private val streetRepository: StreetRepository = mockk()
    private val useCase = HouseManagementUseCase(repository, streetRepository)

    @Test
    fun `recalculateVisitSegments should handle interleaved streets correctly`() {
        val houses = listOf(
            House(id = 1, streetName = "STREET A", listOrder = 10),
            House(id = 2, streetName = "STREET A", listOrder = 20),
            House(id = 3, streetName = "STREET B", listOrder = 30),
            House(id = 4, streetName = "STREET A", listOrder = 40)
        )

        val result = useCase.recalculateVisitSegments(houses)

        assertEquals(0, result[0].visitSegment) // House 1 (Street A)
        assertEquals(0, result[1].visitSegment) // House 2 (Street A)
        assertEquals(1, result[2].visitSegment) // House 3 (Street B)
        assertEquals(2, result[3].visitSegment) // House 4 (Street A - new segment)
    }

    @Test
    fun `recalculateVisitSegments should be stable after reordering`() {
        // Original: A, B, A
        val houses = listOf(
            House(id = 1, streetName = "STREET A", listOrder = 10),
            House(id = 3, streetName = "STREET B", listOrder = 20),
            House(id = 4, streetName = "STREET A", listOrder = 30)
        )
        
        val result1 = useCase.recalculateVisitSegments(houses)
        assertEquals(0, result1[0].visitSegment)
        assertEquals(1, result1[1].visitSegment)
        assertEquals(2, result1[2].visitSegment)

        // After reorder: A, A, B
        val reordered = listOf(
            House(id = 1, streetName = "STREET A", listOrder = 10),
            House(id = 4, streetName = "STREET A", listOrder = 20),
            House(id = 3, streetName = "STREET B", listOrder = 30)
        )

        val result2 = useCase.recalculateVisitSegments(reordered)
        assertEquals(0, result2[0].visitSegment)
        assertEquals(0, result2[1].visitSegment) // Joined segment 0
        assertEquals(1, result2[2].visitSegment) // Segment 1
    }
}
