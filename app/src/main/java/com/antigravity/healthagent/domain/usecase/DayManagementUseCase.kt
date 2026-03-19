package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.repository.HouseRepository
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Calendar
import java.util.Date

class DayManagementUseCase @Inject constructor(
    private val repository: HouseRepository
) {

    suspend fun getDayActivity(date: String, agentName: String): DayActivity? {
        return repository.getDayActivity(date, agentName.uppercase())
    }

    suspend fun unlockDay(date: String, agentName: String) {
        val upperName = agentName.uppercase()
        val activity = repository.getDayActivity(date, upperName)
        val status = activity?.status?.takeIf { it.isNotBlank() } ?: "NORMAL"
        repository.updateDayActivity(DayActivity(date, status, false, upperName))
    }

    suspend fun closeDay(date: String, agentName: String) {
        val upperName = agentName.uppercase()
        val activity = repository.getDayActivity(date, upperName) ?: DayActivity(date, "NORMAL", false, upperName)
        repository.updateDayActivity(activity.copy(isClosed = true, agentName = upperName))
    }

    fun isDateLocked(activity: DayActivity?): Boolean {
        return activity?.isClosed == true
    }

    fun canSafelyUnlock(currentDate: String): Boolean {
        try {
            val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
            val dateObj = sdf.parse(currentDate)
            
            if (dateObj != null) {
                val todayStr = sdf.format(Date())
                // If it's not today, we might warn used (handled by ViewModel via return val?) 
                // Usecase just answers if it's "safe" without warning.
                // Logic in VM was: if (currentDate != todayStr) showWarning
                return currentDate == todayStr
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
        return true
    }

    suspend fun getNextBusinessDay(date: String, agentName: String): String {
        return try {
            val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
            val cal = Calendar.getInstance()
            val dateObj = sdf.parse(date) ?: return ""
            cal.time = dateObj
            
            var attempts = 0
            do {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                attempts++
                
                // Skip weekends
                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                if (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY) {
                    continue
                }
                
                // Check if this day has a non-NORMAL status
                val nextDateStr = sdf.format(cal.time)
                val activity = repository.getDayActivity(nextDateStr, agentName.uppercase())
                val status = activity?.status ?: "NORMAL"
                
                // If status is NORMAL or blank, this is a valid work day
                if (status.isBlank() || status == "NORMAL") {
                    return nextDateStr
                }
                
                // Otherwise, skip this day (FERIADO, TEMPO CHUVOSO, etc.)
            } while (attempts < 30) // Safety limit to prevent infinite loop
            
            sdf.format(cal.time)
        } catch (e: Exception) {
            ""
        }
    }
}
