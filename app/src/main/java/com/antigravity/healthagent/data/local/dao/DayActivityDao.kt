package com.antigravity.healthagent.data.local.dao

import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow
import androidx.room.*

@Dao
interface DayActivityDao {
    @Query("SELECT * FROM day_activities WHERE REPLACE(date, '/', '-') = REPLACE(:date, '/', '-') AND agentUid = :agentUid")
    suspend fun getDayActivity(date: String, agentUid: String? = ""): DayActivity?

    @Query("SELECT * FROM day_activities WHERE REPLACE(date, '/', '-') IN (:dates) AND agentUid = :agentUid")
    fun getDayActivities(dates: List<String>, agentUid: String? = ""): Flow<List<DayActivity>>

    @Query("SELECT * FROM day_activities WHERE REPLACE(date, '/', '-') = REPLACE(:date, '/', '-') AND agentUid = :agentUid")
    fun getDayActivityFlow(date: String, agentUid: String? = ""): Flow<DayActivity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDayActivity(dayActivity: DayActivity)

    @Delete
    suspend fun delete(activity: DayActivity)

    @Query("SELECT * FROM day_activities")
    suspend fun getAllDayActivitiesSnapshot(): List<DayActivity>

    @Query("SELECT * FROM day_activities")
    fun getAllDayActivitiesFlow(): Flow<List<DayActivity>>

    @Query("SELECT * FROM day_activities WHERE agentUid = :agentUid")
    suspend fun getDayActivitiesByAgentSnapshot(agentUid: String): List<DayActivity>

    @Query("SELECT COUNT(*) FROM day_activities")
    suspend fun count(): Int

    @Query("SELECT * FROM day_activities WHERE agentUid = :agentUid")
    suspend fun getAllDayActivities(agentUid: String): List<DayActivity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(activities: List<DayActivity>)

    @Query("SELECT * FROM day_activities WHERE isSynced = 0 AND agentUid = :agentUid")
    suspend fun getUnsyncedActivities(agentUid: String): List<DayActivity>

    @Query("UPDATE OR REPLACE day_activities SET isSynced = 1, agentUid = :agentUid, agentName = :agentName WHERE REPLACE(date, '/', '-') = REPLACE(:date, '/', '-') AND agentUid = :agentUid AND lastUpdated = :timestamp")
    suspend fun markAsSyncedWithTimestamp(date: String, agentName: String, agentUid: String, timestamp: Long)

    @Query("DELETE FROM day_activities WHERE REPLACE(date, '/', '-') = REPLACE(:date, '/', '-') AND agentUid = :agentUid")
    suspend fun deleteDayActivity(date: String, agentUid: String)

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

    @Query("DELETE FROM day_activities WHERE agentUid = :agentUid")
    suspend fun deleteByAgent(agentUid: String)

    @Query("DELETE FROM day_activities WHERE agentUid = :agentUid AND REPLACE(date, '/', '-') IN (:dates) AND isSynced = 1")
    suspend fun deleteByAgentAndDates(agentUid: String, dates: List<String>)

    @Query("SELECT COUNT(*) FROM day_activities WHERE isClosed = 0 AND agentUid = :agentUid")
    suspend fun countOpenDays(agentUid: String): Int

    @Query("UPDATE OR REPLACE day_activities SET isClosed = 1, isManualUnlock = 0, isSynced = 0, lastUpdated = :now WHERE agentUid = :agentUid")
    suspend fun closeAllActivities(agentUid: String, now: Long = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis())

    @Query("UPDATE OR REPLACE day_activities SET agentName = :newName, isSynced = 0, lastUpdated = :now WHERE agentUid = :agentUid")
    suspend fun updateAgentNameForAll(newName: String, agentUid: String, now: Long = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis())

    @Query("UPDATE OR REPLACE day_activities SET agentName = :properName, isSynced = 0, lastUpdated = :now WHERE agentUid = :uid AND agentName LIKE '%@%'")
    suspend fun fixEmailNamesForUid(uid: String, properName: String, now: Long = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis())

    @Query("SELECT * FROM day_activities WHERE agentUid = '' OR agentUid IS NULL")
    suspend fun getAllOrphanActivities(): List<DayActivity>

    @Query("SELECT * FROM day_activities WHERE agentUid != :targetUid AND (UPPER(agentName) = UPPER(:email) OR UPPER(agentName) = UPPER(:prefix) OR UPPER(agentName) = UPPER(:properName) OR (agentUid = '' AND UPPER(agentName) = 'AGENTE'))")
    suspend fun getActivitiesToReclaim(email: String, prefix: String, targetUid: String, properName: String): List<DayActivity>

    @Query("UPDATE OR REPLACE day_activities SET agentUid = :targetUid, isSynced = 0, lastUpdated = :now WHERE agentUid != :targetUid AND (UPPER(agentName) = UPPER(:agentName) OR UPPER(agentName) = UPPER(:email) OR UPPER(agentName) = UPPER(:emailPrefix))")
    suspend fun reclaimActivities(agentName: String, email: String, emailPrefix: String, targetUid: String, now: Long = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis())

    @Query("SELECT * FROM day_activities WHERE agentUid = :agentUid AND REPLACE(date, '/', '-') LIKE '%-' || :monthYearSuffix")
    suspend fun getDayActivitiesByMonth(agentUid: String, monthYearSuffix: String): List<DayActivity>
}
