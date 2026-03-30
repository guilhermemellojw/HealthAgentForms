package com.antigravity.healthagent.data.backup

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HouseIdentityTest {

    @Test
    fun testNaturalKeyStabilityAcrossTime() {
        val house1 = House(
            id = 1,
            blockNumber = "10",
            streetName = "Rua Principal",
            number = "100",
            data = "16-03-2026",
            agentName = "GUILHERME",
            createdAt = 1000L,
            visitSegment = 0
        )
        
        val house2 = house1.copy(createdAt = 2000L) // Different timestamp
        
        assertEquals(
            "Natural keys must be identical even if createdAt changes",
            house1.generateNaturalKey(),
            house2.generateNaturalKey()
        )
    }

    @Test
    fun testStreetSegmentsForReturnTrips() {
        val houses = listOf(
            House(agentName = "A", data = "16-03-2026", streetName = "Rua 1", number = "1", listOrder = 1),
            House(agentName = "A", data = "16-03-2026", streetName = "Rua 1", number = "2", listOrder = 2),
            House(agentName = "A", data = "16-03-2026", streetName = "Rua 2", number = "10", listOrder = 3),
            House(agentName = "A", data = "16-03-2026", streetName = "Rua 1", number = "3", listOrder = 4) // Return to Rua 1
        )
        
        val normalized = recalculateVisitSegments(houses)
        
        assertEquals(0, normalized[0].visitSegment)
        assertEquals(0, normalized[1].visitSegment)
        assertEquals(1, normalized[2].visitSegment)
        assertEquals(2, normalized[3].visitSegment) // Segment must increment on return trip
        
        assertNotEquals(
            "Natural key for return trip must be different",
            normalized[0].generateNaturalKey(),
            normalized[3].generateNaturalKey()
        )
    }

    @Test
    fun testDeduplicationWithinSegment() {
        val house1 = House(agentName = "A", data = "16-03-2026", streetName = "Rua 1", number = "1", listOrder = 1)
        val house2 = House(agentName = "A", data = "16-03-2026", streetName = "Rua 1", number = "1", listOrder = 2) // Duplicate in same segment
        
        val normalized = recalculateVisitSegments(listOf(house1, house2))
        
        assertEquals(normalized[0].visitSegment, normalized[1].visitSegment)
        assertEquals(
            "Duplicates within the same segment must have identical natural keys",
            normalized[0].generateNaturalKey(),
            normalized[1].generateNaturalKey()
        )
    }

    private fun recalculateVisitSegments(houses: List<House>): List<House> {
        if (houses.isEmpty()) return emptyList()
        var currentSegment = 0
        var lastStreet = ""
        return houses.sortedBy { it.listOrder }.map { house ->
            val street = house.streetName.trim().uppercase()
            if (lastStreet.isNotEmpty() && street != lastStreet) {
                currentSegment++
            }
            lastStreet = street
            house.copy(visitSegment = currentSegment)
        }
    }
}
