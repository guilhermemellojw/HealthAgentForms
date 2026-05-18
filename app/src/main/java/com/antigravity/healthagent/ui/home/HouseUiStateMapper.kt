package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.heal
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
        
        val treatmentSummary = house.treatment.formattedSummary
        
        // Resilience: Ensure house has a fallback agentName and healed situation if missing during mapping
        val displayHouse = house.copy(
            agentName = house.agentName.ifBlank { "NÃO ATRIBUÍDO" },
            situation = house.situation.heal()
        )
        
        val fullIdDisplay = house.address.fullIdDisplay

        return HouseUiState(
            house = displayHouse,
            invalidFields = if (isRecentlyEdited) emptySet() else invalidFields,
            highlightErrors = !isRecentlyEdited && (invalidFields.isNotEmpty() || isDuplicate),
            isTreated = house.treatment.hasAnyTreatment,
            blockDisplay = if (house.address.blockSequence.isNotBlank()) "${house.address.blockNumber} / ${house.address.blockSequence}" else house.address.blockNumber,
            formattedStreet = formattedStreet,
            treatmentShortSummary = treatmentSummary,
            observation = displayHouse.observation,
            isRecentlyEdited = isRecentlyEdited,
            isHighlighted = isHighlighted,
            isMine = isMine,
            fullIdDisplay = fullIdDisplay,
            errorLabels = errorLabels
        )
    }
}
