package com.antigravity.healthagent.data.local.dao

import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow
import androidx.room.*

@Dao
interface DayActivityDao {
    @Query("SELECT * FROM day_activities WHERE REPLACE(date, '/', '-') = REPLACE(:date, '/', '-') AND (UPPER(agentName) = UPPER(:agentName) OR (agentUid != '' AND agentUid = :agentUid))")
    suspend fun getDayActivity(date: String, agentName: String, agentUid: String? = ""): DayActivity?

    @Query("SELECT * FROM day_activities WHERE REPLACE(date, '/', '-') IN (:dates) AND (UPPER(agentName) = UPPER(:agentName) OR (agentUid != '' AND agentUid = :agentUid))")
    fun getDayActivities(dates: List<String>, agentName: String, agentUid: String? = ""): Flow<List<DayActivity>>

    @Query("SELECT * FROM day_activities WHERE REPLACE(date, '/', '-') = REPLACE(:date, '/', '-') AND (UPPER(agentName) = UPPER(:agentName) OR (agentUid != '' AND agentUid = :agentUid))")
    fun getDayActivityFlow(date: String, agentName: String, agentUid: String? = ""): Flow<DayActivity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDayActivity(dayActivity: DayActivity)

    @Query("SELECT * FROM day_activities")
    fun getAllDayActivitiesSnapshot(): List<DayActivity>

    @Query("SELECT COUNT(*) FROM day_activities")
    suspend fun count(): Int

    @Query("SELECT * FROM day_activities WHERE ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName)))")
    suspend fun getAllDayActivities(agentName: String, agentUid: String): List<DayActivity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(activities: List<DayActivity>)

    @Query("SELECT * FROM day_activities WHERE isSynced = 0 AND ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName)))")
    suspend fun getUnsyncedActivities(agentName: String, agentUid: String): List<DayActivity>

    @Query("UPDATE day_activities SET isSynced = 1 WHERE REPLACE(date, '/', '-') = REPLACE(:date, '/', '-') AND ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName))) AND lastUpdated = :timestamp")
    suspend fun markAsSyncedWithTimestamp(date: String, agentName: String, agentUid: String, timestamp: Long)

    @Query("DELETE FROM day_activities WHERE REPLACE(date, '/', '-') = REPLACE(:date, '/', '-') AND ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName)))")
    suspend fun deleteDayActivity(date: String, agentName: String, agentUid: String)

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

    @Query("DELETE FROM day_activities WHERE (agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName))")
    suspend fun deleteByAgent(agentName: String, agentUid: String)

    @Query("DELETE FROM day_activities WHERE ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName))) AND REPLACE(date, '/', '-') IN (:dates)")
    suspend fun deleteByAgentAndDates(agentName: String, agentUid: String, dates: List<String>)

    @Query("SELECT COUNT(*) FROM day_activities WHERE isClosed = 0 AND ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName)))")
    suspend fun countOpenDays(agentName: String, agentUid: String): Int

    @Query("UPDATE day_activities SET isClosed = 1, isManualUnlock = 0, isSynced = 0, lastUpdated = :now WHERE ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:agentName)))")
    suspend fun closeAllActivities(agentName: String, agentUid: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE day_activities SET agentName = :newName, isSynced = 0, lastUpdated = :now WHERE ((agentUid != '' AND agentUid = :agentUid) OR (agentUid = '' AND UPPER(agentName) = UPPER(:oldName)))")
    suspend fun updateAgentNameForAll(oldName: String, newName: String, agentUid: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM day_activities WHERE agentUid = '' OR agentUid IS NULL")
    suspend fun getAllOrphanActivities(): List<DayActivity>

    @Query("UPDATE day_activities SET agentUid = :targetUid, isSynced = 0, lastUpdated = :now WHERE agentUid = '' AND (UPPER(agentName) = UPPER(:agentName) OR UPPER(agentName) = UPPER(:email) OR UPPER(agentName) = UPPER(:emailPrefix))")
    suspend fun updateAgentUidForAll(agentName: String, email: String, emailPrefix: String, targetUid: String, now: Long = System.currentTimeMillis())
}
