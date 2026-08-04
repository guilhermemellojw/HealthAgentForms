package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.model.heal
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.utils.formatStreetName

data class CachedHouseUi(
    val house: House,
    val isDuplicate: Boolean,
    val isRecentlyEdited: Boolean,
    val isHighlighted: Boolean,
    val isMine: Boolean,
    val ui: HouseUiState
)

data class MappedDayResult(
    val states: List<HouseUiState>,
    val cache: Map<Int, CachedHouseUi>
)

/**
 * Incremental day mapping: only re-renders cards whose inputs (house content or flags)
 * actually changed. Previous state is reused for unchanged houses, keeping the hot
 * path O(changed) instead of O(day) per save.
 */
fun mapDayIncremental(
    houses: List<House>,
    previous: Map<Int, CachedHouseUi>,
    generateHouseKey: (House) -> String,
    recentlyEditedHouseIds: Map<Int, Long>,
    highlightedId: Int?,
    myUid: String,
    mapper: (House, Boolean, Boolean, Boolean, Boolean) -> HouseUiState
): MappedDayResult {
    val keys = houses.map { generateHouseKey(it) }
    val counts = HashMap<String, Int>(keys.size * 2)
    keys.forEach { key -> counts[key] = (counts[key] ?: 0) + 1 }

    val states = ArrayList<HouseUiState>(houses.size)
    val newCache = HashMap<Int, CachedHouseUi>(previous.size * 2)
    for (i in houses.indices) {
        val house = houses[i]
        val isDuplicate = (counts[keys[i]] ?: 0) > 1
        val isRecentlyEdited = recentlyEditedHouseIds.containsKey(house.id)
        val isHighlighted = house.id == highlightedId
        val isMine = house.agentUid == myUid

        val cached = previous[house.id]
        val ui = if (cached != null &&
            cached.house == house &&
            cached.isDuplicate == isDuplicate &&
            cached.isRecentlyEdited == isRecentlyEdited &&
            cached.isHighlighted == isHighlighted &&
            cached.isMine == isMine
        ) {
            cached.ui
        } else {
            mapper(house, isDuplicate, isRecentlyEdited, isHighlighted, isMine)
        }
        newCache[house.id] = CachedHouseUi(house, isDuplicate, isRecentlyEdited, isHighlighted, isMine, ui)
        states.add(ui)
    }
    return MappedDayResult(states, newCache)
}

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
            errorLabels = errorLabels.toList()
        )
    }
}
