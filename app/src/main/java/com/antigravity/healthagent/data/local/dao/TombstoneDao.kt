package com.antigravity.healthagent.data.local.dao

import androidx.room.*
import com.antigravity.healthagent.data.local.model.Tombstone
import com.antigravity.healthagent.data.local.model.TombstoneType

@Dao
interface TombstoneDao {
    @Query("SELECT * FROM tombstones")
    suspend fun getAllTombstones(): List<Tombstone>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: Tombstone)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstones(tombstones: List<Tombstone>)

    @Query("DELETE FROM tombstones WHERE id IN (:ids)")
    suspend fun deleteTombstones(ids: List<Int>)

    @Query("DELETE FROM tombstones")
    suspend fun deleteAll()
}
