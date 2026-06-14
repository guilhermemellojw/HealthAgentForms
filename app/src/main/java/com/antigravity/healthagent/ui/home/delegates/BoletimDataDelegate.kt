package com.antigravity.healthagent.ui.home.delegates

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.ui.home.BoletimSummary
import com.antigravity.healthagent.ui.home.BlockSummary
import com.antigravity.healthagent.ui.home.DashboardTotals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import com.antigravity.healthagent.utils.DateUtils
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BoletimDataDelegate @Inject constructor() {
    private fun getFastTimestamp(date: String): Long {
        return try {
            val parts = if (date.contains("-")) date.split("-") else date.split("/")
            if (parts.size == 3) {
                val day = parts[0].toIntOrNull() ?: 0
                val month = parts[1].toIntOrNull() ?: 0
                val year = parts[2].toIntOrNull() ?: 0
                (year * 10000 + month * 100 + day).toLong()
            } else 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun getBoletimListFlow(
        scope: CoroutineScope,
        allHousesFlow: Flow<List<House>>,
        agentNameFlow: Flow<String>,
        remoteAgentUidFlow: Flow<String?>,
        currentUserUidFlow: Flow<String?>
    ): StateFlow<List<BoletimSummary>> {
        val globalSortedVisits = allHousesFlow.map { all ->
            all.sortedWith(compareBy(
                { getFastTimestamp(it.data) },
                { it.agentName },
                { it.listOrder },
                { it.id }
            ))
        }.flowOn(Dispatchers.Default)

        return combine(
            allHousesFlow,
            globalSortedVisits,
            agentNameFlow,
            remoteAgentUidFlow,
            currentUserUidFlow
        ) { all, global, name, remoteUid, currentUid ->
            val targetUid = remoteUid ?: currentUid
            val personalHouses = all.filter { house ->
                house.agentUid == targetUid || (house.agentUid.isEmpty() && (
                    house.agentName.uppercase() == name ||
                    name.contains(house.agentName.uppercase()) ||
                    house.agentName.uppercase().contains(name)
                ))
            }
            val groupedByDate = personalHouses.groupBy { it.data }.toList().sortedByDescending { getFastTimestamp(it.first) }

            groupedByDate.map { (date, houses) ->
                val blocks = houses.groupBy { "${it.address.blockNumber}-${it.address.blockSequence}-${it.address.bairro}" }
                    .map { (_, blockHouses) ->
                        val h = blockHouses.first()

                        val lastHouseInBlock = blockHouses.lastOrNull()
                        val lastHouseId = lastHouseInBlock?.id ?: -1L
                        val indexOfLastInGlobal = global.indexOfLast { it.id == lastHouseId }
                        val isImplicitlyConcluded = indexOfLastInGlobal != -1 && indexOfLastInGlobal < global.lastIndex

                        val isCompleted = blockHouses.any { it.quarteiraoConcluido || it.localidadeConcluida } || isImplicitlyConcluded

                        BlockSummary(
                            number = h.address.blockNumber,
                            sequence = h.address.blockSequence,
                            bairro = h.address.bairro,
                            isCompleted = isCompleted,
                            isLocalidadeConcluded = blockHouses.any { it.localidadeConcluida },
                            totalHouses = blockHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY },
                            totalVisits = blockHouses.size,
                            focos = blockHouses.count { it.treatment.comFoco }
                        )
                    }

                BoletimSummary(
                    date = date,
                    agentName = houses.firstOrNull()?.agentName ?: name,
                    totals = calculateDashboardTotals(houses),
                    blocks = blocks,
                    status = if (blocks.all { it.isCompleted }) "CONCLUÍDO" else "EM ABERTO"
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun calculateDashboardTotals(dayHouses: List<House>): DashboardTotals {
        return DashboardTotals(
            totalHouses = dayHouses.size,
            a1 = dayHouses.sumOf { it.treatment.a1 },
            a2 = dayHouses.sumOf { it.treatment.a2 },
            b = dayHouses.sumOf { it.treatment.b },
            c = dayHouses.sumOf { it.treatment.c },
            d1 = dayHouses.sumOf { it.treatment.d1 },
            d2 = dayHouses.sumOf { it.treatment.d2 },
            e = dayHouses.sumOf { it.treatment.e },
            eliminados = dayHouses.sumOf { it.treatment.eliminados },
            larvicida = dayHouses.sumOf { it.treatment.larvicida },
            totalFocos = dayHouses.count { it.treatment.comFoco },
            totalRegisteredHouses = dayHouses.size,
            worked = dayHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY },
            recused = dayHouses.count { it.situation == Situation.REC },
            absent = dayHouses.count { it.situation == Situation.A },
            closed = dayHouses.count { it.situation == Situation.F },
            vacant = dayHouses.count { it.situation == Situation.V }
        )
    }
}
