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
