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
        return allHouses.groupBy { it.data }.map { (date, dayHouses) ->
            val activity = activities.find { it.date == date }
            val dayHousesSorted = dayHouses.sortedBy { it.listOrder }
            
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
            
            val bairrosWithLocalidadeConcluida = dayHouses
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
                    totalHouses = blockHouses.count { it.situation == Situation.NONE },
                    totalVisits = blockHouses.size,
                    focos = blockHouses.count { it.comFoco }
                )
            }.sortedWith(compareBy({ it.isCompleted }, { it.bairro }, { it.number }))

            BoletimSummary(
                date = date,
                agentName = dayHouses.firstOrNull()?.agentName ?: "",
                totals = DashboardTotals(
                    totalHouses = dayHouses.count { it.situation == Situation.NONE },
                    a1 = dayHouses.sumOf { it.a1 }, a2 = dayHouses.sumOf { it.a2 },
                    b = dayHouses.sumOf { it.b }, c = dayHouses.sumOf { it.c },
                    d1 = dayHouses.sumOf { it.d1 }, d2 = dayHouses.sumOf { it.d2 },
                    e = dayHouses.sumOf { it.e }, eliminados = dayHouses.sumOf { it.eliminados },
                    larvicida = dayHouses.sumOf { it.larvicida },
                    recused = dayHouses.count { it.situation == Situation.REC },
                    absent = dayHouses.count { it.situation == Situation.A },
                    closed = dayHouses.count { it.situation == Situation.F },
                    vacant = dayHouses.count { it.situation == Situation.V },
                    totalFocos = dayHouses.count { it.comFoco }
                ),
                blocks = blockSummaries,
                status = activity?.status ?: ""
            )
        }.sortedWith(compareByDescending { 
            try {
                java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.getDefault()).parse(it.date)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        })
    }
}
