package com.antigravity.healthagent.data.sync

import com.antigravity.healthagent.domain.logger.AppLogger
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fonte de settings do sistema (metadata/settings) — backend-neutro.
 * Implementação Supabase chega na fatia Auth; mesma forma de retorno.
 */
interface SystemSettingsSource {
    suspend fun fetchSystemSettings(): Result<Map<String, Any>>
}

@Singleton
class FirestoreSystemSettingsSource @Inject constructor(
    private val firestore: FirebaseFirestore
) : SystemSettingsSource {
    override suspend fun fetchSystemSettings(): Result<Map<String, Any>> {
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
}
