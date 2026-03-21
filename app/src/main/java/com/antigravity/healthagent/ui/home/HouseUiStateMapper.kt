package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.utils.formatStreetName

object HouseUiStateMapper {
    fun map(house: House, houseValidationUseCase: HouseValidationUseCase): HouseUiState {
        val invalidFields = houseValidationUseCase.getInvalidFields(house, strict = true).toSet()
        val formattedStreet = house.streetName.formatStreetName().ifBlank { "Sem Logradouro" }
        
        val treatmentParts = mutableListOf<String>()
        if (house.a1 > 0) treatmentParts.add("A1: ${house.a1}")
        if (house.a2 > 0) treatmentParts.add("A2: ${house.a2}")
        if (house.b > 0) treatmentParts.add("B: ${house.b}")
        if (house.c > 0) treatmentParts.add("C: ${house.c}")
        if (house.d1 > 0) treatmentParts.add("D1: ${house.d1}")
        if (house.d2 > 0) treatmentParts.add("D2: ${house.d2}")
        if (house.e > 0) treatmentParts.add("E: ${house.e}")
        if (house.eliminados > 0) treatmentParts.add("Elim: ${house.eliminados}")
        if (house.larvicida > 0.0) treatmentParts.add("Larv: ${house.larvicida}g")
        
        return HouseUiState(
            house = house,
            invalidFields = invalidFields,
            highlightErrors = invalidFields.isNotEmpty(),
            isTreated = treatmentParts.isNotEmpty() || house.comFoco,
            blockDisplay = if (house.blockSequence.isNotBlank()) "${house.blockNumber} / ${house.blockSequence}" else house.blockNumber,
            formattedStreet = formattedStreet,
            treatmentShortSummary = treatmentParts.joinToString(" | ")
        )
    }
}
