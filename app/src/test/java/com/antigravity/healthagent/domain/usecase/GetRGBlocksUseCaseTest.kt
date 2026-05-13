package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import org.junit.Assert.assertEquals
import org.junit.Test

class GetRGBlocksUseCaseTest {

    private val useCase = GetRGBlocksUseCase()
    private val bairro = "CENTRO"

    @Test
    fun `invoke - should prioritize Date then listOrder`() {
        // Given:
        val h1 = House(id = 1, data = "01/05/2026", listOrder = 1, blockNumber = "1", blockSequence = "A", bairro = bairro, number = "1", createdAt = 100)
        val h2 = House(id = 2, data = "01/05/2026", listOrder = 2, blockNumber = "1", blockSequence = "A", bairro = bairro, number = "2", createdAt = 110)
        val h3 = House(id = 3, data = "02/05/2026", listOrder = 1, blockNumber = "1", blockSequence = "A", bairro = bairro, number = "3", createdAt = 200)
        val h4 = House(id = 4, data = "02/05/2026", listOrder = 2, blockNumber = "1", blockSequence = "A", bairro = bairro, number = "4", createdAt = 210)
        
        val houses = listOf(h4, h2, h1, h3) // Shuffled

        // When
        val result = useCase(houses, bairro, "2026")

        // Then
        assertEquals(1, result.size)
        val blockHouses = result.first().houses
        assertEquals(4, blockHouses.size)
        assertEquals(1, blockHouses[0].id) // Day 1, LO 1
        assertEquals(2, blockHouses[1].id) // Day 1, LO 2
        assertEquals(3, blockHouses[2].id) // Day 2, LO 1
        assertEquals(4, blockHouses[3].id) // Day 2, LO 2
    }

    @Test
    fun `invoke - should use id as tie-breaker for same LO on same day`() {
        // Given: Teamwork on the same day. 
        val h1 = House(id = 1, data = "01/05/2026", listOrder = 0, agentName = "A", bairro = bairro, blockNumber = "1", number = "1", createdAt = 2000)
        val h2 = House(id = 2, data = "01/05/2026", listOrder = 0, agentName = "B", bairro = bairro, blockNumber = "1", number = "2", createdAt = 1000)
        
        val houses = listOf(h2, h1)

        // When
        val result = useCase(houses, bairro, "2026")

        // Then
        assertEquals(1, result.size)
        val blockHouses = result.first().houses
        assertEquals(2, blockHouses.size)
        assertEquals(1, blockHouses[0].id) // ID 1 comes first even if createdAt 2000 is later
        assertEquals(2, blockHouses[1].id) // ID 2 comes second
    }

    @Test
    fun `invoke - should group teamwork houses by agent when LO is same`() {
        // Scenario: Agent A and Agent B working together on same day.
        // Even if they have same listOrder, we group by name (A first, then B).
        
        val h1A = House(id = 10, data = "01/05/2026", agentName = "AGENTE A", number = "1", blockNumber = "1", bairro = bairro, createdAt = 1000, listOrder = 1)
        val h1B = House(id = 11, data = "01/05/2026", agentName = "AGENTE B", number = "2", blockNumber = "1", bairro = bairro, createdAt = 1200, listOrder = 1)
        
        val h2A = House(id = 20, data = "01/05/2026", agentName = "AGENTE A", number = "3", blockNumber = "1", bairro = bairro, createdAt = 1100, listOrder = 2)
        val h2B = House(id = 21, data = "01/05/2026", agentName = "AGENTE B", number = "4", blockNumber = "1", bairro = bairro, createdAt = 1300, listOrder = 2)
 
        val houses = listOf(h2B, h1B, h2A, h1A) // Shuffled
 
        // When
        val result = useCase(houses, bairro, "2026")
 
        // Then
        val blockHouses = result.first().houses
        assertEquals(4, blockHouses.size)
        
        // Grouped by Agent A first
        assertEquals("AGENTE A", blockHouses[0].agentName)
        assertEquals(10, blockHouses[0].id)
        assertEquals("AGENTE A", blockHouses[1].agentName)
        assertEquals(20, blockHouses[1].id)
        
        // Then Agent B
        assertEquals("AGENTE B", blockHouses[2].agentName)
        assertEquals(11, blockHouses[2].id)
        assertEquals("AGENTE B", blockHouses[3].agentName)
        assertEquals(21, blockHouses[3].id)
    }

