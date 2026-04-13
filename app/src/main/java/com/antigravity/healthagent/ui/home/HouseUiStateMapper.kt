package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.utils.formatStreetName

object HouseUiStateMapper {
    fun map(
        house: House, 
        houseValidationUseCase: HouseValidationUseCase, 
        isDuplicate: Boolean = false,
        isRecentlyEdited: Boolean = false
    ): HouseUiState {
        val invalidFields = houseValidationUseCase.getInvalidFields(house, strict = true).toSet()
        val errorLabels = mutableListOf<String>()
        
        // Only show errors if NOT recently edited (Silent Window)
        if (!isRecentlyEdited) {
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
                    "treatment_without_larvicide" -> errorLabels.add("DEP. SEM LARV.")
                }
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
        
        // Resilience: Ensure house has a fallback agentName and healed situation if missing during mapping
        val displayHouse = house.copy(
            agentName = house.agentName.ifBlank { "NÃO ATRIBUÍDO" },
            situation = if (house.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) 
                com.antigravity.healthagent.data.local.model.Situation.NONE else house.situation
        )
        
        val idParts = mutableListOf<String>()
        if (house.number.isNotBlank()) idParts.add(house.number)
        if (house.sequence > 0) idParts.add("S${house.sequence}")
        if (house.complement > 0) idParts.add("C${house.complement}")
        val fullIdDisplay = idParts.joinToString("-").ifBlank { "S/N" }

        return HouseUiState(
            house = displayHouse,
            invalidFields = if (isRecentlyEdited) emptySet() else invalidFields,
            highlightErrors = !isRecentlyEdited && (invalidFields.isNotEmpty() || isDuplicate),
            isTreated = treatmentParts.isNotEmpty() || house.comFoco,
            blockDisplay = if (house.blockSequence.isNotBlank()) "${house.blockNumber} / ${house.blockSequence}" else house.blockNumber,
            formattedStreet = formattedStreet,
            treatmentShortSummary = treatmentParts.joinToString(" | "),
            observation = displayHouse.observation,
            isRecentlyEdited = isRecentlyEdited,
            fullIdDisplay = fullIdDisplay,
            errorLabels = errorLabels
        )
    }
}
