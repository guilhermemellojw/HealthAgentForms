package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import org.junit.Assert.assertEquals
import org.junit.Test

class PredictionReproductionTest {

    private val useCase = HouseManagementUseCase(mockk(), mockk())

    @Test
    fun reproduceUserCase() {
        val date = "02-04-2026"
        val block = "4"
        val street = "Rua João Eugênio Erthal"
        
        val houses = listOf(
            House(id = 1, data = date, blockNumber = block, streetName = street, number = "67", sequence = 4, listOrder = 1),
            House(id = 2, data = date, blockNumber = block, streetName = street, number = "67", sequence = 5, listOrder = 2),
            House(id = 3, data = date, blockNumber = block, streetName = street, number = "57", sequence = 0, listOrder = 3),
            House(id = 4, data = date, blockNumber = block, streetName = street, number = "57", sequence = 1, listOrder = 4)
        )
        
        val prediction = useCase.predictNextHouseValues(houses, date, block, street)
        
        println("Predicted Number: ${prediction.number}")
        println("Predicted Sequence: ${prediction.sequence}")
        
        // According to current logic:
        // last = 57(1), preLast = 57(0)
        // lastNum == preLastNum (57 == 57) -> Number = 57
        // nextSequence = 1 + (1-0) = 2
        
        assertEquals("57", prediction.number)
        assertEquals(2, prediction.sequence)
    }

    private fun mockk(): com.antigravity.healthagent.data.repository.HouseRepository {
        return io.mockk.mockk()
    }
}
