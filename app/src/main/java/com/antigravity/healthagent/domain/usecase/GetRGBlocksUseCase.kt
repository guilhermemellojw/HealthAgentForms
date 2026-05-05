package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.ui.home.BlockSegment
import com.antigravity.healthagent.utils.normalize
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

        // 1. Pre-calculate global completion states exactly like Semanal/Boletim logic
        val allHousesSorted = allHouses.sortedBy { it.listOrder }
        val blockToLastIndex = mutableMapOf<String, Int>()
        allHousesSorted.forEachIndexed { index, h ->
            val key = "${h.blockNumber.trim().uppercase()}|${h.blockSequence.trim().uppercase()}|${h.bairro.trim().uppercase()}"
            blockToLastIndex[key] = index
        }

        val bairroHouses = allHouses.filter { it.bairro.equals(selectedBairro, ignoreCase = true) }
        
        // DEDUPLICATION: Ensure each physical house appears only once in the RG report.
        // We keep the LATEST visit based on date and lastUpdated timestamp.
        val deduplicatedHouses = bairroHouses.groupBy { 
            "${it.bairro.normalize()}|${it.blockNumber.normalize()}|${it.blockSequence.normalize()}|${it.streetName.normalize()}|${it.number.normalize()}|${it.sequence}|${it.complement}".uppercase()
        }.map { (_, entries) ->
            entries.sortedWith(compareByDescending<House> { getTimestamp(it.data) }.thenByDescending { it.lastUpdated }).first()
        }

        val segments = mutableListOf<BlockSegment>()
        
        // Use listOrder for internal house sequence (supports manual reordering)
        val sortedHouses = deduplicatedHouses.sortedWith(compareBy({ getTimestamp(it.data) }, { it.listOrder }))
        val groupedByBlock = sortedHouses.groupBy { Pair(it.blockNumber.trim().uppercase(), it.blockSequence.trim().uppercase()) }
        
        groupedByBlock.keys.sortedWith(compareBy({ it.first.padStart(10, '0') }, { it.second.padStart(10, '0') })).forEach { key ->
            val (bNum, bSeq) = key
            val blockHouses = groupedByBlock[key] ?: return@forEach 
            
            val globalKey = "${bNum}|${bSeq}|${selectedBairro.trim().uppercase()}"
            val lastIndexInFull = blockToLastIndex[globalKey] ?: -1
            
            // Get all raw visits for this block to check manual flags across history, not just deduplicated
            val rawBlockVisits = bairroHouses.filter { 
                it.blockNumber.trim().uppercase() == bNum &&
                it.blockSequence.trim().uppercase() == bSeq
            }
            
            val manualConcluded = rawBlockVisits.any { it.quarteiraoConcluido || it.localidadeConcluida }
            val autoConcluido = lastIndexInFull != -1 && lastIndexInFull < allHousesSorted.size - 1
            
            val isConcluded = manualConcluded || autoConcluido
            
            val conclusionDate = if (isConcluded) {
                if (manualConcluded) {
                    rawBlockVisits.lastOrNull { it.quarteiraoConcluido || it.localidadeConcluida }?.data ?: blockHouses.lastOrNull()?.data
                } else {
                    val lastHouseInFull = if (lastIndexInFull != -1) allHousesSorted[lastIndexInFull] else null
                    lastHouseInFull?.data ?: blockHouses.lastOrNull()?.data
                }
            } else null
            
            segments.add(BlockSegment(
                blockNumber = bNum,
                blockSequence = bSeq,
                startDate = blockHouses.firstOrNull()?.data ?: "",
                endDate = blockHouses.lastOrNull()?.data ?: "",
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
