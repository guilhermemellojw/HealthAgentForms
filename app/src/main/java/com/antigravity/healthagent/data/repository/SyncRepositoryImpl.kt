package com.antigravity.healthagent.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.antigravity.healthagent.data.local.AppDatabase
import com.antigravity.healthagent.data.local.dao.DayActivityDao
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.TombstoneDao
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Tombstone
import com.antigravity.healthagent.data.local.model.TombstoneType
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.sync.SyncScheduler
import com.antigravity.healthagent.domain.repository.BackupRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.utils.withRetry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class SyncRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val houseDao: HouseDao,
    private val dayActivityDao: DayActivityDao,
    private val tombstoneDao: TombstoneDao,
    private val settingsManager: SettingsManager,
    private val database: AppDatabase,
    private val backupRepository: BackupRepository,
    private val syncSchedulerProvider: Provider<SyncScheduler>,
    private val syncPushHandler: SyncPushHandler,
    private val syncPullHandler: SyncPullHandler,
    private val syncDeletionHandler: SyncDeletionHandler,
    private val syncAdminHandler: SyncAdminHandler
) : SyncRepository {

    private val syncMutex = Mutex()

    private suspend fun <T> runInTransactionWithRetry(block: suspend () -> T): T {
        return database.withRetry(maxAttempts = 3) {
            database.withTransaction { block() }
        }
    }

    override suspend fun pushLocalDataToCloud(
        houses: List<House>,
        activities: List<DayActivity>,
        targetUid: String?,
        shouldReplace: Boolean
    ): Result<Unit> {
        return syncPushHandler.pushLocalDataToCloud(
            houses = houses,
            activities = activities,
            targetUid = targetUid,
            shouldReplace = shouldReplace,
            syncMutex = syncMutex
        )
    }

    override suspend fun pullCloudDataToLocal(targetUid: String?, force: Boolean): Result<Unit> {
        return syncPullHandler.pullCloudDataToLocal(
            targetUid = targetUid,
            force = force,
            syncMutex = syncMutex
        )
    }

    override suspend fun clearLocalData(): Result<Unit> {
        return withTimeoutOrNull(15000L) {
            syncMutex.withLock {
                syncAdminHandler.clearLocalDataInternal()
            }
        } ?: Result.failure(Exception("Tempo esgotado ao tentar limpar dados locais (Database busy)"))
    }

    override suspend fun clearAgentData(agentUid: String): Result<Unit> {
        return withTimeoutOrNull(15000L) {
            syncMutex.withLock {
                syncAdminHandler.clearAgentDataInternal(agentUid)
            }
        } ?: Result.failure(Exception("Tempo esgotado ao tentar limpar dados locais (Database busy)"))
    }

    override suspend fun performDataCleanup(): Result<Unit> = syncAdminHandler.performDataCleanup()

    override suspend fun restoreLocalData(houses: List<House>, activities: List<DayActivity>, agentUid: String?): Result<Unit> =
        syncAdminHandler.restoreLocalData(houses, activities, agentUid)

    override suspend fun fetchSystemSettings(): Result<Map<String, Any>> = syncAdminHandler.fetchSystemSettings()

    override suspend fun updateSystemSetting(key: String, value: Any): Result<Unit> =
        syncAdminHandler.updateSystemSetting(key, value)

    override suspend fun deleteAgentHouse(agentUid: String, houseId: String): Result<Unit> =
        syncDeletionHandler.deleteAgentHouse(agentUid, houseId)

    override suspend fun deleteAgentActivity(agentUid: String, activityDate: String): Result<Unit> =
        syncDeletionHandler.deleteAgentActivity(agentUid, activityDate)

    override suspend fun recordHouseDeletion(house: House): Result<Unit> =
        syncDeletionHandler.recordHouseDeletion(house)

    override suspend fun recordActivityDeletion(date: String, agentUid: String): Result<Unit> =
        syncDeletionHandler.recordActivityDeletion(date, agentUid)

    override suspend fun recordBulkDeletions(houseKeys: List<String>, activityDates: List<String>, targetUid: String?): Result<Unit> =
        syncDeletionHandler.recordBulkDeletions(houseKeys, activityDates, targetUid)

    override suspend fun deleteHousesSurgically(agentUid: String, houses: List<House>): Result<Unit> =
        syncDeletionHandler.deleteHousesSurgically(agentUid, houses)

    override suspend fun deleteAllCloudData(): Result<Unit> = syncAdminHandler.deleteAllCloudData()

    override suspend fun clearSyncError(uid: String): Result<Unit> = syncAdminHandler.clearSyncError(uid)
    
    override suspend fun pruneOldTombstones(): Result<Unit> = syncAdminHandler.pruneOldTombstones()
}
