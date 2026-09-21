package com.antigravity.healthagent.data.sync

import com.antigravity.healthagent.domain.logger.AppLogger
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.data.settings.SettingsManager
import com.google.firebase.auth.FirebaseAuth
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.firstOrNull

@HiltWorker
class LogoutWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val houseRepository: HouseRepository,
    private val settingsManager: SettingsManager,
    private val auth: FirebaseAuth
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppLogger.i("LogoutWorker", "Starting guaranteed logout cleanup...")
        
        try {
            val firebaseUser = auth.currentUser
            if (firebaseUser != null) {
                val uid = firebaseUser.uid
                val cachedProfile = settingsManager.cachedUser.firstOrNull()
                val agentName = cachedProfile?.agentName ?: ""

                // 1. FINAL SYNC (Best Effort)
                if (agentName.isNotBlank()) {
                    val houses = houseRepository.getUnsyncedHouses(uid)
                    if (houses.isNotEmpty()) {
                        AppLogger.i("LogoutWorker", "Performing final sync for $agentName (${houses.size} houses)...")
                        syncRepository.pushLocalDataToCloud(houses, emptyList(), uid)
                    }
                }
            }

            // 2. WIPE LOCAL DATA
            AppLogger.i("LogoutWorker", "Wiping local database...")
            syncRepository.clearLocalData()

            // 3. WIPE SUCCESSFUL
            AppLogger.i("LogoutWorker", "Cleanup completed successfully.")
            return Result.success()
        } catch (e: Exception) {
            AppLogger.e("LogoutWorker", "Cleanup failed: ${e.message}")
            // Even if sync fails, we MUST try to wipe data
            try {
                syncRepository.clearLocalData()
            } catch (e2: Exception) {}
            return Result.failure()
        }
    }
}
