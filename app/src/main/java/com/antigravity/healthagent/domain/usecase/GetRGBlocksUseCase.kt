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
            val matchesBairro = house.bairro.normalize() == normalizedSelectedBairro
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

        // 2. PRODUCTION-FAITHFUL SORTING
        // We mirror the Production Screen sorting logic: Date first, then listOrder, then id.
        // NO DEDUPLICATION: Every visit in the production/bulletin must appear in the RG.
        val sortedVisits = initialFiltered
            .sortedWith(compareBy(
                { getTimestamp(it.data) }, // Primary: Production Date
                { it.agentName },           // Secondary: Group by Agent to avoid interleaving
                { it.listOrder },           // Tertiary: Manual Percurso Order
                { it.id }                  // Quaternary: Database ID (Tie-breaker)
            ))

        // 3. GROUP BY BLOCK & CREATE SEGMENTS
        val blockGroups = sortedVisits.groupBy { 
            it.blockNumber.normalize() to it.blockSequence.normalize() 
        }

        val blockOrder = sortedVisits.map { 
            it.blockNumber.normalize() to it.blockSequence.normalize() 
        }.distinct()

        return blockOrder.map { (bNum, bSeq) ->
            val blockHouses = blockGroups[bNum to bSeq] ?: emptyList()
            val lastHouse = blockHouses.lastOrNull()
            
            BlockSegment(
                blockNumber = bNum,
                blockSequence = bSeq,
                startDate = blockHouses.firstOrNull()?.data ?: "",
                endDate = lastHouse?.data ?: "",
                isConcluded = blockHouses.any { it.quarteiraoConcluido || it.localidadeConcluida },
                // The date of the report is the date of the LAST house in the list
                conclusionDate = lastHouse?.data ?: "",
                houses = blockHouses,
                participatingAgents = blockHouses.map { it.agentName }.distinct().filter { it.isNotBlank() }
            )
        }
    }
}
