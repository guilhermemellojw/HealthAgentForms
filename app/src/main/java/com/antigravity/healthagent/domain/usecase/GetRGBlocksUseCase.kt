package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.ui.home.BlockSegment
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class GetRGBlocksUseCase @Inject constructor() {
    private val dateFormatter = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    private fun getTimestamp(date: String): Long {
        return try {
            dateFormatter.parse(date)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    operator fun invoke(
        allHouses: List<House>,
        selectedBairro: String,
        selectedYear: String
    ): List<BlockSegment> {
        if (selectedBairro.isBlank()) return emptyList()

        val bairroHouses = allHouses.filter { it.bairro.equals(selectedBairro, ignoreCase = true) }
        val segments = mutableListOf<BlockSegment>()
        
        // Use createdAt for global chronological sorting across multiple agents (perfect for offline pairs)
        val sortedHouses = bairroHouses.sortedWith(compareBy({ getTimestamp(it.data) }, { it.createdAt }))
        val groupedByBlock = sortedHouses.groupBy { Pair(it.blockNumber, it.blockSequence) }
        
        groupedByBlock.keys.sortedWith(compareBy({ it.first.padStart(10, '0') }, { it.second.padStart(10, '0') })).forEach { key ->
            val (bNum, bSeq) = key
            val blockHouses = groupedByBlock[key] ?: return@forEach 
            
            var currentSegmentHouses = mutableListOf<House>()
            val housesByDate = blockHouses.groupBy { it.data }
            val sortedDates = housesByDate.keys.sortedBy { getTimestamp(it) }
            
            sortedDates.forEach { date ->
                val dayHousesForBlock = housesByDate[date] ?: emptyList()
                
                // 1. Group by agent and find their earliest work in this specific block/day
                val agentFirstTouch = dayHousesForBlock.groupBy { it.agentName }
                    .mapValues { (_, houses) -> houses.minOf { it.createdAt } }
                
                // 2. Sort agents by who started THIS block first
                val sortedAgents = agentFirstTouch.toList().sortedBy { it.second }.map { it.first }
                
                // 3. Complete all of the first agent's houses before moving to the next
                val dayHouses = dayHousesForBlock.sortedWith(compareBy(
                    { sortedAgents.indexOf(it.agentName) },
                    { it.createdAt }
                ))
                
                currentSegmentHouses.addAll(dayHouses)
                
                val lastHouseOfDay = dayHouses.last()
                val manualConcluded = dayHouses.any { it.quarteiraoConcluido }
                
                // Auto-Conclusion logic: did the agent(s) work on something else AFTER this block on the same day?
                // We sort all global work that day by absolute creation time to detect block transitions
                val allWorkThatDay = allHouses.filter { it.data == date }.sortedBy { it.createdAt }
                val indexOfLast = allWorkThatDay.indexOfFirst { it.id == lastHouseOfDay.id }
                val autoConcluded = indexOfLast != -1 && indexOfLast < allWorkThatDay.size - 1
                
                if (manualConcluded || autoConcluded) {
                     segments.add(BlockSegment(
                         blockNumber = bNum,
                         blockSequence = bSeq,
                         startDate = currentSegmentHouses.first().data,
                         endDate = currentSegmentHouses.last().data,
                         isConcluded = true,
                         conclusionDate = lastHouseOfDay.data,
                         houses = currentSegmentHouses.toList()
                     ))
                     currentSegmentHouses = mutableListOf()
                }
            }
            
            if (currentSegmentHouses.isNotEmpty()) {
                 segments.add(BlockSegment(
                     blockNumber = bNum,
                     blockSequence = bSeq,
                     startDate = currentSegmentHouses.first().data,
                     endDate = currentSegmentHouses.last().data,
                     isConcluded = false,
                     conclusionDate = null,
                     houses = currentSegmentHouses.toList()
                 ))
            }
        }
        
        // Filter by the selected Year
        return if (selectedYear.isBlank()) segments
        else {
            segments.filter { seg ->
                val dateToCheck = seg.conclusionDate ?: seg.endDate
                dateToCheck.endsWith(selectedYear)
            }
        }
    }
}
