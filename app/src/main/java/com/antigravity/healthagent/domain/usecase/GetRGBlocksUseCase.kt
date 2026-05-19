package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.ui.home.BlockSegment
import com.antigravity.healthagent.utils.normalize
import com.antigravity.healthagent.utils.healBairro
import com.antigravity.healthagent.utils.healAgentName
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

        val healedSelectedBairro = selectedBairro.healBairro()

        // 1. EARLY FILTERING: Focus only on the selected neighborhood and year
        val initialFiltered = allHouses.filter { house ->
            val matchesBairro = house.address.bairro.healBairro() == healedSelectedBairro
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

        // 3. GROUP BY BLOCK AND CHRONOLOGICALLY PARTITION INTO SEGMENTS
        val blockGroups = sortedVisits.groupBy { 
            it.address.blockNumber.normalize() to it.address.blockSequence.normalize() 
        }

        val blockOrder = sortedVisits.map { 
            it.address.blockNumber.normalize() to it.address.blockSequence.normalize() 
        }.distinct()

        val allSegments = mutableListOf<BlockSegment>()

        for ((bNum, bSeq) in blockOrder) {
            val unsortedBlockHouses = blockGroups[bNum to bSeq] ?: emptyList()
            
            // Compute the earliest visit timestamp for each agent on each day inside this block
            val agentEarliestTimeOnDay = unsortedBlockHouses
                .groupBy { getTimestamp(it.data) to it.agentUid }
                .mapValues { (_, houses) ->
                    houses.minOfOrNull { it.createdAt } ?: Long.MAX_VALUE
                }

            // Sort the block houses chronologically by day, then by the agent who started first on that day,
            // then by listOrder, and fallback to SQLite ID.
            val blockHouses = unsortedBlockHouses.sortedWith(compareBy(
                { getTimestamp(it.data) },
                { agentEarliestTimeOnDay[getTimestamp(it.data) to it.agentUid] ?: Long.MAX_VALUE },
                { it.agentName },
                { it.listOrder },
                { it.id }
            ))
            
            // Partition houses of this block into chronological segments based on manual completion flags
            val partitions = mutableListOf<MutableList<House>>()
            var currentPartition = mutableListOf<House>()
            
            for (house in blockHouses) {
                currentPartition.add(house)
                if (house.quarteiraoConcluido || house.localidadeConcluida) {
                    partitions.add(currentPartition)
                    currentPartition = mutableListOf()
                }
            }
            if (currentPartition.isNotEmpty()) {
                partitions.add(currentPartition)
            }

            // Map each partition to a BlockSegment
            for (partition in partitions) {
                val lastHouse = partition.lastOrNull()
                val lastHouseId = lastHouse?.id ?: -1
                
                // Implicit Conclusion Logic (Global Parity):
                // A segment is implicitly concluded if its last house is not the last house 
                // in the global percurso of the agent(s).
                val indexOfLastInGlobal = globalSortedVisits.indexOfLast { it.id == lastHouseId }
                val isImplicitlyConcluded = indexOfLastInGlobal != -1 && indexOfLastInGlobal < globalSortedVisits.lastIndex
                
                val isConcluded = partition.any { it.quarteiraoConcluido || it.localidadeConcluida } || isImplicitlyConcluded

                allSegments.add(
                    BlockSegment(
                        blockNumber = bNum,
                        blockSequence = bSeq,
                        startDate = partition.firstOrNull()?.data ?: "",
                        endDate = lastHouse?.data ?: "",
                        isConcluded = isConcluded,
                        conclusionDate = lastHouse?.data,
                        houses = partition,
                        participatingAgents = partition.map { h -> h.agentName.healAgentName() }.distinct().filter { name -> name.isNotBlank() }
                    )
                )
            }
        }

        return allSegments
    }
}
