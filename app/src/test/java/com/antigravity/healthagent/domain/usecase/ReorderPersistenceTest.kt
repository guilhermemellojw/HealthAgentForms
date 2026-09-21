package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.model.VisitAddress
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReorderPersistenceTest {

    private lateinit var clashDetector: ClashDetector
    private lateinit var validationUseCase: HouseValidationUseCase

    private fun house(
        id: Int,
        listOrder: Long = 0,
        visitSegment: Int = 0,
        streetName: String = "RUA A",
        number: String = "10",
        sequence: Int = 0,
        complement: Int = 0
    ) = House(
        id = id,
        data = "25-05-2026",
        agentName = "GUILHERME",
        agentUid = "uid_1",
        address = VisitAddress(
            blockNumber = "01",
            blockSequence = "",
            streetName = streetName,
            number = number,
            sequence = sequence,
            complement = complement,
            bairro = "CENTRO"
        ),
        propertyType = PropertyType.R,
        situation = Situation.NONE,
        treatment = TreatmentData(),
        listOrder = listOrder,
        visitSegment = visitSegment
    )

    @Before
    fun setup() {
        clashDetector = ClashDetector()
        validationUseCase = HouseValidationUseCase()
    }

    @Test
    fun `reordered houses maintain distinct identities when sorted by listOrder`() {
        val h1 = house(id = 1, listOrder = 0, number = "10")
        val h2 = house(id = 2, listOrder = 1, number = "20")
        val h3 = house(id = 3, listOrder = 2, number = "30")

        val sorted = listOf(h3, h1, h2).sortedBy { it.listOrder }

        assertEquals(1, sorted[0].id)
        assertEquals(2, sorted[1].id)
        assertEquals(3, sorted[2].id)
    }

    @Test
    fun `reorder does not introduce address clashes`() {
        val h1 = house(id = 1, listOrder = 0, number = "10", sequence = 0)
        val h2 = house(id = 2, listOrder = 1, number = "20", sequence = 0)
        val h3 = house(id = 3, listOrder = 2, number = "30", sequence = 0)

        val allHouses = listOf(h1, h2, h3)

        allHouses.forEach { candidate ->
            val others = allHouses.filter { it.id != candidate.id }
            val clash = clashDetector.findClash(candidate, others)
            assertNull("House ${candidate.id} should not clash with others after reorder", clash)
        }
    }

    @Test
    fun `validation passes for reordered houses with distinct addresses`() {
        val houses = listOf(
            house(id = 1, listOrder = 0, number = "10"),
            house(id = 2, listOrder = 1, number = "20"),
            house(id = 3, listOrder = 2, number = "30")
        )

        val result = validationUseCase.validateCurrentDay("25-05-2026", houses)
        assertTrue("Reordered distinct houses should pass validation", result.isValid)
    }

    @Test
    fun `swapping listOrder does not affect validation result`() {
        val h1 = house(id = 1, listOrder = 10, number = "10")
        val h2 = house(id = 2, listOrder = 20, number = "20")

        val resultBefore = validationUseCase.validateCurrentDay("25-05-2026", listOf(h1, h2))

        val h1Swapped = h1.copy(listOrder = 20)
        val h2Swapped = h2.copy(listOrder = 10)
        val resultAfter = validationUseCase.validateCurrentDay("25-05-2026", listOf(h1Swapped, h2Swapped))

        assertEquals("Validation should be independent of listOrder", resultBefore.isValid, resultAfter.isValid)
    }

    @Test
    fun `autoIncrement preserves listOrder on original house`() {
        val original = house(id = 1, listOrder = 5, number = "10", complement = 0)
        val existing = listOf(house(id = 2, number = "10", complement = 0))

        val result = clashDetector.autoIncrementToAvoidClash(original, existing)

        assertEquals("listOrder should be preserved after autoIncrement", 5L, result.listOrder)
    }

    @Test
    fun `same address with different sequences are not duplicates`() {
        val h1 = house(id = 1, number = "10", sequence = 0, complement = 0)
        val h2 = house(id = 2, number = "10", sequence = 1, complement = 0)

        val result = validationUseCase.validateCurrentDay("25-05-2026", listOf(h1, h2))
        assertTrue("Different sequences should not be duplicates", result.isValid)
    }
}
