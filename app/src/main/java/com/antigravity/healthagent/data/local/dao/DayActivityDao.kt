package com.antigravity.healthagent.data.local.dao

import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow
import androidx.room.*

@Dao
interface DayActivityDao {
    @Query("SELECT * FROM day_activities WHERE date = :date AND UPPER(agentName) = UPPER(:agentName)")
    suspend fun getDayActivity(date: String, agentName: String): DayActivity?

    @Query("SELECT * FROM day_activities WHERE date IN (:dates) AND UPPER(agentName) = UPPER(:agentName)")
    fun getDayActivities(dates: List<String>, agentName: String): Flow<List<DayActivity>>

    @Query("SELECT * FROM day_activities WHERE date = :date AND UPPER(agentName) = UPPER(:agentName)")
    fun getDayActivityFlow(date: String, agentName: String): Flow<DayActivity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDayActivity(dayActivity: DayActivity)

    @Query("SELECT * FROM day_activities")
    suspend fun getAllDayActivities(): List<DayActivity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(activities: List<DayActivity>)

    @Query("DELETE FROM day_activities WHERE date = :date AND UPPER(agentName) = UPPER(:agentName)")
    suspend fun deleteDayActivity(date: String, agentName: String)

    @Transaction
    suspend fun upsertDayActivities(activities: List<DayActivity>) {
        // We use Upsert (insertAll with REPLACE) to update cloud data locally 
        // without deleting local records that haven't been synced yet.
        insertAll(activities)
    }

    @Transaction
    suspend fun replaceDayActivities(activities: List<DayActivity>) {
        deleteAll()
        insertAll(activities)
    }

    @Query("DELETE FROM day_activities")
    suspend fun deleteAll()

    @Query("DELETE FROM day_activities WHERE UPPER(agentName) = UPPER(:agentName)")
    suspend fun deleteByAgent(agentName: String)

    @Query("DELETE FROM day_activities WHERE UPPER(agentName) = UPPER(:agentName) AND date IN (:dates)")
    suspend fun deleteByAgentAndDates(agentName: String, dates: List<String>)

    @Query("SELECT COUNT(*) FROM day_activities WHERE isClosed = 0 AND UPPER(agentName) = UPPER(:agentName)")
    suspend fun countOpenDays(agentName: String): Int

    @Query("UPDATE day_activities SET isClosed = 1 WHERE UPPER(agentName) = UPPER(:agentName)")
    suspend fun closeAllActivities(agentName: String)
}
