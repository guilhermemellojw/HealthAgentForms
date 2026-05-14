package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.utils.formatStreetName

object HouseUiStateMapper {
    fun map(
        house: House, 
        houseValidationUseCase: HouseValidationUseCase, 
        isDuplicate: Boolean = false,
        isRecentlyEdited: Boolean = false,
        isHighlighted: Boolean = false,
        isMine: Boolean = true
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

        val formattedStreet = house.address.streetName.formatStreetName().ifBlank { "Sem Logradouro" }
        
        val treatmentParts = mutableListOf<String>()
        if (house.treatment.a1 > 0) treatmentParts.add("A1: ${house.treatment.a1}")
        if (house.treatment.a2 > 0) treatmentParts.add("A2: ${house.treatment.a2}")
        if (house.treatment.b > 0) treatmentParts.add("B: ${house.treatment.b}")
        if (house.treatment.c > 0) treatmentParts.add("C: ${house.treatment.c}")
        if (house.treatment.d1 > 0) treatmentParts.add("D1: ${house.treatment.d1}")
        if (house.treatment.d2 > 0) treatmentParts.add("D2: ${house.treatment.d2}")
        if (house.treatment.e > 0) treatmentParts.add("E: ${house.treatment.e}")
        if (house.treatment.eliminados > 0) treatmentParts.add("Elim: ${house.treatment.eliminados}")
        if (house.treatment.larvicida > 0.0) treatmentParts.add("Larv: ${house.treatment.larvicida}g")
        
        // Resilience: Ensure house has a fallback agentName and healed situation if missing during mapping
        val displayHouse = house.copy(
            agentName = house.agentName.ifBlank { "NÃO ATRIBUÍDO" },
            situation = if (house.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) 
                com.antigravity.healthagent.data.local.model.Situation.NONE else house.situation
        )
        
        val fullIdDisplay = house.address.fullIdDisplay

        return HouseUiState(
            house = displayHouse,
            invalidFields = if (isRecentlyEdited) emptySet() else invalidFields,
            highlightErrors = !isRecentlyEdited && (invalidFields.isNotEmpty() || isDuplicate),
            isTreated = treatmentParts.isNotEmpty() || house.treatment.comFoco,
            blockDisplay = if (house.address.blockSequence.isNotBlank()) "${house.address.blockNumber} / ${house.address.blockSequence}" else house.address.blockNumber,
            formattedStreet = formattedStreet,
            treatmentShortSummary = treatmentParts.joinToString(" | "),
            observation = displayHouse.observation,
            isRecentlyEdited = isRecentlyEdited,
            isHighlighted = isHighlighted,
            isMine = isMine,
            fullIdDisplay = fullIdDisplay,
            errorLabels = errorLabels
        )
    }
}
