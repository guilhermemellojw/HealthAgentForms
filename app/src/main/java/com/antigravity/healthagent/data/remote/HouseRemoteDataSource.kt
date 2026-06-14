package com.antigravity.healthagent.data.remote

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow

interface HouseRemoteDataSource {
    suspend fun fetchHousesInRange(uid: String, dates: List<String>, agentName: String): Result<List<House>>
    suspend fun fetchActivitiesInRange(uid: String, dates: List<String>, agentName: String): Result<List<DayActivity>>
    suspend fun deleteAgentHouse(uid: String, houseId: String, monthYear: String?): Result<Unit>
    suspend fun deleteAgentActivity(uid: String, activityDate: String, monthYear: String?): Result<Unit>
    suspend fun transferAgentData(fromUid: String, toUid: String, targetAgentName: String): Result<Unit>
    fun observeAgentProduction(uid: String, agentName: String, datePattern: String?): Flow<Pair<List<House>, List<DayActivity>>>
}
