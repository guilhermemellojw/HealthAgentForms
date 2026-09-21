package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.dao.DayActivityDao
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.remote.supabase.SupabaseProfileRow
import com.antigravity.healthagent.data.remote.supabase.toAuthUser
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.sync.reclaimLocalIdentity
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.SyncRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auth via Supabase (Google ID token) — implementação paralela à Firebase,
 * selecionada por BuildConfig.USE_SUPABASE_AUTH no Hilt.
 */
@Singleton
class SupabaseAuthRepositoryImpl @Inject constructor(
    private val supabase: SupabaseClient,
    private val syncRepository: SyncRepository,
    private val settingsManager: SettingsManager,
    private val houseDao: HouseDao,
    private val activityDao: DayActivityDao,
    private val workManager: androidx.work.WorkManager
) : AuthRepository {

    private val bootstrapAdmins = listOf("gmellobkp@gmail.com")

    override val currentUserAsync: Flow<AuthUser?> = callbackFlow {
        val job = launch {
            supabase.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val sessionUser = supabase.auth.currentUserOrNull()
                        if (sessionUser == null) {
                            trySend(null)
                        } else {
                            try {
                                val cached = settingsManager.cachedUser.firstOrNull()
                                if (cached != null && cached.uid == sessionUser.id) {
                                    trySend(cached)
                                }
                                val meta = sessionUser.userMetadata
                                val user = getFullUserData(
                                    uid = sessionUser.id,
                                    email = sessionUser.email,
                                    displayName = meta?.get("name")?.toString()?.trim('"'),
                                    photoUrl = meta?.get("picture")?.toString()?.trim('"')
                                )
                                trySend(user)
                            } catch (e: Exception) {
                                AppLogger.e("SupabaseAuth", "Error fetching user data", e)
                                val lastResort = settingsManager.cachedUser.firstOrNull()
                                if (lastResort != null && lastResort.uid == sessionUser.id) {
                                    trySend(lastResort)
                                } else {
                                    trySend(
                                        AuthUser(
                                            uid = sessionUser.id,
                                            email = sessionUser.email,
                                            displayName = null,
                                            photoUrl = null
                                        )
                                    )
                                }
                            }
                        }
                    }
                    is SessionStatus.NotAuthenticated -> trySend(null)
                    else -> Unit // Loading / Refreshing: mantém último estado
                }
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun signInWithGoogle(idToken: String): Result<AuthUser> {
        return try {
            supabase.auth.signInWith(IDToken) {
                this.idToken = idToken
                provider = Google
            }
            val sessionUser = supabase.auth.currentUserOrNull()
                ?: throw Exception("Login failed: User is null")
            val meta = sessionUser.userMetadata
            val user = getFullUserData(
                uid = sessionUser.id,
                email = sessionUser.email,
                displayName = meta?.get("name")?.toString()?.trim('"'),
                photoUrl = meta?.get("picture")?.toString()?.trim('"')
            )
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun signOut() {
        AppLogger.i("SupabaseAuth", "SignOut requested - Performing immediate session clear...")
        workManager.cancelAllWorkByTag("sync")
        try {
            supabase.auth.signOut()
        } catch (e: Exception) {
            AppLogger.e("SupabaseAuth", "Supabase signOut failed: ${e.message}")
        }
        settingsManager.clearSessionSettings()
        try {
            syncRepository.clearLocalData()
        } catch (e: Exception) {
            AppLogger.e("SupabaseAuth", "Immediate database wipe failed: ${e.message}")
        }
        val logoutRequest = androidx.work.OneTimeWorkRequestBuilder<com.antigravity.healthagent.data.sync.LogoutWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueue(logoutRequest)
    }

    override fun getCurrentUserUid(): String? = supabase.auth.currentUserOrNull()?.id

    override suspend fun migratePreRegistration(user: AuthUser): Result<Unit> {
        val email = user.email ?: return Result.failure(Exception("Email do usuário não disponível para migração"))
        return migratePreRegistration(email, user.uid)
    }

    override suspend fun migratePreRegistration(email: String, targetUid: String, preferredAgentName: String?): Result<Unit> {
        return try {
            // Sem docs pre_ no Supabase: o claim casa a linha seed pelo e-mail e a
            // cascata reatribui o id (profiles -> agents -> produção).
            claimProfile()
            val preferredName = preferredAgentName?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            if (preferredName != null) {
                try {
                    supabase.from("profiles").update(buildJsonObject { put("agent_name", preferredName) }) {
                        filter { eq("id", targetUid) }
                    }
                } catch (e: Exception) {
                    AppLogger.e("SupabaseAuth", "Failed to set preferred agent name", e)
                }
            }
            val normalizedEmail = email.trim().lowercase()
            reclaimLocalIdentity(
                houseDao = houseDao,
                activityDao = activityDao,
                email = normalizedEmail,
                uid = targetUid,
                displayName = null,
                standardName = preferredName ?: normalizedEmail.substringBefore("@").uppercase()
            )
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e("SupabaseAuth", "migratePreRegistration failed", e)
            Result.failure(e)
        }
    }

    private suspend fun claimProfile() {
        try {
            supabase.postgrest.rpc("claim_migrated_profile")
        } catch (e: Exception) {
            AppLogger.w("SupabaseAuth", "claim_migrated_profile best-effort: ${e.message}")
        }
    }

    private suspend fun fetchProfile(uid: String): SupabaseProfileRow? {
        return try {
            supabase.from("profiles").select {
                filter { eq("id", uid) }
            }.decodeSingleOrNull<SupabaseProfileRow>()
        } catch (e: Exception) {
            AppLogger.w("SupabaseAuth", "fetchProfile failed: ${e.message}")
            null
        }
    }

    private suspend fun getFullUserData(uid: String, email: String?, displayName: String?, photoUrl: String?): AuthUser {
        val normEmail = (email ?: "").trim().lowercase()
        if (bootstrapAdmins.contains(normEmail)) {
            AppLogger.i("SupabaseAuth", "Bootstrap Admin detected: $normEmail")
            val admin = AuthUser(
                uid = uid,
                email = normEmail,
                displayName = displayName,
                photoUrl = photoUrl,
                role = com.antigravity.healthagent.domain.repository.UserRole.ADMIN,
                isAuthorized = true,
                agentName = null
            )
            settingsManager.saveUserProfile(admin)
            return admin
        }

        // Primeiro login pós-migração: assume a linha seed pelo e-mail.
        claimProfile()
        val row = fetchProfile(uid)
        if (row == null) {
            val cached = settingsManager.cachedUser.firstOrNull()
            if (cached != null && cached.uid == uid) {
                AppLogger.i("SupabaseAuth", "Using CACHED user profile for $normEmail")
                return cached
            }
            val fresh = AuthUser(uid = uid, email = normEmail.ifBlank { null }, displayName = displayName, photoUrl = photoUrl)
            settingsManager.saveUserProfile(fresh)
            return fresh
        }

        val user = row.toAuthUser(
            authUid = uid,
            authEmail = email,
            authDisplayName = displayName,
            authPhotoUrl = photoUrl,
            bootstrapAdmins = bootstrapAdmins
        )
        if (user.isAuthorized) {
            reclaimLocalIdentity(
                houseDao = houseDao,
                activityDao = activityDao,
                email = normEmail,
                uid = user.uid,
                displayName = user.displayName,
                standardName = user.standardName
            )
        }
        settingsManager.saveUserProfile(user)
        return user
    }
}
