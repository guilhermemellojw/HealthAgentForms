package com.antigravity.healthagent.ui

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AgentSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SupervisorWeeklyDataTest {

    private val tz = TimeZone.getTimeZone("America/Sao_Paulo")

    @Test
    fun testGenerateWeeksForMonth() {
        val year = 2026
        val month = 4 // May (0-based: 0=Jan, 1=Feb, 2=Mar, 3=Apr, 4=May)
        
        val weeks = generateWeeksForMonth(year, month)
        println("Generated weeks for May 2026:")
        weeks.forEach {
            println("${it.label} -> start: ${it.start} (${it.start.time}), end: ${it.end} (${it.end.time})")
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = tz }
        
        assertEquals("Semana 1 (26/04 - 02/05)", weeks[0].label)
        assertEquals("2026-04-26 00:00:00", sdf.format(weeks[0].start))
        assertEquals("2026-05-02 23:59:59", sdf.format(weeks[0].end))
    }

    @Test
    fun testGetDateStringsInRange() {
        val startT = 1777172400000L // April 26 2026 00:00:00 America/Sao_Paulo
        val endT = 1777777199000L   // May 2 2026 23:59:59 America/Sao_Paulo

        val dates = getDateStringsInRange(startT, endT)
        println("Date strings in range:")
        println(dates)

        val expected = listOf(
            "26-04-2026",
            "27-04-2026",
            "28-04-2026",
            "29-04-2026",
            "30-04-2026",
            "01-05-2026",
            "02-05-2026"
        )
        assertEquals(expected, dates)
    }

    @Test
    fun testCalculateAggregateSummary_WeeklyRawFallback() {
        // May 1st 2026 is in the week range Sunday April 26 to Saturday May 2.
        // We set the weekRange from April 26 to May 2.
        val sdf = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).apply { timeZone = tz }
        val weekStart = sdf.parse("26-04-2026 00:00:00")
        val weekEnd = sdf.parse("02-05-2026 23:59:59")

        val houses = listOf(
            House(
                id = 1,
                data = "01-05-2026", // Inside the week range, 12:00 embargo safe (since limitNumeric is inside future in our mock limit)
                situation = Situation.NONE,
                treatment = TreatmentData(comFoco = true, eliminados = 2),
                agentUid = "uid_1"
            ),
            House(
                id = 2,
                data = "25-04-2026", // Outside week range (before)
                situation = Situation.NONE,
                agentUid = "uid_1"
            ),
            House(
                id = 3,
                data = "03-05-2026", // Outside week range (after)
                situation = Situation.NONE,
                agentUid = "uid_1"
            )
        )

        val agentData = listOf(
            AgentData(
                uid = "uid_1",
                email = "agent@gov.br",
                agentName = "Agent 1",
                houses = houses,
                activities = emptyList(),
                summary = null
            )
        )

        val result = calculateAggregateSummary(agentData, weekStart, weekEnd, selectedMonth = 4, selectedYear = 2026)
        println("Aggregate summary results:")
        println("Visits: ${result.totalVisits}")
        println("Worked: ${result.totalWorked}")
        println("Foci: ${result.totalFoci}")

        assertEquals(1, result.totalVisits)
        assertEquals(1, result.totalWorked)
        assertEquals(1, result.totalFoci)
    }

    data class WeekRange(val label: String, val start: Date, val end: Date)

    data class AggregateSummary(
        val totalWorked: Int = 0,
        val totalVisits: Int = 0,
        val housesDetails: List<StatDetail> = emptyList(),
        val visitsDetails: List<StatDetail> = emptyList(),
        val totalFoci: Int = 0,
        val fociDetails: List<StatDetail> = emptyList(),
        val totalTratados: Int = 0,
        val tratadosDetails: List<StatDetail> = emptyList(),
        val totalFechados: Int = 0,
        val fechadosDetails: List<StatDetail> = emptyList(),
        val totalAbandonados: Int = 0,
        val abandonadosDetails: List<StatDetail> = emptyList(),
        val totalRecusados: Int = 0,
        val recusadosDetails: List<StatDetail> = emptyList(),
        val activeAgentsCount: Int = 0
    )

    data class StatDetail(
        val agentName: String,
        val email: String,
        val uid: String,
        val value: Int,
        val photoUrl: String? = null
    )

    private fun String.toNumericDate(): Long? {
        if (this.isBlank()) return null
        val clean = this.replace("/", "-").trim()
        val parts = clean.split("-")
        if (parts.size != 3) return null
        return try {
            val part0 = parts[0].toInt()
            val part1 = parts[1].toInt()
            val part2 = parts[2].toInt()
            if (part0 > 1000) {
                part0.toLong() * 10000 + part1.toLong() * 100 + part2.toLong()
            } else if (part2 > 1000) {
                part2.toLong() * 10000 + part1.toLong() * 100 + part0.toLong()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateAggregateSummary(agents: List<AgentData>, weekStart: Date?, weekEnd: Date?, selectedMonth: Int, selectedYear: Int): AggregateSummary {
        val startT = weekStart?.time ?: 0L
        val endT = weekEnd?.time ?: Long.MAX_VALUE
        
        // Mock embargo to future to keep all mock houses safe
        val limitNumeric = 20261231L

        val startNumeric = if (weekStart != null) {
            val c = Calendar.getInstance(tz).apply { time = Date(startT) }
            c.get(Calendar.YEAR).toLong() * 10000 + (c.get(Calendar.MONTH) + 1).toLong() * 100 + c.get(Calendar.DAY_OF_MONTH).toLong()
        } else 0L

        val endNumeric = if (weekEnd != null) {
            val c = Calendar.getInstance(tz).apply { time = Date(endT) }
            c.get(Calendar.YEAR).toLong() * 10000 + (c.get(Calendar.MONTH) + 1).toLong() * 100 + c.get(Calendar.DAY_OF_MONTH).toLong()
        } else Long.MAX_VALUE

        val year = selectedYear
        val month = selectedMonth

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
        var totalFoci = 0
        var totalFechados = 0
        var activeAgentsCount = 0

        val currentYear = 2026
        val currentMonth = 4
        
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
                    
                    var visitCount = summary.totalHouses
                    var workedCount = (summary.situationCounts["NONE"] ?: 0) + (summary.situationCounts["EMPTY"] ?: 0)
                    var fCount = summary.focusCount
                    var fclosedCount = summary.situationCounts["F"] ?: 0

                    val isCurrentYearSummary = !summary.monthYear.contains("-") && summary.monthYear.toInt() == currentYear
                    if (isCurrentYearSummary) {
                        val currentMonthHouses = agent.houses.filter { dateFilter(it.data) }
                        visitCount += currentMonthHouses.size
                        workedCount += currentMonthHouses.count { 
                            it.situation == Situation.NONE || 
                            it.situation == Situation.EMPTY 
                        }
                        fCount += currentMonthHouses.count { it.treatment.comFoco }
                        fclosedCount += currentMonthHouses.count { it.situation == Situation.F }
                    }

                    if (visitCount > 0) totalVisits += visitCount
                    if (workedCount > 0) totalWorked += workedCount
                    if (fCount > 0) totalFoci += fCount
                    if (fclosedCount > 0) totalFechados += fclosedCount
                }
            } else {
                val periodHouses = agent.houses.filter { dateFilter(it.data) }
                
                if (periodHouses.isNotEmpty()) {
                    activeAgentsCount++
                    
                    val visitCount = periodHouses.size
                    if (visitCount > 0) {
                        totalVisits += visitCount
                    }

                    val workedCount = periodHouses.count { 
                        it.situation == Situation.NONE || 
                        it.situation == Situation.EMPTY 
                    }
                    if (workedCount > 0) {
                        totalWorked += workedCount
                    }
                    
                    val fociCount = periodHouses.count { it.treatment.comFoco }
                    if (fociCount > 0) {
                        totalFoci += fociCount
                    }

                    val closedCount = periodHouses.count { it.situation == Situation.F }
                    if (closedCount > 0) {
                        totalFechados += closedCount
                    }
                }
            }
        }
        return AggregateSummary(
            totalWorked = totalWorked,
            totalVisits = totalVisits,
            totalFoci = totalFoci,
            totalFechados = totalFechados,
            activeAgentsCount = activeAgentsCount
        )
    }

    private fun generateWeeksForMonth(year: Int, month: Int): List<WeekRange> {
        val weeks = mutableListOf<WeekRange>()
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

        val sdf = SimpleDateFormat("dd/MM", Locale.US).apply { timeZone = tz }
        val now = 1800000000000L 

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
            
            weeks.add(WeekRange("Semana $weekNum (${sdf.format(start)} - ${sdf.format(weekEnd.time)})", start, weekEnd.time))
            
            cal.time = weekEnd.time
            cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            
            weekNum++
        }
        return weeks
    }

    private fun getDateStringsInRange(start: Long, end: Long): List<String> {
        val dates = mutableListOf<String>()
        val tz = TimeZone.getTimeZone("America/Sao_Paulo")
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = start
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US).apply { timeZone = tz }
        
        var count = 0
        while (cal.timeInMillis <= end && count < 31) {
            dates.add(sdf.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, 1)
            count++
        }
        return dates
    }

    @Test
    fun testCalculateAggregateSummary_CurrentYearRecalculation() {
        val summary = AgentSummary(
            monthYear = "2026", // Yearly summary
            treatedCount = 10,   // past months treated
            focusCount = 2,      // past months focus
            situationCounts = mapOf("NONE" to 10, "F" to 3, "REC" to 1),
            propertyTypeCounts = emptyMap(),
            totalHouses = 14,
            daysWorked = 4,
            lastUpdated = 0L
        )

        // Raw houses of the current month (May 2026)
        val currentMonthHouses = listOf(
            House(
                id = 100,
                data = "15-05-2026",
                situation = Situation.NONE, // worked
                treatment = TreatmentData(comFoco = true),
                agentUid = "uid_1"
            ),
            House(
                id = 101,
                data = "16-05-2026",
                situation = Situation.F, // closed
                agentUid = "uid_1"
            )
        )

        val agentData = listOf(
            AgentData(
                uid = "uid_1",
                email = "agent@gov.br",
                agentName = "Agent 1",
                houses = currentMonthHouses,
                activities = emptyList(),
                summary = summary
            )
        )

        // Running calculateAggregateSummary for "Ano Todo" (month == -1)
        val result = calculateAggregateSummary(agentData, weekStart = null, weekEnd = null, selectedMonth = -1, selectedYear = 2026)

        println("Yearly Consolidated Results (Precalculated + Raw Recalculated):")
        println("Visits: ${result.totalVisits}")
        println("Worked: ${result.totalWorked}")
        println("Foci: ${result.totalFoci}")
        println("Fechados: ${result.totalFechados}")

        // Expected visits: summary.totalHouses (14) + raw currentMonthHouses.size (2) = 16
        assertEquals(16, result.totalVisits)
        // Expected worked: summary worked (10) + raw NONE (1) = 11
        assertEquals(11, result.totalWorked)
        // Expected foci: summary focus (2) + raw withFoco (1) = 3
        assertEquals(3, result.totalFoci)
        // Expected closed: summary closed (3) + raw closed (1) = 4
        assertEquals(4, result.totalFechados)
    }
}
