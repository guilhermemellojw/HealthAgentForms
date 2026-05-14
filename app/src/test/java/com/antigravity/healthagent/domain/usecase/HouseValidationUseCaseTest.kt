package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.model.DailyContext
import org.junit.Assert.*
import org.junit.Test

class HouseValidationUseCaseTest {

    private val useCase = HouseValidationUseCase()
    private val date = "13-05-2026"

    @Test
    fun `validate - house with missing essential fields is invalid`() {
        val house = House(
            id = 1,
            data = date,
            address = com.antigravity.healthagent.domain.model.VisitAddress(
                bairro = "", // Missing
                streetName = "Rua Teste",
                blockNumber = "1"
            )
        )
        
        val result = useCase.getInvalidFields(house)
        assertTrue(result.contains("bairro"))
        assertFalse(useCase.isHouseValid(house))
    }

    @Test
    fun `validate - treatment without deposits is invalid`() {
        val house = House(
            id = 1,
            data = date,
            address = com.antigravity.healthagent.domain.model.VisitAddress(
                bairro = "Centro",
                streetName = "Rua Teste",
                blockNumber = "1",
                number = "123"
            ),
            situation = Situation.NONE,
            treatment = TreatmentData(larvicida = 10.0, a1 = 0) // No deposits
        )
        
        val result = useCase.getInvalidFields(house)
        assertTrue(result.contains("larvicide_inspection"))
    }

    @Test
    fun `validate - deposits without larvicide is invalid`() {
        val house = House(
            id = 1,
            data = date,
            address = com.antigravity.healthagent.domain.model.VisitAddress(
                bairro = "Centro",
                streetName = "Rua Teste",
                blockNumber = "1",
                number = "123"
            ),
            situation = Situation.NONE,
            treatment = TreatmentData(a1 = 1, larvicida = 0.0)
        )
        
        val result = useCase.getInvalidFields(house)
        assertTrue(result.contains("treatment_without_larvicide"))
    }

    @Test
    fun `validate - duplicate addresses on same day are flagged`() {
        val houses = listOf(
            House(id = 1, data = date, address = com.antigravity.healthagent.domain.model.VisitAddress(bairro = "Centro", blockNumber = "1", streetName = "Rua A", number = "100")),
            House(id = 2, data = date, address = com.antigravity.healthagent.domain.model.VisitAddress(bairro = "Centro", blockNumber = "1", streetName = "Rua A", number = "100"))
        )
        
        val result = useCase.validateCurrentDay(date, houses)
        assertFalse(result.isValid)
        assertEquals(2, result.errorHouseIds.size)
        assertTrue(result.errorDetails.any { it.isDuplicate })
    }
}
