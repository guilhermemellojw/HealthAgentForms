package com.antigravity.healthagent.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.data.local.dao.HouseDao
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
    private val houseDao: HouseDao,
    private val settingsManager: SettingsManager,
    private val auth: FirebaseAuth
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        android.util.Log.i("LogoutWorker", "Starting guaranteed logout cleanup...")
        
        try {
            val firebaseUser = auth.currentUser
            if (firebaseUser != null) {
                val uid = firebaseUser.uid
                val cachedProfile = settingsManager.cachedUser.firstOrNull()
                val agentName = cachedProfile?.agentName ?: ""

                // 1. FINAL SYNC (Best Effort)
                if (agentName.isNotBlank()) {
                    val houses = houseDao.getUnsyncedHouses(agentName, uid)
                    if (houses.isNotEmpty()) {
                        android.util.Log.i("LogoutWorker", "Performing final sync for $agentName (${houses.size} houses)...")
                        syncRepository.pushLocalDataToCloud(houses, emptyList(), uid)
                    }
                }
            }

            // 2. WIPE LOCAL DATA
            android.util.Log.i("LogoutWorker", "Wiping local database...")
            syncRepository.clearLocalData()

            // 3. FIREBASE SIGN OUT
            android.util.Log.i("LogoutWorker", "Signing out of Firebase...")
            auth.signOut()

            android.util.Log.i("LogoutWorker", "Cleanup completed successfully.")
            return Result.success()
        } catch (e: Exception) {
            android.util.Log.e("LogoutWorker", "Cleanup failed: ${e.message}")
            // Even if sync fails, we MUST try to wipe data and sign out
            try {
                syncRepository.clearLocalData()
                auth.signOut()
            } catch (e2: Exception) {}
            return Result.failure()
        }
    }
}
