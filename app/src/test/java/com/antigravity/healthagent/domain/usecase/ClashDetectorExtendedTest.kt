package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.model.VisitAddress
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ClashDetectorExtendedTest {

    private lateinit var detector: ClashDetector

    private fun house(
        id: Int = 0,
        blockNumber: String = "01",
        blockSequence: String = "",
        streetName: String = "RUA A",
        number: String = "10",
        sequence: Int = 0,
        complement: Int = 0,
        bairro: String = "CENTRO",
        date: String = "25-05-2026",
        agentUid: String = "uid1",
        agentName: String = "AGENTE",
        visitSegment: Int = 0
    ) = House(
        id = id,
        address = VisitAddress(
            blockNumber = blockNumber,
            blockSequence = blockSequence,
            streetName = streetName,
            number = number,
            sequence = sequence,
            complement = complement,
            bairro = bairro
        ),
        data = date,
        agentUid = agentUid,
        agentName = agentName,
        visitSegment = visitSegment
    )

    @Before
    fun setup() {
        detector = ClashDetector()
    }

    @Test
    fun `findClash default ignores visitSegment`() {
        val existing = listOf(house(id = 1, visitSegment = 0))
        val candidate = house(id = 2, visitSegment = 5)

        val clash = detector.findClash(candidate, existing)
        assertNotNull(clash)
    }

    @Test
    fun `findClash same address different segment matches by default`() {
        val existing = listOf(house(id = 1, visitSegment = 0))
        val candidate = house(id = 2, visitSegment = 3)

        assertNotNull("Should detect clash when segments differ (default ignores segment)", detector.findClash(candidate, existing))
    }

    @Test
    fun `findClash explicitly including visitSegment differentiates`() {
        val existing = listOf(house(id = 1, visitSegment = 0))
        val candidate = house(id = 2, visitSegment = 3)

        assertNull("Should NOT detect clash when segments differ and includeVisitSegment=true", detector.findClash(candidate, existing, includeVisitSegment = true))
    }

    @Test
    fun `autoIncrement default ignores visitSegment`() {
        val existing = listOf(house(id = 1, number = "10", complement = 0, visitSegment = 0))
        val candidate = house(id = 0, number = "10", complement = 0, visitSegment = 99)

        val result = detector.autoIncrementToAvoidClash(candidate, existing)
        assertEquals("Should increment even though visitSegment differs (default ignores segment)", 1, result.address.complement)
    }

    @Test
    fun `autoIncrement explicitly including visitSegment skips when segment differs`() {
        val existing = listOf(house(id = 1, number = "10", complement = 0, visitSegment = 0))
        val candidate = house(id = 0, number = "10", complement = 0, visitSegment = 99)

        val result = detector.autoIncrementToAvoidClash(candidate, existing, includeVisitSegment = true)
        assertEquals("Should NOT increment because different segment means no clash", 0, result.address.complement)
    }

    @Test
    fun `findClash case insensitive across all address fields`() {
        val existing = listOf(house(id = 1, streetName = "Rua Das Flores", number = "123A", bairro = "Centro"))
        val candidate = house(id = 2, streetName = "rua das flores", number = "123a", bairro = "centro")

        assertNotNull("Case differences should be ignored for clash detection", detector.findClash(candidate, existing))
    }
}
