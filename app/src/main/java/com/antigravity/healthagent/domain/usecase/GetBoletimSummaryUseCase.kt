package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.ui.home.BoletimSummary
import com.antigravity.healthagent.ui.home.BlockSummary
import com.antigravity.healthagent.ui.home.DashboardTotals
import javax.inject.Inject

class GetBoletimSummaryUseCase @Inject constructor() {
    operator fun invoke(
        allHouses: List<House>,
        activities: List<DayActivity>
    ): List<BoletimSummary> {
        // IMPROVEMENT: Group by Date AND Agent UID to prevent data splitting when names are inconsistent (Bug Fix #11)
        // We use isUidEmpty as part of the key to keep truly orphaned records (empty UID) isolated by name.
        return allHouses.groupBy { Triple(it.data, it.agentUid, it.agentUid.isEmpty()) }.map { (groupKey, dayHouses) ->
            val (date, agentUid, isUidEmpty) = groupKey
            
            // If UID is empty, we further group by name to avoid mixing different agents without UIDs
            val effectiveGroup = if (isUidEmpty) {
                dayHouses.groupBy { it.agentName.uppercase() }
            } else {
                mapOf(pickBestName(dayHouses) to dayHouses)
            }

            effectiveGroup.map { (agentName, groupHouses) ->
                val activity = activities.find { it.date == date && (it.agentUid == agentUid || (it.agentUid.isEmpty() && it.agentName.uppercase() == agentName)) }
                val dayHousesSorted = groupHouses.sortedBy { it.listOrder }
                
                val blockMap = dayHousesSorted.groupBy { 
                    Triple(it.bairro.trim().uppercase(), it.blockNumber, it.blockSequence) 
                }
                
                val completedBlocks = mutableListOf<Triple<String, String, String>>()
                blockMap.forEach { (blockKey, blockHouses) ->
                    if (blockHouses.any { it.quarteiraoConcluido }) {
                        completedBlocks.add(blockKey)
                    } else {
                        val lastInBlockId = blockHouses.last().id
                        val indexOfLast = dayHousesSorted.indexOfFirst { it.id == lastInBlockId }
                        if (indexOfLast != -1 && indexOfLast < dayHousesSorted.size - 1) {
                            completedBlocks.add(blockKey)
                        }
                    }
                }
                
                val bairrosWithLocalidadeConcluida = groupHouses
                    .filter { it.localidadeConcluida }
                    .map { it.bairro }
                    .toSet()
                
                val blockSummaries = blockMap.map { (blockKey, blockHouses) ->
                    val firstBH = blockHouses.first()
                    BlockSummary(
                        number = blockKey.second,
                        sequence = blockKey.third,
                        bairro = firstBH.bairro,
                        isCompleted = completedBlocks.contains(blockKey),
                        isLocalidadeConcluded = bairrosWithLocalidadeConcluida.contains(firstBH.bairro),
                        totalHouses = blockHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY }, // Abertos
                        totalVisits = blockHouses.size, // Total real visits
                        focos = blockHouses.count { it.comFoco }
                    )
                }.sortedWith(compareBy({ it.isCompleted }, { it.bairro }, { it.number }))

                BoletimSummary(
                    date = date,
                    agentName = agentName,
                    totals = DashboardTotals(
                        totalHouses = groupHouses.size,
                        a1 = groupHouses.sumOf { it.a1 }, a2 = groupHouses.sumOf { it.a2 },
                        b = groupHouses.sumOf { it.b }, c = groupHouses.sumOf { it.c },
                        d1 = groupHouses.sumOf { it.d1 }, d2 = groupHouses.sumOf { it.d2 },
                        e = groupHouses.sumOf { it.e }, eliminados = groupHouses.sumOf { it.eliminados },
                        larvicida = groupHouses.sumOf { it.larvicida },
                        worked = groupHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY },
                        recused = groupHouses.count { it.situation == Situation.REC },
                        absent = groupHouses.count { it.situation == Situation.A },
                        closed = groupHouses.count { it.situation == Situation.F },
                        vacant = groupHouses.count { it.situation == Situation.V },
                        totalFocos = groupHouses.count { it.comFoco },
                        totalRegisteredHouses = groupHouses.size
                    ),
                    blocks = blockSummaries,
                    status = activity?.status ?: ""
                )
            }
        }.flatten().sortedWith(compareByDescending { 
            try {
                java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US).parse(it.date)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        })
    }

    /**
     * Heuristic to pick the most descriptive agent name from a list of records.
     * Prioritizes actual names over email addresses.
     */
    private fun pickBestName(houses: List<House>): String {
        val names = houses.map { it.agentName }.distinct()
        if (names.size <= 1) return names.firstOrNull()?.uppercase() ?: ""
        
        // Filter out emails if possible
        val nonEmails = names.filter { !it.contains("@") && it.isNotBlank() }
        val candidates = if (nonEmails.isNotEmpty()) nonEmails else names
        
        return candidates.maxByOrNull { it.length }?.uppercase() ?: ""
    }
}
