package com.antigravity.healthagent.domain.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow

interface SyncRepository {
    /** 
     * Pushes all local Room data (Houses, DayActivities) to the logged-in user's Firestore document.
     * Usually called manually, or after finishing a day.
     */
    suspend fun pushLocalDataToCloud(houses: List<House>, activities: List<DayActivity>): Result<Unit>
    
    /**
     * For Admin users to view all data submitted by all agents.
     */
    suspend fun fetchAllAgentsData(): Result<List<AgentData>>
}

data class AgentData(
    val uid: String,
    val email: String,
    val houses: List<House>,
    val activities: List<DayActivity>,
    val lastSyncTime: Long = 0L
)
