package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.model.VisitAddress
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DuplicateDetectionAfterEditTest {

    private lateinit var validationUseCase: HouseValidationUseCase
    private lateinit var clashDetector: ClashDetector

    private fun house(
        id: Int,
        streetName: String = "RUA A",
        number: String = "10",
        sequence: Int = 0,
        complement: Int = 0,
        visitSegment: Int = 0,
        date: String = "25-05-2026",
        agentUid: String = "uid1",
        agentName: String = "AGENTE",
        bairro: String = "CENTRO",
        blockNumber: String = "01"
    ) = House(
        id = id,
        address = VisitAddress(
            blockNumber = blockNumber,
            blockSequence = "",
            streetName = streetName,
            number = number,
            sequence = sequence,
            complement = complement,
            bairro = bairro
        ),
        data = date,
        agentUid = agentUid,
        agentName = agentName,
        propertyType = PropertyType.R,
        situation = Situation.NONE,
        treatment = TreatmentData(),
        visitSegment = visitSegment
    )

    @Before
    fun setup() {
        validationUseCase = HouseValidationUseCase()
        clashDetector = ClashDetector()
    }

    @Test
    fun `same address different segments are always detected as duplicates`() {
        val house1 = house(id = 1, streetName = "RUA A", number = "10", visitSegment = 0)
        val house2 = house(id = 2, streetName = "RUA A", number = "10", visitSegment = 1)

        val result = validationUseCase.validateCurrentDay("25-05-2026", listOf(house1, house2))
        assertFalse("Same address regardless of segment is a duplicate", result.isValid)
    }

    @Test
    fun `true duplicates still detected with same segment`() {
        val house1 = house(id = 1, streetName = "RUA A", number = "10", visitSegment = 0)
        val house2 = house(id = 2, streetName = "RUA A", number = "10", visitSegment = 0)

        val result = validationUseCase.validateCurrentDay("25-05-2026", listOf(house1, house2))
        assertFalse("Same address same segment is a true duplicate", result.isValid)
    }

    @Test
    fun `editing number does not create false duplicate with unchanged house`() {
        val original = house(id = 1, streetName = "RUA A", number = "10")
        val edited = house(id = 1, streetName = "RUA A", number = "20")

        val houses = listOf(original, edited)
        val result = validationUseCase.validateCurrentDay("25-05-2026", houses)
        assertTrue("Same house with different numbers should not be a duplicate", result.isValid)
    }

    @Test
    fun `clash detector finds clash between two different IDs with same address`() {
        val existing = listOf(house(id = 1, streetName = "RUA A", number = "10"))
        val candidate = house(id = 2, streetName = "RUA A", number = "10")

        assertNotNull("Different IDs with same address should clash", clashDetector.findClash(candidate, existing))
    }

    @Test
    fun `clash detector does not flag same ID as clash`() {
        val existing = listOf(house(id = 1, streetName = "RUA A", number = "10"))
        val candidate = house(id = 1, streetName = "RUA A", number = "10")

        assertNull("Same ID should not be flagged as a clash", clashDetector.findClash(candidate, existing))
    }

    @Test
    fun `editing street name changes address identity`() {
        val house1 = house(id = 1, streetName = "RUA A", number = "10")
        val house2 = house(id = 2, streetName = "RUA B", number = "10")

        val result = validationUseCase.validateCurrentDay("25-05-2026", listOf(house1, house2))
        assertTrue("Different street names should not be duplicates", result.isValid)
    }

    @Test
    fun `clash detector ignores visitSegment by default after edit`() {
        val existing = listOf(house(id = 1, streetName = "RUA A", number = "10", visitSegment = 0))
        val candidate = house(id = 2, streetName = "RUA A", number = "10", visitSegment = 5)

        assertNotNull("Clash detected even with different segments (default ignores segment)", clashDetector.findClash(candidate, existing))
    }
}
