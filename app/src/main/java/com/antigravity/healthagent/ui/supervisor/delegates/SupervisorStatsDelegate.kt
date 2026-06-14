package com.antigravity.healthagent.ui.supervisor.delegates

import android.content.Context
import android.net.Uri
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.domain.repository.AccessControlRepository
import com.antigravity.healthagent.domain.usecase.RestoreDataUseCase
import com.antigravity.healthagent.ui.supervisor.AggregateSummary
import com.antigravity.healthagent.ui.supervisor.StatDetail
import com.antigravity.healthagent.utils.toNumericDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupervisorStatsDelegate @Inject constructor(
    private val agentRepository: AgentRepository,
    private val accessControlRepository: AccessControlRepository,
    private val restoreDataUseCase: RestoreDataUseCase
) {
    private val tz = TimeZone.getTimeZone("America/Sao_Paulo")
    private var liveJob: Job? = null

    fun startLiveInspection(
        scope: CoroutineScope,
        state: SupervisorState,
        uid: String,
        filterFutureData: (List<AgentData>) -> List<AgentData>
    ) {
        if (state.focusedAgentUid.value == uid) return
        
        state.focusedAgentUid.value = uid
        liveJob?.cancel()
        liveJob = scope.launch {
            val datePattern = if (state.selectedMonth.value == -1) "-${state.selectedYear.value}" 
                             else String.format("-%02d-%d", state.selectedMonth.value + 1, state.selectedYear.value)
            
            agentRepository.observeAgentProduction(uid, datePattern)
                .collect { data ->
                    state.liveAgentData.value = data?.let { filterFutureData(listOf(it)).firstOrNull() }
                }
        }
    }

    fun stopLiveInspection(state: SupervisorState) {
        state.focusedAgentUid.value = null
        state.liveAgentData.value = null
        liveJob?.cancel()
        liveJob = null
    }

    fun restoreAgentData(
        scope: CoroutineScope,
        state: SupervisorState,
        context: Context,
        agentUid: String,
        fileUri: Uri,
        targetDate: String? = null,
        onRefresh: () -> Unit
    ) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.value = "Permissão negada"
                return@launch
            }
            state.isLoading.value = true
            try {
                val agent = state.rawAgents.value.find { it.uid == agentUid }
                val existingDates = agent?.activities?.map { it.date.replace("/", "-") } ?: emptyList()
                
                val result = restoreDataUseCase(context, agentUid, fileUri, targetDate, existingDates)
                if (result.isSuccess) {
                    state.uiEvent.value = "Dados restaurados com sucesso para o agente selecionado!"
                    onRefresh()
                } else {
                    state.uiEvent.value = "Falha na restauração: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                state.uiEvent.value = "Erro durante restauração: ${e.message}"
            } finally {
                state.isLoading.value = false
            }
        }
    }

    fun calculateAggregateSummary(
        state: SupervisorState,
        agents: List<AgentData>,
        weekStart: Date?,
        weekEnd: Date?
    ): AggregateSummary {
        val startT = weekStart?.time ?: 0L
        val endT = weekEnd?.time ?: Long.MAX_VALUE
        
        val nowCal = Calendar.getInstance(tz)
        val hourOfDay = nowCal.get(Calendar.HOUR_OF_DAY)
        val limitCal = Calendar.getInstance(tz)
        if (hourOfDay < 12) {
            limitCal.add(Calendar.DAY_OF_YEAR, -1)
        }
        val limitNumeric = limitCal.get(Calendar.YEAR).toLong() * 10000 + (limitCal.get(Calendar.MONTH) + 1).toLong() * 100 + limitCal.get(Calendar.DAY_OF_MONTH).toLong()

        val startNumeric = if (weekStart != null) {
            val c = Calendar.getInstance(tz).apply { time = Date(startT) }
            c.get(Calendar.YEAR).toLong() * 10000 + (c.get(Calendar.MONTH) + 1).toLong() * 100 + c.get(Calendar.DAY_OF_MONTH).toLong()
        } else 0L

        val endNumeric = if (weekEnd != null) {
            val c = Calendar.getInstance(tz).apply { time = Date(endT) }
            c.get(Calendar.YEAR).toLong() * 10000 + (c.get(Calendar.MONTH) + 1).toLong() * 100 + c.get(Calendar.DAY_OF_MONTH).toLong()
        } else Long.MAX_VALUE

        val year = state.selectedYear.value
        val month = state.selectedMonth.value

        val selectedMonthStartNumeric = if (month != -1) {
            year.toLong() * 10000 + (month + 1).toLong() * 100 + 1
        } else 0L

        val selectedMonthEndNumeric = if (month != -1) {
            year.toLong() * 10000 + (month + 1).toLong() * 100 + 31
        } else Long.MAX_VALUE

        val selectedYearStartNumeric = year.toLong() * 10000 + 101
        val selectedYearEndNumeric = year.toLong() * 10000 + 1231

        val dateFilter: (String) -> Boolean = { dateStr ->
            val numericDate = dateStr.toNumericDate()
            if (numericDate != null) {
                if (numericDate > limitNumeric) {
                    false
                } else if (weekStart != null && weekEnd != null) {
                    numericDate in startNumeric..endNumeric
                } else if (month != -1) {
                    numericDate in selectedMonthStartNumeric..selectedMonthEndNumeric
                } else {
                    numericDate in selectedYearStartNumeric..selectedYearEndNumeric
                }
            } else false
        }

        var totalWorked = 0
        var totalVisits = 0
        val housesDetails = mutableListOf<StatDetail>()
        val visitsDetails = mutableListOf<StatDetail>()
        var totalFoci = 0
        val fociDetails = mutableListOf<StatDetail>()
        var totalTratados = 0
        val tratadosDetails = mutableListOf<StatDetail>()
        var totalFechados = 0
        val fechadosDetails = mutableListOf<StatDetail>()
        var totalAbandonados = 0
        val abandonadosDetails = mutableListOf<StatDetail>()
        var totalRecusados = 0
        val recusadosDetails = mutableListOf<StatDetail>()
        var activeAgentsCount = 0

        val now = Calendar.getInstance(tz)
        val currentYear = now.get(Calendar.YEAR)
        val currentMonth = now.get(Calendar.MONTH)
        
        agents.forEach { agent ->
            val summary = agent.summary
            val isSummarySafe = if (summary != null) {
                val parts = summary.monthYear.split("-")
                if (parts.size == 2) {
                    val sMonth = parts[0].toInt() - 1
                    val sYear = parts[1].toInt()
                    sYear < currentYear || (sYear == currentYear && sMonth < currentMonth)
                } else {
                    try {
                        val sYear = summary.monthYear.toInt()
                        sYear <= currentYear
                    } catch (e: Exception) {
                        false
                    }
                }
            } else false

            val useSummary = summary != null && (weekStart == null || weekEnd == null) && isSummarySafe
            
            if (useSummary && summary != null) {
                if (summary.totalHouses > 0 || summary.daysWorked > 0) {
                    activeAgentsCount++
                    val displayName = pickBestDisplayName(agent.agentName, agent.email)
                    
                    var visitCount = summary.totalHouses
                    var workedCount = (summary.situationCounts["NONE"] ?: 0) + (summary.situationCounts["EMPTY"] ?: 0)
                    var fCount = summary.focusCount
                    var tCount = summary.treatedCount
                    var fclosedCount = summary.situationCounts["F"] ?: 0
                    var abandonedCount = summary.situationCounts["A"] ?: 0
                    var recusadosCount = summary.situationCounts["REC"] ?: 0
                    var vaziosCount = summary.situationCounts["V"] ?: 0

                    val isCurrentYearSummary = !summary.monthYear.contains("-") && summary.monthYear.toInt() == currentYear
                    if (isCurrentYearSummary) {
                        val currentMonthHouses = agent.houses.filter { dateFilter(it.data) }
                        
                        visitCount += currentMonthHouses.size
                        workedCount += currentMonthHouses.count { 
                            it.situation == com.antigravity.healthagent.data.local.model.Situation.NONE || 
                            it.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY 
                        }
                        fCount += currentMonthHouses.count { it.treatment.comFoco }
                        tCount += currentMonthHouses.count { it.treatment.hasAnyTreatment }
                        fclosedCount += currentMonthHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.F }
                        abandonedCount += currentMonthHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.A }
                        recusadosCount += currentMonthHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.REC }
                        vaziosCount += currentMonthHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.V }
                    }

                    if (visitCount > 0) {
                        totalVisits += visitCount
                        visitsDetails.add(StatDetail(displayName, agent.email, agent.uid, visitCount, agent.photoUrl))
                    }

                    if (workedCount > 0) {
                        totalWorked += workedCount
                        housesDetails.add(StatDetail(displayName, agent.email, agent.uid, workedCount, agent.photoUrl))
                    }
                    
                    if (fCount > 0) {
                        totalFoci += fCount
                        fociDetails.add(StatDetail(displayName, agent.email, agent.uid, fCount, agent.photoUrl))
                    }
                    
                    if (tCount > 0) {
                        totalTratados += tCount
                        tratadosDetails.add(StatDetail(displayName, agent.email, agent.uid, tCount, agent.photoUrl))
                    }

                    if (fclosedCount > 0) {
                        totalFechados += fclosedCount
                        fechadosDetails.add(StatDetail(displayName, agent.email, agent.uid, fclosedCount, agent.photoUrl))
                    }

                    if (abandonedCount > 0) {
                        totalAbandonados += abandonedCount
                        abandonadosDetails.add(StatDetail(displayName, agent.email, agent.uid, abandonedCount, agent.photoUrl))
                    }

                    if (recusadosCount > 0) {
                        totalRecusados += recusadosCount
                        recusadosDetails.add(StatDetail(displayName, agent.email, agent.uid, recusadosCount, agent.photoUrl))
                    }
                }
            } else {
                val periodActivities = agent.activities.filter { dateFilter(it.date) }
                val periodHouses = agent.houses.filter { dateFilter(it.data) }
                
                if (periodActivities.isNotEmpty() || periodHouses.isNotEmpty()) {
                    activeAgentsCount++
                    val displayName = pickBestDisplayName(agent.agentName, agent.email)
                    
                    val visitCount = periodHouses.size
                    if (visitCount > 0) {
                        totalVisits += visitCount
                        visitsDetails.add(StatDetail(displayName, agent.email, agent.uid, visitCount, agent.photoUrl))
                    }

                    val workedCount = periodHouses.count { 
                        it.situation == com.antigravity.healthagent.data.local.model.Situation.NONE || 
                        it.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY 
                    }
                    if (workedCount > 0) {
                        totalWorked += workedCount
                        housesDetails.add(StatDetail(displayName, agent.email, agent.uid, workedCount, agent.photoUrl))
                    }
                    
                    val fociCount = periodHouses.count { it.treatment.comFoco }
                    if (fociCount > 0) {
                        totalFoci += fociCount
                        fociDetails.add(StatDetail(displayName, agent.email, agent.uid, fociCount, agent.photoUrl))
                    }

                    val treatedCount = periodHouses.count { it.treatment.hasAnyTreatment }
                    if (treatedCount > 0) {
                        totalTratados += treatedCount
                        tratadosDetails.add(StatDetail(displayName, agent.email, agent.uid, treatedCount, agent.photoUrl))
                    }

                    val closedCount = periodHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.F }
                    if (closedCount > 0) {
                        totalFechados += closedCount
                        fechadosDetails.add(StatDetail(displayName, agent.email, agent.uid, closedCount, agent.photoUrl))
                    }

                    val abandonedCount = periodHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.A }
                    if (abandonedCount > 0) {
                        totalAbandonados += abandonedCount
                        abandonadosDetails.add(StatDetail(displayName, agent.email, agent.uid, abandonedCount, agent.photoUrl))
                    }

                    val recusedCount = periodHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.REC }
                    if (recusedCount > 0) {
                        totalRecusados += recusedCount
                        recusadosDetails.add(StatDetail(displayName, agent.email, agent.uid, recusedCount, agent.photoUrl))
                    }
                }
            }
        }

        return AggregateSummary(
            totalWorked = totalWorked,
            housesDetails = housesDetails.sortedByDescending { it.count },
            totalVisits = totalVisits,
            visitsDetails = visitsDetails.sortedByDescending { it.count },
            totalFoci = totalFoci,
            fociDetails = fociDetails.sortedByDescending { it.count },
            totalTratados = totalTratados,
            tratadosDetails = tratadosDetails.sortedByDescending { it.count },
            totalFechados = totalFechados,
            fechadosDetails = fechadosDetails.sortedByDescending { it.count },
            totalAbandonados = totalAbandonados,
            abandonadosDetails = abandonadosDetails.sortedByDescending { it.count },
            totalRecusados = totalRecusados,
            recusadosDetails = recusadosDetails.sortedByDescending { it.count },
            activeAgents = activeAgentsCount,
            totalAgents = agents.size
        )
    }

    private fun pickBestDisplayName(name: String?, email: String): String {
        val n = name?.trim() ?: ""
        return if (n.isBlank() || n.contains("@")) {
            email.substringBefore("@").uppercase()
        } else {
            n.uppercase()
        }
    }
}
