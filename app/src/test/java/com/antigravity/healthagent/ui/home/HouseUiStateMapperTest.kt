package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import org.junit.Assert.*
import org.junit.Test

class HouseUiStateMapperTest {

    private val houseValidationUseCase = HouseValidationUseCase()

    @Test
    fun `map - house with situation REC and no treatment - isTreated is false`() {
        val house = House(
            situation = Situation.REC,
            a1 = 0, a2 = 0, b = 0, c = 0, d1 = 0, d2 = 0, e = 0,
            eliminados = 0, larvicida = 0.0
        )
        
        val uiState = HouseUiStateMapper.map(house, houseValidationUseCase)
        
        assertFalse("isTreated should be false for REC house without actual treatment", uiState.isTreated)
        assertEquals("treatmentShortSummary should be empty", "", uiState.treatmentShortSummary)
    }

    @Test
    fun `map - house with treatment - isTreated is true and summary contains treatment info`() {
        val house = House(
            situation = Situation.NONE,
            a1 = 1,
            larvicida = 10.5
        )
        
        val uiState = HouseUiStateMapper.map(house, houseValidationUseCase)
        
        assertTrue("isTreated should be true when treatment exists", uiState.isTreated)
        assertTrue("Summary should contain A1: 1", uiState.treatmentShortSummary.contains("A1: 1"))
        assertTrue("Summary should contain Larv: 10.5g", uiState.treatmentShortSummary.contains("Larv: 10.5g"))
        assertFalse("Summary should NOT contain situation name", uiState.treatmentShortSummary.contains("NONE"))
    }

    @Test
    fun `map - house with situation F and no treatment - summary is empty`() {
        val house = House(
            situation = Situation.F,
            a1 = 0, eliminados = 0, larvicida = 0.0
        )
        
        val uiState = HouseUiStateMapper.map(house, houseValidationUseCase)
        
        assertFalse(uiState.isTreated)
        assertEquals("", uiState.treatmentShortSummary)
    }
    @Test
    fun `map - house with EMPTY situation - is healed to NONE in displayHouse`() {
        val house = House(situation = Situation.EMPTY)
        
        val uiState = HouseUiStateMapper.map(house, houseValidationUseCase)
        
        assertEquals("Situation should be healed to NONE in UI state", Situation.NONE, uiState.house.situation)
        // Note: enum code for both EMPTY and NONE is now "—" and description is "Aberto"
        assertEquals("—", uiState.house.situation.code)
    }
}
