package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow

interface HouseRepository {
    fun getAllHouses(): Flow<List<House>>
    fun getAllHousesOrderedByBlock(): Flow<List<House>>
    suspend fun getHouseById(id: Long): House?
    suspend fun getAllHousesOnce(): List<House> // Snapshot for backup
    suspend fun insertHouse(house: House)
    suspend fun updateHouse(house: House)
    suspend fun updateHouses(houses: List<House>)
    suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String)
    suspend fun deleteHouse(house: House)
    suspend fun replaceAllHouses(houses: List<House>) // Restore

    // DayActivity (Weekly Status)
    fun getDayActivities(dates: List<String>, agentName: String): Flow<List<DayActivity>>
    fun getDayActivityFlow(date: String, agentName: String): Flow<DayActivity?>
    suspend fun updateDayActivity(dayActivity: DayActivity)
    suspend fun getDayActivity(date: String, agentName: String): DayActivity?
    suspend fun getAllDayActivitiesOnce(): List<DayActivity>
    suspend fun replaceAllDayActivities(activities: List<DayActivity>)
    suspend fun restoreAgentData(agentName: String, houses: List<House>, activities: List<DayActivity>)
    suspend fun deleteProduction(date: String, agentName: String) // Deletes houses, might need check
    suspend fun countOpenDays(agentName: String): Int
    suspend fun closeAllDays(agentName: String)
    suspend fun clearAllData()
}
