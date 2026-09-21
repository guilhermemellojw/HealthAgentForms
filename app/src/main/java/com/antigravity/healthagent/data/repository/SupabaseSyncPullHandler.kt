package com.antigravity.healthagent.data.repository

import android.content.Context
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.remote.supabase.toAnyMap
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.sync.PullHandler
import com.antigravity.healthagent.data.sync.RemoteSyncFlags
import com.antigravity.healthagent.data.sync.SupabaseSystemSettingsSource
import com.antigravity.healthagent.data.sync.SyncReconciler
import com.antigravity.healthagent.data.sync.TeamworkSyncHandler
import com.antigravity.healthagent.data.sync.VersionChecker
import com.antigravity.healthagent.data.util.parseRemoteTimestamp
import com.antigravity.healthagent.data.util.toDayActivitySafe
import com.antigravity.healthagent.data.util.toHouseSafe
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pull Supabase -> local (delta por updated_at + soft-delete) — paralelo ao
 * SyncPullHandler, selecionado por BuildConfig.USE_SUPABASE_SYNC.
 */
@Singleton
class SupabaseSyncPullHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabase: SupabaseClient,
    private val houseRepository: HouseRepository,
    private val settingsManager: SettingsManager,
    private val teamworkSyncHandler: TeamworkSyncHandler,
    private val syncReconciler: SyncReconciler
) : PullHandler {

    private val versionChecker = VersionChecker(SupabaseSystemSettingsSource(supabase))

    private val isoFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private fun nowIso(): String = isoFmt.get()!!.format(java.util.Date())

    /**
     * Pull sem teto: PostgREST limita a 1000 linhas (full pull tem milhares).
     */
    private suspend fun fetchTablePaged(
        table: String,
        agentId: String,
        sinceIso: String?,
        orderColumn: String,
        pageSize: Long = 1000L
    ): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var from = 0L
        while (true) {
            val page = supabase.from(table).select {
                filter {
                    eq("agent_id", agentId)
                    if (sinceIso != null) gt("updated_at", sinceIso)
                }
                order(orderColumn, Order.ASCENDING)
                range(from, from + pageSize - 1)
            }.decodeList<JsonObject>()
            out.addAll(page)
            if (page.size < pageSize) break
            from += pageSize
        }
        return out
    }

    override suspend fun pullCloudDataToLocal(
        targetUid: String?,
        force: Boolean,
        syncMutex: Mutex
    ): Result<SyncRepository.SyncResult> {
        val result = withTimeoutOrNull(600000L) {
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val authUid = supabase.auth.currentUserOrNull()?.id
                        val uid = targetUid ?: authUid
                            ?: return@withContext Result.failure(Exception("Not logged in"))
                        val isTargetDifferentUser = targetUid != null && targetUid != authUid

                        // 1. Perfil + agente (flags de wipe + cursor delta)
                        val profile = supabase.from("profiles").select {
                            filter { eq("id", uid) }
                        }.decodeSingleOrNull<JsonObject>()?.toAnyMap()
                        val agent = supabase.from("agents").select {
                            filter { eq("id", uid) }
                        }.decodeSingleOrNull<JsonObject>()?.toAnyMap()

                        val email = (profile?.get("email") as? String)
                            ?: return@withContext Result.failure(Exception("User email not found"))
                        val profileAgentName = (agent?.get("agent_name") as? String)?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                            ?: (profile?.get("agent_name") as? String)?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                        val profileDisplayName = (profile?.get("display_name") as? String)?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                        val finalAgentName = profileAgentName ?: profileDisplayName ?: email.substringBefore("@").uppercase()

                        // --- VERSION SAFETY CHECK ---
                        val sysSettings = versionChecker.fetchSystemSettings().getOrDefault(emptyMap())
                        versionChecker.checkVersion(context, sysSettings).onFailure {
                            return@withContext Result.failure(it)
                        }

                        // --- REMOTE WIPE CHECK ---
                        val hasSyncHistory = settingsManager.lastSyncTimestamp.first() > 0
                        val localUnsyncedCount = houseRepository.getUnsyncedHouses(uid).size + houseRepository.getUnsyncedActivities(uid).size
                        val flags = RemoteSyncFlags(
                            requireDataResetFromUser = (profile?.get("require_data_reset") as? Boolean) ?: false,
                            requireDataResetFromAgent = false,
                            agentDocExists = agent != null
                        )
                        val requireReset = versionChecker.isWipeRequired(
                            flags = flags,
                            hasSyncHistory = hasSyncHistory,
                            isTargetDifferentUser = isTargetDifferentUser,
                            localUnsyncedCount = localUnsyncedCount
                        )

                        val localCount = houseRepository.countHouses()

                        if (requireReset) {
                            AppLogger.w("SupabasePull", "Remote Wipe Triggered for UID: $uid")
                            try {
                                houseRepository.clearAgentData(uid)
                                if (!isTargetDifferentUser) settingsManager.setLastSyncTimestamp(0L)
                                if (flags.requireDataReset) {
                                    supabase.from("profiles").update(buildJsonObject { put("require_data_reset", false) }) {
                                        filter { eq("id", uid) }
                                    }
                                }
                            } catch (e: Exception) {
                                AppLogger.e("SupabasePull", "Remote Wipe Failed: ${e.message}")
                                return@withContext Result.failure(e)
                            }
                        }

                        // 2. Cursor delta (last_pull; NULL = full pull)
                        val lastPullMs = parseRemoteTimestampOrNullCompat(agent?.get("last_pull")) ?: 0L
                        val cachedLastSync = if (isTargetDifferentUser || force || requireReset || localCount == 0) 0L
                            else settingsManager.lastSyncTimestamp.first()
                        val now = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                        val lastSync = if (cachedLastSync > now + 3600000L) 0L else cachedLastSync
                        val effectiveSince = maxOf(lastPullMs, lastSync).let { if (it > 0) maxOf(0L, it - 300000L) else 0L }
                        val sinceIso = if (effectiveSince > 0) isoFmt.get()!!.format(java.util.Date(effectiveSince)) else null

                        val cloudHouses = mutableListOf<House>()
                        val cloudDayActivities = mutableListOf<DayActivity>()
                        val cloudDeletedHouses = mutableSetOf<String>()
                        val cloudDeletedActivities = mutableSetOf<String>()

                        coroutineScope {
                            val housesJob = async {
                                fetchTablePaged("houses", uid, sinceIso, "updated_at")
                            }
                            val activitiesJob = async {
                                fetchTablePaged("day_activities", uid, sinceIso, "updated_at")
                            }
                            val (houseRows, activityRows) = awaitAll(housesJob, activitiesJob)

                            for (row in houseRows) {
                                val map = row.toAnyMap()
                                val deletedAt = parseRemoteTimestampOrNullCompat(map["deleted_at"])
                                val key = (map["natural_key"] as? String) ?: continue
                                if (deletedAt != null) {
                                    cloudDeletedHouses.add(key.replace("/", "-"))
                                } else {
                                    map.toHouseSafe(key, uid, finalAgentName)?.let { cloudHouses.add(it) }
                                }
                            }
                            for (row in activityRows) {
                                val map = row.toAnyMap()
                                val deletedAt = parseRemoteTimestampOrNullCompat(map["deleted_at"])
                                val dateKey = ((map["date_text"] as? String) ?: "").replace("/", "-")
                                if (deletedAt != null) {
                                    if (dateKey.isNotBlank()) cloudDeletedActivities.add("$dateKey|${finalAgentName.uppercase()}")
                                } else {
                                    map.toDayActivitySafe(dateKey.ifBlank { uid }, uid, finalAgentName)?.let { cloudDayActivities.add(it) }
                                }
                            }
                        }

                        // CLOCK SKEW DETECTION (paridade Firebase)
                        val maxCloudTime = (cloudHouses.map { it.lastUpdated } + cloudDayActivities.map { it.lastUpdated }).maxOrNull() ?: 0L
                        val skew0 = maxCloudTime - com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                        settingsManager.setClockSkewMs(if (kotlin.math.abs(skew0) > 120000) skew0 else 0L)

                        // --- TEAMWORK (fetch Supabase: query única, sem chunk 10) ---
                        val teammateHouses = teamworkSyncHandler.performTeamworkSync(
                            uid = uid,
                            cloudHouses = cloudHouses,
                            isTargetDifferentUser = isTargetDifferentUser,
                            fetchRemote = { bairros ->
                                if (bairros.isEmpty()) emptyList()
                                else supabase.from("houses").select {
                                    filter {
                                        isIn("bairro", bairros)
                                    }
                                    order("data_date", Order.ASCENDING)
                                }.decodeList<JsonObject>().mapNotNull { row ->
                                    val map = row.toAnyMap()
                                    // Apagadas não entram no teamwork (delta próprio as propaga).
                                    if (map["deleted_at"] != null) return@mapNotNull null
                                    val key = map["natural_key"] as? String ?: return@mapNotNull null
                                    map.toHouseSafe(
                                        key,
                                        (map["agent_uid"] as? String) ?: "",
                                        (map["agent_name"] as? String) ?: ""
                                    )
                                }
                            }
                        )

                        // --- RECONCILIATION (sem heal de tombstone arrays: não existem aqui) ---
                        syncReconciler.reconcile(
                            uid = uid,
                            finalAgentName = finalAgentName,
                            isTargetDifferentUser = isTargetDifferentUser,
                            cloudHouses = cloudHouses,
                            cloudDayActivities = cloudDayActivities,
                            cloudDeletedHouses = cloudDeletedHouses,
                            cloudDeletedActivities = cloudDeletedActivities,
                            teammateHouses = teammateHouses,
                            healRemoteTombstones = false
                        ).onFailure {
                            return@withContext Result.failure(it)
                        }

                        if (!isTargetDifferentUser) {
                            val maxObservedTime = (cloudHouses.map { it.lastUpdated } + cloudDayActivities.map { it.lastUpdated }).maxOrNull() ?: 0L
                            val serverTime = now
                            val safetyAnchor = serverTime - 600000L
                            val finalSyncTime = maxOf(maxObservedTime, safetyAnchor)
                            settingsManager.setLastSyncTimestamp(finalSyncTime)
                            try {
                                supabase.from("agents").update(buildJsonObject { put("last_pull", nowIso()) }) {
                                    filter { eq("id", uid) }
                                }
                            } catch (e: Exception) {
                                AppLogger.w("SupabasePull", "last_pull update failed: ${e.message}")
                            }
                            val skew = maxObservedTime - com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                            settingsManager.setClockSkewMs(skew)
                            Result.success(SyncRepository.SyncResult(cloudMaxTime = finalSyncTime, clockSkewMs = skew))
                        } else {
                            Result.success(SyncRepository.SyncResult())
                        }
                    } catch (e: Exception) {
                        AppLogger.e("SupabasePull", "Pull failed", e)
                        Result.failure(e)
                    }
                }
            }
        } ?: Result.failure(Exception("O download de dados atingiu o tempo limite. Verifique sua conexão ou tente novamente."))
        return result
    }

    private fun parseRemoteTimestampOrNullCompat(value: Any?): Long? {
        return com.antigravity.healthagent.data.util.parseRemoteTimestampOrNull(value)
    }
}
