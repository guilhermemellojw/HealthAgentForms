package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.model.VisitAddress
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ClashDetectorTest {

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
    ): House = House(
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
    fun `findClash returns null when no clash exists`() {
        val existing = listOf(house(id = 1, number = "10"), house(id = 2, number = "20"))
        val candidate = house(id = 0, number = "30")

        assertNull(detector.findClash(candidate, existing))
    }

    @Test
    fun `findClash detects exact duplicate address`() {
        val existing = listOf(house(id = 1, number = "10"), house(id = 2, number = "20"))
        val candidate = house(id = 3, number = "10")

        val clash = detector.findClash(candidate, existing)
        assertNotNull(clash)
        assertEquals(1, clash!!.id)
    }

    @Test
    fun `findClash ignores same ID`() {
        val existing = listOf(house(id = 1, number = "10"))
        val candidate = house(id = 1, number = "10") // Same ID, no clash

        assertNull(detector.findClash(candidate, existing))
    }

    @Test
    fun `findClash is case insensitive for street names`() {
        val existing = listOf(house(id = 1, streetName = "RUA BONITA"))
        val candidate = house(id = 2, streetName = "rua bonita")

        assertNotNull(detector.findClash(candidate, existing))
    }

    @Test
    fun `findClash differentiates by agent UID`() {
        val existing = listOf(house(id = 1, agentUid = "uid1"))
        val candidate = house(id = 2, agentUid = "uid2")

        assertNull(detector.findClash(candidate, existing))
    }

    @Test
    fun `findClash differentiates by date`() {
        val existing = listOf(house(id = 1, date = "25-05-2026"))
        val candidate = house(id = 2, date = "26-05-2026")

        assertNull(detector.findClash(candidate, existing))
    }

    @Test
    fun `findClash differentiates by visit segment when included`() {
        val existing = listOf(house(id = 1, visitSegment = 0))
        val candidate = house(id = 2, visitSegment = 1)

        assertNull(detector.findClash(candidate, existing, includeVisitSegment = true))
    }

    @Test
    fun `findClash ignores visit segment when excluded`() {
        val existing = listOf(house(id = 1, visitSegment = 0))
        val candidate = house(id = 2, visitSegment = 1)

        assertNotNull(detector.findClash(candidate, existing, includeVisitSegment = false))
    }

    @Test
    fun `autoIncrement increments complement when number is present`() {
        val existing = listOf(
            house(id = 1, number = "10", complement = 0),
            house(id = 2, number = "10", complement = 1)
        )
        val candidate = house(id = 0, number = "10", complement = 0)

        val result = detector.autoIncrementToAvoidClash(candidate, existing)
        assertEquals(2, result.address.complement)
    }

    @Test
    fun `autoIncrement increments sequence when number is blank`() {
        val existing = listOf(
            house(id = 1, number = "", sequence = 0),
            house(id = 2, number = "", sequence = 1)
        )
        val candidate = house(id = 0, number = "", sequence = 0)

        val result = detector.autoIncrementToAvoidClash(candidate, existing)
        assertEquals(2, result.address.sequence)
    }

    @Test
    fun `autoIncrement returns same house when no clash`() {
        val existing = listOf(house(id = 1, number = "10"))
        val candidate = house(id = 0, number = "20")

        val result = detector.autoIncrementToAvoidClash(candidate, existing)
        assertEquals(candidate.address.sequence, result.address.sequence)
        assertEquals(candidate.address.complement, result.address.complement)
    }
}
