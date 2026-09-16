package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.VisitAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MapDayIncrementalTest {

    private val myUid = "agent_1"

    private fun house(id: Int, number: String, ts: Long = id.toLong()): House = House(
        id = id,
        data = "25-05-2026",
        agentUid = myUid,
        agentName = "AGENTE",
        address = VisitAddress(
            bairro = "CENTRO",
            blockNumber = "001",
            blockSequence = "A",
            streetName = "RUA X",
            number = number,
            sequence = 1,
            complement = 0
        ),
        propertyType = PropertyType.R,
        situation = Situation.NONE,
        visitSegment = 1,
        listOrder = id.toLong(),
        lastUpdated = ts
    )

    private val houseKey: (House) -> String = { it.address.number }

    private fun countingMapper(invocations: MutableList<House>): (House, Boolean, Boolean, Boolean, Boolean) -> HouseUiState =
        { h, isDuplicate, _, _, _ ->
            invocations.add(h)
            HouseUiState(
                house = h,
                invalidFields = emptySet(),
                highlightErrors = isDuplicate,
                isTreated = false,
                blockDisplay = "",
                formattedStreet = "",
                treatmentShortSummary = ""
            )
        }

    private fun manyHouses(count: Int): List<House> = (1..count).map { house(it, it.toString()) }

    @Test
    fun `first pass maps every house in order`() {
        val invocations = mutableListOf<House>()
        val input = manyHouses(200)

        val result = mapDayIncremental(
            houses = input,
            previous = emptyMap(),
            generateHouseKey = houseKey,
            recentlyEditedHouseIds = emptyMap(),
            highlightedId = null,
            myUid = myUid,
            mapper = countingMapper(invocations)
        )

        assertEquals(200, invocations.size)
        assertEquals(200, result.states.size)
        assertEquals(input.map { it.id }, result.states.map { it.house.id })
    }

    @Test
    fun `editing one house of two hundred remaps only that house`() {
        val invocations = mutableListOf<House>()
        val input = manyHouses(200)
        val first = mapDayIncremental(
            houses = input,
            previous = emptyMap(),
            generateHouseKey = houseKey,
            recentlyEditedHouseIds = emptyMap(),
            highlightedId = null,
            myUid = myUid,
            mapper = countingMapper(invocations)
        )
        invocations.clear()

        val edited = input.toMutableList()
        edited[41] = house(42, "42", ts = 999L)

        val second = mapDayIncremental(
            houses = edited,
            previous = first.cache,
            generateHouseKey = houseKey,
            recentlyEditedHouseIds = emptyMap(),
            highlightedId = null,
            myUid = myUid,
            mapper = countingMapper(invocations)
        )

        assertEquals(1, invocations.size)
        assertEquals(42, invocations[0].id)
        assertSame("Unchanged card must be reused", first.states[0], second.states[0])
        assertSame("Unchanged card must be reused", first.states[100], second.states[100])
        assertEquals(200, second.states.size)
    }

    @Test
    fun `unchanged emission maps nothing`() {
        val invocations = mutableListOf<House>()
        val input = manyHouses(50)
        val first = mapDayIncremental(
            houses = input,
            previous = emptyMap(),
            generateHouseKey = houseKey,
            recentlyEditedHouseIds = emptyMap(),
            highlightedId = null,
            myUid = myUid,
            mapper = countingMapper(invocations)
        )
        invocations.clear()

        mapDayIncremental(
            houses = input,
            previous = first.cache,
            generateHouseKey = houseKey,
            recentlyEditedHouseIds = emptyMap(),
            highlightedId = null,
            myUid = myUid,
            mapper = countingMapper(invocations)
        )

        assertEquals(0, invocations.size)
    }

    @Test
    fun `recently edited flag remaps only that card`() {
        val invocations = mutableListOf<House>()
        val input = manyHouses(20)
        val first = mapDayIncremental(
            houses = input,
            previous = emptyMap(),
            generateHouseKey = houseKey,
            recentlyEditedHouseIds = emptyMap(),
            highlightedId = null,
            myUid = myUid,
            mapper = countingMapper(invocations)
        )
        invocations.clear()

        mapDayIncremental(
            houses = input,
            previous = first.cache,
            generateHouseKey = houseKey,
            recentlyEditedHouseIds = mapOf(7 to 123L),
            highlightedId = null,
            myUid = myUid,
            mapper = countingMapper(invocations)
        )

        assertEquals(1, invocations.size)
        assertEquals(7, invocations[0].id)
    }

    @Test
    fun `duplicate resolution remaps only affected cards`() {
        val invocations = mutableListOf<House>()
        val withDuplicate = listOf(house(1, "10"), house(2, "10"), house(3, "11"))
        val first = mapDayIncremental(
            houses = withDuplicate,
            previous = emptyMap(),
            generateHouseKey = houseKey,
            recentlyEditedHouseIds = emptyMap(),
            highlightedId = null,
            myUid = myUid,
            mapper = countingMapper(invocations)
        )

        assertEquals("Duplicate card must be flagged", true, first.states[0].highlightErrors)
        assertEquals("Duplicate card must be flagged", true, first.states[1].highlightErrors)

        invocations.clear()
        val duplicateResolved = listOf(withDuplicate[0], withDuplicate[2])
        val second = mapDayIncremental(
            houses = duplicateResolved,
            previous = first.cache,
            generateHouseKey = houseKey,
            recentlyEditedHouseIds = emptyMap(),
            highlightedId = null,
            myUid = myUid,
            mapper = countingMapper(invocations)
        )

        // Removed house is not mapped; survivor flag flip (id=1 stops being duplicate) remaps it
        assertEquals(1, invocations.size)
        assertEquals(1, invocations[0].id)
        assertEquals("Survivor must no longer be flagged", false, second.states[0].highlightErrors)
    }
}