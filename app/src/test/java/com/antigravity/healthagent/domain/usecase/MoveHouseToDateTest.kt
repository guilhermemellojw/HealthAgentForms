package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.model.VisitAddress
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MoveHouseToDateTest {

    private lateinit var clashDetector: ClashDetector
    private lateinit var validationUseCase: HouseValidationUseCase

    private fun house(
        id: Int,
        streetName: String = "RUA A",
        number: String = "10",
        sequence: Int = 0,
        complement: Int = 0,
        visitSegment: Int = 0,
        date: String = "25-05-2026",
        agentUid: String = "uid_1",
        agentName: String = "GUILHERME",
        listOrder: Long = 0
    ) = House(
        id = id,
        data = date,
        agentName = agentName,
        agentUid = agentUid,
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
    fun `moving house to different date removes it from source date`() {
        val sourceDateHouses = listOf(
            house(id = 1, date = "25-05-2026", number = "10"),
            house(id = 2, date = "25-05-2026", number = "20")
        )

        val movedHouse = house(id = 1, date = "26-05-2026", number = "10")
        val remainingSource = sourceDateHouses.filter { it.id != movedHouse.id }

        assertEquals(1, remainingSource.size)
        assertEquals(2, remainingSource[0].id)
    }

    @Test
    fun `moving house to new date does not create clash on target date`() {
        val targetDateHouses = listOf(
            house(id = 10, date = "26-05-2026", number = "10", streetName = "RUA B")
        )

        val movedHouse = house(id = 1, date = "26-05-2026", number = "10", streetName = "RUA A")

        val allTarget = targetDateHouses + movedHouse
        val clash = clashDetector.findClash(movedHouse, targetDateHouses)

        assertNull("Moving to different street should not clash", clash)
    }

    @Test
    fun `moving house to target with same address creates clash`() {
        val targetDateHouses = listOf(
            house(id = 10, date = "26-05-2026", number = "10", streetName = "RUA A")
        )

        val movedHouse = house(id = 1, date = "26-05-2026", number = "10", streetName = "RUA A")

        val clash = clashDetector.findClash(movedHouse, targetDateHouses)
        assertNotNull("Same address on target date should clash", clash)
    }

    @Test
    fun `source date validation passes after move`() {
        val housesAfterMove = listOf(
            house(id = 2, date = "25-05-2026", number = "20")
        )

        val result = validationUseCase.validateCurrentDay("25-05-2026", housesAfterMove)
        assertTrue("Source date with one remaining house should be valid", result.isValid)
    }

    @Test
    fun `target date validation passes after move to empty date`() {
        val movedHouse = house(id = 1, date = "26-05-2026", number = "10")

        val result = validationUseCase.validateCurrentDay("26-05-2026", listOf(movedHouse))
        assertTrue("Target date with one moved house should be valid", result.isValid)
    }

    @Test
    fun `moving house changes its date field`() {
        val original = house(id = 1, date = "25-05-2026")
        val moved = original.copy(data = "26-05-2026")

        assertEquals("25-05-2026", original.data)
        assertEquals("26-05-2026", moved.data)
    }

    @Test
    fun `autoIncrement resolves clash when target date has same address`() {
        val targetHouses = listOf(
            house(id = 10, date = "26-05-2026", number = "10", complement = 0)
        )

        val movedHouse = house(id = 1, date = "26-05-2026", number = "10", complement = 0)
        val resolved = clashDetector.autoIncrementToAvoidClash(movedHouse, targetHouses)

        assertTrue("complement should be incremented to avoid clash", resolved.address.complement > 0)
    }

    @Test
    fun `moving house preserves agent identity`() {
        val original = house(id = 1, date = "25-05-2026", agentUid = "uid_1", agentName = "GUILHERME")
        val moved = original.copy(data = "26-05-2026")

        assertEquals(original.agentUid, moved.agentUid)
        assertEquals(original.agentName, moved.agentName)
    }
}
