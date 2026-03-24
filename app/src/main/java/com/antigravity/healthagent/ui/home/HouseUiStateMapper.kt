package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.utils.formatStreetName

object HouseUiStateMapper {
    fun map(house: House, houseValidationUseCase: HouseValidationUseCase, isDuplicate: Boolean = false): HouseUiState {
        val invalidFields = houseValidationUseCase.getInvalidFields(house, strict = true).toSet()
        val errorLabels = mutableListOf<String>()
        if (isDuplicate) errorLabels.add("DUPLICADO")
        
        invalidFields.forEach { key ->
            when(key) {
                "number" -> errorLabels.add("SEM Nº")
                "propertyType" -> errorLabels.add("SEM TIPO")
                "situation" -> errorLabels.add("SEM SITUAÇÃO")
                "agentName" -> errorLabels.add("SEM AGENTE")
                "bairro" -> errorLabels.add("SEM BAIRRO")
                "streetName" -> errorLabels.add("SEM RUA")
                "blockNumber" -> errorLabels.add("SEM QUART.")
                "situation_treatment" -> errorLabels.add("TRAT. INDEVIDO")
                "larvicide_inspection" -> errorLabels.add("LARV. SEM DEP.")
            }
        }

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
        
        // Resilience: Ensure house has a fallback agentName if missing during mapping
        val displayHouse = if (house.agentName.isBlank()) house.copy(agentName = "NÃO ATRIBUÍDO") else house
        
        return HouseUiState(
            house = displayHouse,
            invalidFields = invalidFields,
            highlightErrors = invalidFields.isNotEmpty() || isDuplicate,
            isTreated = treatmentParts.isNotEmpty() || house.comFoco,
            blockDisplay = if (house.blockSequence.isNotBlank()) "${house.blockNumber} / ${house.blockSequence}" else house.blockNumber,
            formattedStreet = formattedStreet,
            treatmentShortSummary = treatmentParts.joinToString(" | "),
            observation = displayHouse.observation,
            isDuplicate = isDuplicate,
            errorLabels = errorLabels
        )
    }
}
