package com.antigravity.healthagent.ui.supervisor.delegates

import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.ui.supervisor.SupervisorViewModel
import com.antigravity.healthagent.utils.toNumericDate

import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupervisorFilterDelegate @Inject constructor() {
    private val tz = TimeZone.getTimeZone("America/Sao_Paulo")
    private val availableMonths = listOf("Ano Todo", "Jan", "Fev", "Mar", "Abr", "Mai", "Jun", "Jul", "Ago", "Set", "Out", "Nov", "Dez")

    fun toggleAgentExpanded(state: SupervisorState, uid: String) {
        val current = state.expandedUids.value
        state.expandedUids.value = if (current.contains(uid)) current - uid else current + uid
    }

    fun updateSearchQuery(state: SupervisorState, query: String) {
        state.searchQuery.value = query
    }

    fun getFilteredMonths(state: SupervisorState): List<String> {
        val currentYear = Calendar.getInstance(tz).get(Calendar.YEAR)
        val currentMonth = Calendar.getInstance(tz).get(Calendar.MONTH)
        
        return if (state.selectedYear.value >= currentYear) {
            availableMonths.take(currentMonth + 2)
        } else {
            availableMonths
        }
    }

    fun updateYear(state: SupervisorState, year: Int, onRefresh: () -> Unit) {
        state.selectedYear.value = year
        val currentYear = Calendar.getInstance(tz).get(Calendar.YEAR)
        val currentMonth = Calendar.getInstance(tz).get(Calendar.MONTH)
        if (year == currentYear && state.selectedMonth.value > currentMonth) {
            state.selectedMonth.value = currentMonth
        }
        state.selectedWeekIndex.value = -1
        onRefresh()
    }

    fun updateMonth(state: SupervisorState, monthIndex: Int, onRefresh: () -> Unit) {
        state.selectedMonth.value = monthIndex
        state.selectedWeekIndex.value = -1
        onRefresh()
    }

    fun updateWeek(state: SupervisorState, weekIndex: Int, onRefresh: () -> Unit) {
        state.selectedWeekIndex.value = weekIndex
        onRefresh()
    }

    fun nextWeek(state: SupervisorState) {
        val cal = Calendar.getInstance(tz).apply {
            time = state.currentWeekStart.value
            add(Calendar.DAY_OF_YEAR, 7)
        }
        state.currentWeekStart.value = cal.time
    }

    fun previousWeek(state: SupervisorState) {
        val cal = Calendar.getInstance(tz).apply {
            time = state.currentWeekStart.value
            add(Calendar.DAY_OF_YEAR, -7)
        }
        state.currentWeekStart.value = cal.time
    }

    fun generateWeeksForMonth(year: Int, month: Int): List<SupervisorViewModel.WeekRange> {
        val weeks = mutableListOf<SupervisorViewModel.WeekRange>()
        val cal = Calendar.getInstance(tz)
        cal.set(year, month, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        
        val maxDayOfMonth = Calendar.getInstance(tz).apply { set(year, month, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
        val endOfMonth = Calendar.getInstance(tz).apply {
            set(year, month, maxDayOfMonth, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }

        val sdf = com.antigravity.healthagent.utils.DateUtils.SLASH_DATE.get().apply { timeZone = tz }
        val now = Calendar.getInstance(tz).timeInMillis

        var weekNum = 1
        while (cal.timeInMillis <= endOfMonth.timeInMillis) {
            val start = cal.time
            if (start.time > now) break
            
            val weekEnd = Calendar.getInstance(tz).apply {
                time = start
                add(Calendar.DAY_OF_MONTH, 6)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
            }
            
            weeks.add(SupervisorViewModel.WeekRange("Semana $weekNum (${sdf.format(start)} - ${sdf.format(weekEnd.time)})", start, weekEnd.time))
            
            cal.time = weekEnd.time
            cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            
            weekNum++
        }
        return weeks
    }

    fun filterFutureData(agents: List<AgentData>): List<AgentData> {
        val cal = Calendar.getInstance(tz)
        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
        
        val filterCal = Calendar.getInstance(tz)
        if (hourOfDay < 12) {
            filterCal.add(Calendar.DAY_OF_YEAR, -1)
        }
        
        val limitStr = com.antigravity.healthagent.utils.DateUtils.COMPACT_DATE.get().apply { timeZone = tz }.format(filterCal.time)
        val limitInt = limitStr.toInt()

        return agents.map { agent ->
            val filteredHouses = agent.houses.filter { house ->
                val numericDate = house.data.toNumericDate()
                numericDate != null && numericDate <= limitInt
            }
            
            val filteredActivities = agent.activities.filter { activity ->
                val numericDate = activity.date.toNumericDate()
                numericDate != null && numericDate <= limitInt
            }
            
            val now = Calendar.getInstance(tz)
            val cMonth = now.get(Calendar.MONTH) + 1
            val cYear = now.get(Calendar.YEAR)
            
            val updatedSummary = agent.summary?.let { s ->
                val parts = s.monthYear.split("-")
                if (parts.size == 2) {
                    val sMonth = parts[0].toInt()
                    val sYear = parts[1].toInt()
                    val isSafe = sYear < cYear || (sYear == cYear && sMonth < cMonth)
                    if (isSafe) s else null
                } else {
                    try {
                        val sYear = s.monthYear.toInt()
                        if (sYear <= cYear) s else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            agent.copy(houses = filteredHouses, activities = filteredActivities, summary = updatedSummary)
        }
    }
}
