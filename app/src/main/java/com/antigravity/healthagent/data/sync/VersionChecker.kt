package com.antigravity.healthagent.data.sync

import android.content.Context
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.utils.AppConstants
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VersionChecker @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    suspend fun fetchSystemSettings(): Result<Map<String, Any>> {
        return try {
            val snapshot = withTimeoutOrNull(5000) {
                firestore.collection("metadata").document("settings")
                    .get().await()
            }
            if (snapshot == null) return Result.success(emptyMap())
            val settings = snapshot.data ?: emptyMap()
            Result.success(settings)
        } catch (e: Exception) {
            AppLogger.w("VersionChecker", "fetchSystemSettings offline fallback: ${e.message}")
            Result.success(emptyMap())
        }
    }

    fun checkVersion(context: Context, sysSettings: Map<String, Any>): Result<Unit> {
        val minVersion = (sysSettings["minAppVersion"] as? Number)?.toInt() ?: AppConstants.MIN_VERSION_CODE
        val pInfo = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
        val currentVersion = pInfo?.versionCode ?: 0
        
        if (currentVersion < minVersion) {
            AppLogger.e("VersionChecker", "Version Enforcement: App version ($currentVersion) is below minimum required ($minVersion)")
            return Result.failure(Exception("Versão do aplicativo desatualizada. Por favor, atualize o 'Eu ACE' na Play Store para continuar sincronizando seus dados."))
        }
        return Result.success(Unit)
    }

    fun isWipeRequired(
        userDoc: DocumentSnapshot,
        agentDocSnapshot: DocumentSnapshot,
        hasSyncHistory: Boolean,
        isTargetDifferentUser: Boolean,
        localUnsyncedCount: Int
    ): Boolean {
        val requireResetFromUser = userDoc.getBoolean("requireDataReset") ?: false
        val requireResetFromAgent = agentDocSnapshot.getBoolean("requireDataReset") ?: false
        val agentDocExists = agentDocSnapshot.exists()
        
        return (requireResetFromUser || requireResetFromAgent || (!isTargetDifferentUser && hasSyncHistory && !agentDocExists)) && localUnsyncedCount == 0
    }
}
