package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.ui.home.BlockSegment
import com.antigravity.healthagent.utils.normalize
import com.antigravity.healthagent.utils.formatStreetName
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

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

        // 1. Pre-calculate global completion states exactly like Semanal/Boletim logic
        val allHousesSorted = allHouses.sortedBy { it.listOrder }
        val blockToLastIndex = mutableMapOf<String, Int>()
        allHousesSorted.forEachIndexed { index, h ->
            val key = "${h.blockNumber.normalize()}|${h.blockSequence.normalize()}|${h.bairro.normalize()}".uppercase()
            blockToLastIndex[key] = index
        }

        val bairroHouses = allHouses.filter { it.bairro.equals(selectedBairro, ignoreCase = true) }
        
        // PERFORMANCE FIX (O(N)): Pre-calculate multi-segment addresses per day
        val dailyAddressSegments = bairroHouses.groupBy { 
            "${it.data}|${it.bairro.normalize()}|${it.blockNumber.normalize()}|${it.blockSequence.normalize()}|${it.streetName.formatStreetName()}|${it.number.normalize()}|${it.sequence}|${it.complement}".uppercase()
        }.mapValues { it.value.map { h -> h.visitSegment }.distinct().size > 1 }

        // DEDUPLICATION: Use the O(N) map to quickly group houses
        val deduplicatedHouses = bairroHouses.groupBy { 
            val addrKey = "${it.data}|${it.bairro.normalize()}|${it.blockNumber.normalize()}|${it.blockSequence.normalize()}|${it.streetName.formatStreetName()}|${it.number.normalize()}|${it.sequence}|${it.complement}".uppercase()
            val baseIdentity = "${it.bairro.normalize()}|${it.blockNumber.normalize()}|${it.blockSequence.normalize()}|${it.streetName.formatStreetName()}|${it.number.normalize()}|${it.sequence}|${it.complement}".uppercase()
            
            if (dailyAddressSegments[addrKey] == true) "$baseIdentity|${it.visitSegment}" 
            else baseIdentity
        }.map { (_, entries) ->
            entries.sortedWith(compareByDescending<House> { getTimestamp(it.data) }.thenByDescending { it.lastUpdated }).first()
        }

        val segments = mutableListOf<BlockSegment>()
        
        // AGENT-DEFINED TIMELINE: Group by Date, then Agent, then their local construction order.
        val sortedHouses = deduplicatedHouses.sortedWith(
            compareBy<House> { getTimestamp(it.data) }
                .thenBy { it.agentName }
                .thenBy { it.listOrder }
        )
        
        val groupedByBlock = sortedHouses.groupBy { Pair(it.blockNumber.trim().uppercase(), it.blockSequence.trim().uppercase()) }
        
        // Respect the order of blocks as they were visited
        groupedByBlock.keys.forEach { key ->
            val (bNum, bSeq) = key
            val blockHouses = groupedByBlock[key] ?: return@forEach 
            
            val rawBlockVisits = allHouses.filter { 
                it.blockNumber.trim().equals(bNum, ignoreCase = true) && 
                it.blockSequence.trim().equals(bSeq, ignoreCase = true) 
            }
            
            // Completion Logic: A block is only concluded if ALL current houses are worked 
            // AND the latest manual flag isn't "Open".
            val autoConcluido = blockHouses.all { h ->
                h.situation != Situation.NONE && h.situation != Situation.EMPTY
            }
            
            val latestManualVisit = rawBlockVisits.maxByOrNull { getTimestamp(it.data) }
            val manualConcluded = latestManualVisit?.quarteiraoConcluido == true || latestManualVisit?.localidadeConcluida == true
            
            val isConcluded = manualConcluded && autoConcluido
            
            val conclusionDate = if (isConcluded) {
                latestManualVisit?.data ?: blockHouses.maxByOrNull { getTimestamp(it.data) }?.data
            } else null
            
            segments.add(BlockSegment(
                blockNumber = bNum,
                blockSequence = bSeq,
                startDate = blockHouses.minOfOrNull { getTimestamp(it.data) }?.let { Date(it) }?.let { dateFormatter.format(it) } ?: "",
                endDate = blockHouses.maxOfOrNull { getTimestamp(it.data) }?.let { Date(it) }?.let { dateFormatter.format(it) } ?: "",
                isConcluded = isConcluded,
                conclusionDate = conclusionDate,
                houses = blockHouses
            ))
        }
        
        // Filter by the selected Year
        return if (selectedYear.isBlank()) segments
        else {
            segments.filter { seg ->
                val dateToCheck = seg.conclusionDate ?: seg.endDate
                try {
                    val cal = Calendar.getInstance()
                    dateFormatter.parse(dateToCheck)?.let { cal.time = it }
                    cal.get(Calendar.YEAR).toString() == selectedYear
                } catch (e: Exception) {
                    dateToCheck.endsWith(selectedYear)
                }
            }
        }
    }
}
