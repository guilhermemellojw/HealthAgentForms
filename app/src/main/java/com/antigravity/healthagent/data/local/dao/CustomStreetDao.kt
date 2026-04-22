package com.antigravity.healthagent.data.local.dao

import androidx.room.*
import com.antigravity.healthagent.data.local.model.CustomStreet
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomStreetDao {
    @Query("SELECT * FROM custom_streets")
    fun getAllCustomStreets(): Flow<List<CustomStreet>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCustomStreet(street: CustomStreet)

    @Delete
    suspend fun deleteCustomStreet(street: CustomStreet)

    @Query("DELETE FROM custom_streets")
    suspend fun deleteAll()
}
