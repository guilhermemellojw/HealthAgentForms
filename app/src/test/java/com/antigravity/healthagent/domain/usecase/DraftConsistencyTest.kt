package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.DailyContext
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.model.VisitAddress
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.normalize
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DraftConsistencyTest {

    private lateinit var saveHouseUseCase: SaveHouseUseCase
    private lateinit var clashDetector: ClashDetector
    private lateinit var validationUseCase: HouseValidationUseCase

    private fun baseHouse(
        id: Int = 0,
        streetName: String = "RUA TESTE",
        number: String = "100",
        sequence: Int = 0,
        complement: Int = 0,
        blockNumber: String = "001",
        bairro: String = "CENTRO",
        visitSegment: Int = 0,
        lastUpdated: Long = 1000L
    ) = House(
        id = id,
        data = "25-05-2026",
        agentName = "GUILHERME",
        agentUid = "uid_1",
        address = VisitAddress(
            blockNumber = blockNumber,
            blockSequence = "",
            streetName = streetName,
            number = number,
            sequence = sequence,
            complement = complement,
            bairro = bairro
        ),
        propertyType = PropertyType.R,
        situation = Situation.NONE,
        treatment = TreatmentData(),
        visitSegment = visitSegment,
        lastUpdated = lastUpdated
    )

    @Before
    fun setup() {
        clashDetector = ClashDetector()
        validationUseCase = HouseValidationUseCase()
    }

    @Test
    fun `sanitize produces normalized streetName for draft comparison`() {
        val raw = baseHouse(streetName = "  rua das flores  ")
        val sanitized = raw.copy(
            address = raw.address.copy(
                streetName = raw.address.streetName.trim().formatStreetName()
            )
        )

        assertEquals("Rua das Flores", sanitized.address.streetName)
        assertEquals(sanitized.address.streetName.formatStreetName(), sanitized.address.streetName)
    }

    @Test
    fun `prune comparison formatStreetName is consistent for Title Case and UPPERCASE`() {
        val titleCase = "Rua Das Flores"
        val uppercase = "RUA DAS FLORES"

        assertEquals(
            "formatStreetName should normalize both to same output",
            titleCase.formatStreetName(),
            uppercase.formatStreetName()
        )
    }

    @Test
    fun `prune comparison normalize is consistent for mixed case strings`() {
        val mixed = "centro"
        val upper = "CENTRO"

        assertEquals(
            "normalize should produce same output regardless of input case",
            mixed.normalize(),
            upper.normalize()
        )
    }

    @Test
    fun `sanitized draft matches DB house after formatStreetName`() {
        val dbHouse = baseHouse(streetName = "Rua das Flores", number = "123A")
        val draftRaw = baseHouse(streetName = "rua das flores", number = "123a")

        val draftSanitized = draftRaw.copy(
            address = draftRaw.address.copy(
                streetName = draftRaw.address.streetName.trim().formatStreetName(),
                number = draftRaw.address.number.trim().uppercase()
            )
        )

        assertEquals(dbHouse.address.streetName, draftSanitized.address.streetName)
        assertEquals(dbHouse.address.number, draftSanitized.address.number)
    }

    @Test
    fun `clash detection is consistent for case-insensitive addresses`() {
        val h1 = baseHouse(id = 1, streetName = "Rua Das Flores", number = "123")
        val h2 = baseHouse(id = 2, streetName = "rua das flores", number = "123")

        assertNotNull("Case-insensitive clash should be detected", clashDetector.findClash(h2, listOf(h1)))
    }

    @Test
    fun `validation does not flag different complement as duplicate`() {
        val h1 = baseHouse(id = 1, number = "10", complement = 0)
        val h2 = baseHouse(id = 2, number = "10", complement = 1)

        val result = validationUseCase.validateCurrentDay("25-05-2026", listOf(h1, h2))
        assertTrue("Different complement should not be duplicate", result.isValid)
    }

    @Test
    fun `validation flags exact same address as duplicate regardless of visitSegment`() {
        val h1 = baseHouse(id = 1, number = "10", visitSegment = 0)
        val h2 = baseHouse(id = 2, number = "10", visitSegment = 3)

        val result = validationUseCase.validateCurrentDay("25-05-2026", listOf(h1, h2))
        assertFalse("Same address with different segments is still a duplicate", result.isValid)
    }
}
