package com.antigravity.healthagent.data.local.dao

import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow
import androidx.room.*

@Dao
interface DayActivityDao {
    @Query("SELECT * FROM day_activities WHERE date = :date AND (UPPER(agentName) = UPPER(:agentName) OR (agentUid != '' AND agentUid = :agentUid))")
    suspend fun getDayActivity(date: String, agentName: String, agentUid: String? = ""): DayActivity?

    @Query("SELECT * FROM day_activities WHERE date IN (:dates) AND (UPPER(agentName) = UPPER(:agentName) OR (agentUid != '' AND agentUid = :agentUid))")
    fun getDayActivities(dates: List<String>, agentName: String, agentUid: String? = ""): Flow<List<DayActivity>>

    @Query("SELECT * FROM day_activities WHERE date = :date AND (UPPER(agentName) = UPPER(:agentName) OR (agentUid != '' AND agentUid = :agentUid))")
    fun getDayActivityFlow(date: String, agentName: String, agentUid: String? = ""): Flow<DayActivity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDayActivity(dayActivity: DayActivity)

    @Query("SELECT * FROM day_activities")
    suspend fun getAllDayActivities(): List<DayActivity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(activities: List<DayActivity>)

    @Query("SELECT * FROM day_activities WHERE isSynced = 0")
    suspend fun getUnsyncedActivities(): List<DayActivity>

    @Query("UPDATE day_activities SET isSynced = 1 WHERE date IN (:dates) AND UPPER(agentName) = UPPER(:agentName)")
    suspend fun markAsSynced(dates: List<String>, agentName: String)

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

    @Query("UPDATE day_activities SET isClosed = 1, isSynced = 0, lastUpdated = :now WHERE UPPER(agentName) = UPPER(:agentName)")
    suspend fun closeAllActivities(agentName: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE day_activities SET agentName = :newName WHERE agentName = :oldName")
    suspend fun updateAgentNameForAll(oldName: String, newName: String)
}
