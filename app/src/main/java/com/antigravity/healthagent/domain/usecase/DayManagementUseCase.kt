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

    suspend fun getDayActivity(date: String, agentName: String, agentUid: String? = null): DayActivity? {
        return repository.getDayActivity(date.replace("/", "-"), agentName.uppercase(), agentUid)
    }

    suspend fun unlockDay(originalDate: String, agentName: String, agentUid: String? = null, force: Boolean = false) {
        val date = originalDate.replace("/", "-")
        val upperName = agentName.uppercase()
        val activity = repository.getDayActivity(date, upperName, agentUid)
        
        // Use copy to preserve metadata (lastUpdated, isSynced, isManualUnlock history)
        // Set isClosed = false AND isManualUnlock = true
        val newActivity = activity?.copy(
            isClosed = false,
            isManualUnlock = true,
            agentName = upperName,
            agentUid = agentUid ?: ""
        ) ?: DayActivity(
            date = date,
            status = "NORMAL",
            isClosed = false,
            isManualUnlock = true,
            agentName = upperName,
            agentUid = agentUid ?: ""
        )
        
        repository.updateDayActivity(newActivity, force)
    }


    suspend fun closeDay(originalDate: String, agentName: String, agentUid: String? = null, force: Boolean = false) {
        val date = originalDate.replace("/", "-")
        val upperName = agentName.uppercase()
        val activity = repository.getDayActivity(date, upperName, agentUid) ?: DayActivity(date, "NORMAL", false, false, upperName, agentUid ?: "")
        // When closing, reset isManualUnlock to false
        repository.updateDayActivity(activity.copy(isClosed = true, isManualUnlock = false, agentName = upperName, agentUid = agentUid ?: ""), force)
    }

    fun isDateLocked(activity: DayActivity?): Boolean {
        return activity?.isClosed == true
    }

    suspend fun canSafelyUnlock(currentDate: String, agentName: String, agentUid: String? = null, isAdmin: Boolean = false): Boolean {
        try {
            // Admins can ALWAYS unlock without confirmation
            if (isAdmin) return true
            
            val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
            val todayStr = sdf.format(Date())
            
            // 1. Today is always safe
            if (currentDate == todayStr) return true
            
            // 2. The last workday is also safe (silent unlock)
            val lastWorkday = getPreviousWorkDay(agentName, agentUid)
            if (currentDate == lastWorkday) return true
            
        } catch (e: Exception) {
            // Ignore parse errors
        }
        return false
    }

    suspend fun getPreviousWorkDay(agentName: String, agentUid: String?): String? {
        return try {
            val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
            val todayStr = sdf.format(Date())
            val todayObj = sdf.parse(todayStr) ?: return null
            
            val activities = repository.getAllDayActivitiesOnce(agentName, agentUid ?: "")
            
            // Find the most recent activity that is BEFORE today
            activities
                .mapNotNull { activity ->
                    try {
                        // FIX: Normalize date to prevent parsing failure if it contains '/'
                        val normalizedDate = activity.date.replace("/", "-")
                        val dateObj = sdf.parse(normalizedDate)
                        if (dateObj != null && dateObj.before(todayObj)) {
                            dateObj to normalizedDate
                        } else null
                    } catch (e: Exception) { null }
                }
                .maxByOrNull { it.first.time }
                ?.second
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getNextBusinessDay(date: String, agentName: String, agentUid: String? = null): String {
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
                val activity = repository.getDayActivity(nextDateStr, agentName.uppercase(), agentUid)
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
