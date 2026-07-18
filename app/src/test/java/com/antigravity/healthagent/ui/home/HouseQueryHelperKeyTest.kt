package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.VisitAddress
import org.junit.Assert.*
import org.junit.Test

class HouseQueryHelperKeyTest {

    private fun house(
        streetName: String = "RUA A",
        number: String = "10",
        sequence: Int = 0,
        complement: Int = 0,
        bairro: String = "CENTRO",
        blockNumber: String = "01",
        blockSequence: String = "",
        visitSegment: Int = 0
    ) = House(
        address = VisitAddress(
            blockNumber = blockNumber,
            blockSequence = blockSequence,
            streetName = streetName,
            number = number,
            sequence = sequence,
            complement = complement,
            bairro = bairro
        ),
        visitSegment = visitSegment
    )

    @Test
    fun `generateHouseKey does not include visitSegment`() {
        val h1 = house(streetName = "RUA A", number = "10", visitSegment = 0)
        val h2 = house(streetName = "RUA A", number = "10", visitSegment = 5)

        val key1 = HouseQueryHelper.generateHouseKey(h1)
        val key2 = HouseQueryHelper.generateHouseKey(h2)

        assertEquals("Keys should be identical regardless of visitSegment", key1, key2)
    }

    @Test
    fun `generateHouseKey produces consistent results`() {
        val h = house(streetName = "RUA DAS FLORES", number = "123", bairro = "CENTRO", complement = 1)

        val key1 = HouseQueryHelper.generateHouseKey(h)
        val key2 = HouseQueryHelper.generateHouseKey(h)

        assertEquals(key1, key2)
    }

    @Test
    fun `generateHouseKey differentiates by address number`() {
        val h1 = house(number = "10")
        val h2 = house(number = "20")

        assertNotEquals(HouseQueryHelper.generateHouseKey(h1), HouseQueryHelper.generateHouseKey(h2))
    }

    @Test
    fun `generateHouseKey differentiates by street name`() {
        val h1 = house(streetName = "RUA A")
        val h2 = house(streetName = "RUA B")

        assertNotEquals(HouseQueryHelper.generateHouseKey(h1), HouseQueryHelper.generateHouseKey(h2))
    }

    @Test
    fun `generateHouseKey differentiates by sequence`() {
        val h1 = house(sequence = 0)
        val h2 = house(sequence = 1)

        assertNotEquals(HouseQueryHelper.generateHouseKey(h1), HouseQueryHelper.generateHouseKey(h2))
    }

    @Test
    fun `generateHouseKey differentiates by complement`() {
        val h1 = house(complement = 0)
        val h2 = house(complement = 1)

        assertNotEquals(HouseQueryHelper.generateHouseKey(h1), HouseQueryHelper.generateHouseKey(h2))
    }

    @Test
    fun `generateHouseKey uses formatStreetName for street field`() {
        val h = house(streetName = "RUA DAS FLORES")
        val key = HouseQueryHelper.generateHouseKey(h)

        assertTrue("Key should contain formatted street name", key.contains("Rua das Flores".uppercase()))
    }
}
