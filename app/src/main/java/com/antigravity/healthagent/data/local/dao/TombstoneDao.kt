package com.antigravity.healthagent.data.local.dao

import androidx.room.*
import com.antigravity.healthagent.data.local.model.Tombstone
import com.antigravity.healthagent.data.local.model.TombstoneType

@Dao
interface TombstoneDao {
    @Query("SELECT * FROM tombstones WHERE agentUid = :agentUid")
    suspend fun getAllTombstones(agentUid: String): List<Tombstone>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: Tombstone)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstones(tombstones: List<Tombstone>)

    @Query("DELETE FROM tombstones WHERE id IN (:ids)")
    suspend fun deleteTombstones(ids: List<Int>)

    @Query("DELETE FROM tombstones WHERE naturalKey = :key AND agentUid = :agentUid")
    suspend fun deleteByNaturalKey(key: String, agentUid: String)

    @Query("DELETE FROM tombstones WHERE agentUid = :agentUid")
    suspend fun deleteByAgent(agentUid: String)

    @Query("DELETE FROM tombstones WHERE deletedAt < :threshold")
    suspend fun deleteOldTombstones(threshold: Long)

    @Query("DELETE FROM tombstones")
    suspend fun deleteAll()
}