    @Test
    fun `invoke - should show all visits to a house in chronological log`() {
        // Scenario: 
        // Monday: Agent A visits House 10 (Closed).
        // Tuesday: Agent B visits House 10 again (Worked).
        
        val h10Monday = House(id = 10, data = "01/05/2026", agentUid = "A", number = "10", blockNumber = "1", bairro = bairro, situation = Situation.F, createdAt = 1000, listOrder = 0)
        val h10Tuesday = House(id = 100, data = "02/05/2026", agentUid = "B", number = "10", blockNumber = "1", bairro = bairro, situation = Situation.NONE, createdAt = 2000, listOrder = 0)
        
        val houses = listOf(h10Monday, h10Tuesday)

        // When
        val result = useCase(houses, bairro, "2026")

        // Then
        val blockHouses = result.first().houses
        assertEquals(2, blockHouses.size)
        
        // Both visits must appear in order
        assertEquals(10, blockHouses[0].id)
        assertEquals(Situation.F, blockHouses[0].situation)
        assertEquals("01/05/2026", blockHouses[0].data)
        
        assertEquals(100, blockHouses[1].id)
        assertEquals(Situation.NONE, blockHouses[1].situation)
        assertEquals("02/05/2026", blockHouses[1].data)
        
        // Block date must be the date of the LAST house
        assertEquals("02/05/2026", result.first().conclusionDate)
    }

    @Test
    fun `invoke - should implicitly conclude block if it is not the last one in percurso`() {
        // Given:
        // Agent does Block 1 -> Block 2
        val h1 = House(id = 1, data = "01/05/2026", blockNumber = "1", bairro = bairro, listOrder = 1)
        val h2 = House(id = 2, data = "01/05/2026", blockNumber = "2", bairro = bairro, listOrder = 2)
        
        val houses = listOf(h1, h2)

        // When
        val result = useCase(houses, bairro, "2026")

        // Then
        assertEquals(2, result.size)
        
        val block1 = result.find { it.blockNumber == "1" }!!
        val block2 = result.find { it.blockNumber == "2" }!!
        
        assertEquals(true, block1.isConcluded) // Should be concluded because it was followed by Block 2
        assertEquals(false, block2.isConcluded) // Should be open because it's the last one
    }

    @Test
    fun `invoke - should NOT conclude block if it is the last one even if worked on multiple days`() {
        // Given:
        // Day 1: Block 1
        // Day 2: Block 1 (Continuation)
        val h1 = House(id = 1, data = "01/05/2026", blockNumber = "1", bairro = bairro, listOrder = 1)
        val h2 = House(id = 2, data = "02/05/2026", blockNumber = "1", bairro = bairro, listOrder = 1)
        
        val houses = listOf(h1, h2)

        // When
        val result = useCase(houses, bairro, "2026")

        // Then
        assertEquals(1, result.size)
        assertEquals(false, result.first().isConcluded)
    }

    @Test
    fun `invoke - should implicitly conclude block if agent moved to another bairro`() {
        // Given:
        // Agent does Block 1 in "Bairro A"
        // THEN Agent does Block 2 in "Bairro B"
        val h1 = House(id = 1, data = "01/05/2026", blockNumber = "1", bairro = "BAIRRO A", listOrder = 1)
        val h2 = House(id = 2, data = "01/05/2026", blockNumber = "2", bairro = "BAIRRO B", listOrder = 2)
        
        val houses = listOf(h1, h2)

        // When
        // We look at "BAIRRO A"
        val result = useCase(houses, "BAIRRO A", "2026")

        // Then
        assertEquals(1, result.size)
        val block1 = result.find { it.blockNumber == "1" }!!
        
        // Block 1 should be concluded because it's not the LAST visit globally (H2 follows it)
        assertEquals(true, block1.isConcluded)
    }
}
