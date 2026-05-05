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
            
            val manualConcluded = blockHouses.any { it.quarteiraoConcluido }
            
            segments.add(BlockSegment(
                blockNumber = bNum,
                blockSequence = bSeq,
                startDate = blockHouses.firstOrNull()?.data ?: "",
                endDate = blockHouses.lastOrNull()?.data ?: "",
                isConcluded = manualConcluded,
                conclusionDate = if (manualConcluded) blockHouses.lastOrNull { it.quarteiraoConcluido }?.data ?: blockHouses.lastOrNull()?.data else null,
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
