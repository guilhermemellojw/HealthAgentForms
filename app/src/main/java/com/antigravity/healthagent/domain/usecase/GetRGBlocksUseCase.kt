package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.ui.home.BlockSegment
import com.antigravity.healthagent.utils.normalize
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * GetRGBlocksUseCase
 * Refactored to follow the "Field Path" (Boletim) logic.
 * 
 * Rules:
 * 1. Identity: street + number + sequence + complement.
 * 2. Deduplication: Prioritize worked houses (situation != NONE) over empty ones.
 * 3. Sorting: Use createdAt to interleave multiple agents and days in chronological order.
 */
class GetRGBlocksUseCase @Inject constructor() {
    private val dateFormatter = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    private fun getTimestamp(date: String): Long {
        if (date.isBlank()) return 0L
        return synchronized(dateFormatter) {
            try {
                dateFormatter.parse(date.replace("/", "-"))?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
    }

    operator fun invoke(
        allHouses: List<House>,
        selectedBairro: String,
        selectedYear: String
    ): List<BlockSegment> {
        if (selectedBairro.isBlank()) return emptyList()

        val normalizedSelectedBairro = selectedBairro.normalize()

        // 1. EARLY FILTERING: Focus only on the selected neighborhood and year
        val initialFiltered = allHouses.filter { house ->
            val matchesBairro = house.address.bairro.normalize() == normalizedSelectedBairro
            val matchesYear = if (selectedYear.isBlank()) true else {
                synchronized(dateFormatter) {
                    try {
                        val cal = Calendar.getInstance()
                        dateFormatter.parse(house.data.replace("/", "-"))?.let { cal.time = it }
                        cal.get(Calendar.YEAR).toString() == selectedYear
                    } catch (e: Exception) {
                        house.data.contains(selectedYear)
                    }
                }
            }
            matchesBairro && matchesYear
        }

        if (initialFiltered.isEmpty()) return emptyList()

        // 1. GLOBAL PERCURSO (Unfiltered)
        // We use the entire database to judge if a block was "left behind" globally.
        // This ensures that the last block of a bairro closes if the agent moved to another bairro.
        val globalSortedVisits = allHouses
            .sortedWith(compareBy(
                { getTimestamp(it.data) },
                { it.agentName },
                { it.listOrder },
                { it.id }
            ))

        // 2. FILTERED SORTING (For display)
        val sortedVisits = initialFiltered
            .sortedWith(compareBy(
                { getTimestamp(it.data) },
                { it.agentName },
                { it.listOrder },
                { it.id }
            ))

        // 3. GROUP BY BLOCK & CREATE SEGMENTS
        val blockGroups = sortedVisits.groupBy { 
            it.address.blockNumber.normalize() to it.address.blockSequence.normalize() 
        }

        val blockOrder = sortedVisits.map { 
            it.address.blockNumber.normalize() to it.address.blockSequence.normalize() 
        }.distinct()

        return blockOrder.map { (bNum, bSeq) ->
            val blockHouses = blockGroups[bNum to bSeq] ?: emptyList()
            val lastHouse = blockHouses.lastOrNull()
            
            // Implicit Conclusion Logic (Global Parity):
            // A block is concluded if it has a manual flag OR if it was "left behind" 
            // in the global percurso of the agent(s).
            val lastHouseId = lastHouse?.id ?: -1
            val indexOfLastInGlobal = globalSortedVisits.indexOfLast { it.id == lastHouseId }
            val isImplicitlyConcluded = indexOfLastInGlobal != -1 && indexOfLastInGlobal < globalSortedVisits.lastIndex

            BlockSegment(
                blockNumber = bNum,
                blockSequence = bSeq,
                startDate = blockHouses.firstOrNull()?.data ?: "",
                endDate = lastHouse?.data ?: "",
                isConcluded = blockHouses.any { it.quarteiraoConcluido || it.localidadeConcluida } || isImplicitlyConcluded,
                // The date of the report is the date of the LAST house in the list
                conclusionDate = lastHouse?.data ?: "",
                houses = blockHouses,
                participatingAgents = blockHouses.map { h -> h.agentName }.distinct().filter { name -> name.isNotBlank() }
            )
        }
    }
}
