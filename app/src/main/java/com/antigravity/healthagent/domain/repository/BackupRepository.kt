package com.antigravity.healthagent.domain.repository

import com.antigravity.healthagent.data.backup.BackupData

interface BackupRepository {
    suspend fun uploadTimelineBackup(uid: String, data: BackupData): Result<Unit>
    suspend fun fetchTimeline(uid: String): Result<List<BackupMetadata>>
    suspend fun downloadBackup(storagePath: String): Result<BackupData>
}

data class BackupMetadata(
    val id: String = "",
    val timestamp: Long = 0,
    val storagePath: String = "",
    val houseCount: Int = 0,
    val activityCount: Int = 0,
    val agentName: String = ""
)
