package com.antigravity.healthagent.data.repository

import android.content.Context
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.TombstoneType
import com.antigravity.healthagent.data.remote.supabase.toAnyMap
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.sync.PushHandler
import com.antigravity.healthagent.data.util.nowIsoUtc
import com.antigravity.healthagent.data.util.toDayActivitySafe
import com.antigravity.healthagent.data.util.toHouseSafe
import com.antigravity.healthagent.data.util.toSupabaseRow
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.utils.toDashDate
import com.antigravity.healthagent.utils.toSlashDate
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Push Supabase (upsert em lote + soft-delete + summaries) — paralelo ao
 * SyncPushHandler, selecionado por BuildConfig.USE_SUPABASE_SYNC.
 * Sem FieldValue/arrays de tombstone: deletes viram deleted_at (delta converge).
 */
@Singleton
class SupabaseSyncPushHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabase: SupabaseClient,
    private val houseRepository: HouseRepository,
    private val settingsManager: SettingsManager
) : PushHandler {

    override suspend fun pushLocalDataToCloud(
        houses: List<House>,
        activities: List<DayActivity>,
        targetUid: String?,
        shouldReplace: Boolean,
        isFullWipe: Boolean,
        syncMutex: Mutex
    ): Result<Unit> {
        val result = withTimeoutOrNull(600000L) {
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val authUid = supabase.auth.currentUserOrNull()?.id
                        val uid = targetUid ?: authUid
                            ?: return@withContext Result.failure(Exception("User not authenticated"))

                        // --- WIPE SAFETY CHECK ---
                        val profileRow = supabase.from("profiles").select {
                            filter { eq("id", uid) }
                        }.decodeSingleOrNull<JsonObject>()?.toAnyMap()
                        val agentRow = supabase.from("agents").select {
                            filter { eq("id", uid) }
                        }.decodeSingleOrNull<JsonObject>()?.toAnyMap()
                        val agentDocExists = agentRow != null
                        val hasSyncHistory = settingsManager.lastSyncTimestamp.first() > 0

                        val requireReset = (profileRow?.get("require_data_reset") as? Boolean) == true
                        if (targetUid == null && (requireReset || (hasSyncHistory && !agentDocExists))) {
                            AppLogger.w("SupabasePush", "Push blocked for $uid: Remote wipe pending or data purged.")
                            return@withContext Result.failure(Exception("Sincronização bloqueada: Uma limpeza de dados foi solicitada. Por favor, realize um 'Receber Dados' primeiro."))
                        }

                        var officialAgentName = ((agentRow?.get("agent_name") as? String) ?: "").uppercase()
                        val userEmail = if (targetUid != null) {
                            (agentRow?.get("email") as? String)?.takeIf { it.isNotBlank() }
                                ?: (profileRow?.get("email") as? String)?.takeIf { it.isNotBlank() }
                                ?: "Remote Sync"
                        } else {
                            supabase.auth.currentUserOrNull()?.email ?: "Unknown Email"
                        }

                        // 1. Dados locais + tombstones
                        val unsyncedHouses = houseRepository.getUnsyncedHouses(uid)
                        val unsyncedActivities = houseRepository.getUnsyncedActivities(uid)
                        val tombstones = houseRepository.getAllTombstones(uid)

                        if (unsyncedHouses.isEmpty() && unsyncedActivities.isEmpty() && tombstones.isEmpty() && !shouldReplace) {
                            AppLogger.i("SupabasePush", "Push: Nothing to sync (incremental).")
                            return@withContext Result.success(Unit)
                        }

                        var housesToPush = if (shouldReplace) houses else unsyncedHouses
                        var activitiesToPush = if (shouldReplace) activities else unsyncedActivities

                        // SURGICAL PROTECTION: Identity Isolation Guard (idêntico Firebase)
                        if (uid.isNotBlank()) {
                            val isProxyPush = targetUid != null

                            val crossUidHouses = housesToPush.filter { it.agentUid.isNotBlank() && it.agentUid != uid }
                            if (crossUidHouses.isNotEmpty()) {
                                AppLogger.e("SupabasePush", "IDENTITY LEAK PREVENTED: Filtered out ${crossUidHouses.size} houses with mismatching UIDs.")
                            }
                            housesToPush = if (isProxyPush) {
                                housesToPush.filter { it.agentUid == uid }
                            } else {
                                housesToPush.filter { it.agentUid.isBlank() || it.agentUid == uid }
                            }
                            housesToPush = housesToPush.filter { h ->
                                h.address.bairro.isNotBlank() &&
                                h.address.streetName.isNotBlank() &&
                                h.address.blockNumber.isNotBlank() &&
                                (h.address.number.isNotBlank() || h.address.sequence > 0)
                            }

                            val crossUidActivities = activitiesToPush.filter { it.agentUid.isNotBlank() && it.agentUid != uid }
                            if (crossUidActivities.isNotEmpty()) {
                                AppLogger.e("SupabasePush", "IDENTITY LEAK PREVENTED: Filtered out ${crossUidActivities.size} activities with mismatching UIDs.")
                            }
                            activitiesToPush = if (isProxyPush) {
                                activitiesToPush.filter { it.agentUid == uid }
                            } else {
                                activitiesToPush.filter { it.agentUid.isBlank() || it.agentUid == uid }
                            }
                        }

                        if (officialAgentName.isBlank()) {
                            officialAgentName = (housesToPush.firstOrNull { it.agentName.isNotBlank() }?.agentName
                                ?: activitiesToPush.firstOrNull { it.agentName.isNotBlank() }?.agentName
                                ?: "").uppercase()
                        }

                        val isAdminPush = shouldReplace && targetUid != null && targetUid != authUid
                        val pInfo = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }

                        // Garante linha agents (FK dos upserts; proxy depende disso).
                        supabase.from("agents").upsert(buildJsonObject {
                            put("id", uid)
                            put("email", userEmail)
                            if (officialAgentName.isNotBlank()) put("agent_name", officialAgentName)
                        }) { onConflict = "id" }

                        if (isFullWipe) {
                            AppLogger.w("SupabasePush", "Executando FULL WIPE na nuvem para o agente $uid")
                            for (table in listOf("houses", "day_activities", "monthly_summaries")) {
                                supabase.from(table).delete { filter { eq("agent_id", uid) } }
                            }
                            val staleIds = tombstones.map { it.id }
                            if (staleIds.isNotEmpty()) {
                                houseRepository.runInTransaction {
                                    houseRepository.deleteTombstones(staleIds)
                                }
                            }
                        } else if (shouldReplace) {
                            // Replace por data: apaga o que existe nas datas afetadas, upsert reinsere.
                            val backupDates = (
                                housesToPush.map { it.data.toDashDate() } +
                                activitiesToPush.map { it.date.toDashDate() } +
                                tombstones.filter { it.type == TombstoneType.ACTIVITY }.map { it.naturalKey.split("|")[0].toDashDate() } +
                                tombstones.filter { it.type == TombstoneType.HOUSE }.mapNotNull { tk ->
                                    tk.dataDate.takeIf { it.isNotBlank() }?.toDashDate()
                                }
                            ).toSet()
                            // data_date é DATE: converte DD-MM-YYYY -> ISO.
                            val isoDates = backupDates.mapNotNull { dashToIso(it) }
                            for (dateChunk in isoDates.chunked(50)) {
                                if (dateChunk.isEmpty()) continue
                                val keysH = supabase.from("houses").select(Columns.list("natural_key")) {
                                    filter { eq("agent_id", uid); isIn("data_date", dateChunk) }
                                }.decodeList<JsonObject>().mapNotNull { (it["natural_key"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                                for (kc in keysH.chunked(100)) {
                                    supabase.from("houses").delete { filter { eq("agent_id", uid); isIn("natural_key", kc) } }
                                }
                                val keysA = supabase.from("day_activities").select(Columns.list("date_text")) {
                                    filter { eq("agent_id", uid); isIn("date_value", dateChunk) }
                                }.decodeList<JsonObject>().mapNotNull { (it["date_text"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                                for (kc in keysA.chunked(100)) {
                                    supabase.from("day_activities").delete { filter { eq("agent_id", uid); isIn("date_text", kc) } }
                                }
                            }
                        }

                        if (shouldReplace && targetUid != null && targetUid != authUid) {
                            supabase.from("profiles").update(buildJsonObject { put("require_data_reset", true) }) {
                                filter { eq("id", uid) }
                            }
                            AppLogger.i("SupabasePush", "Admin Restore: Signalling target device ($uid) for local reset.")
                        }

                        // 1. TOMBSTONES -> soft-delete (delta converge; sem arrays)
                        if (tombstones.isNotEmpty()) {
                            val houseKeys = tombstones.filter { it.type == TombstoneType.HOUSE }.map { it.naturalKey }
                            for (kc in houseKeys.chunked(100)) {
                                supabase.from("houses").update(buildJsonObject { put("deleted_at", nowIsoUtc()) }) {
                                    filter { eq("agent_id", uid); isIn("natural_key", kc) }
                                }
                            }
                            val activityDates = tombstones.filter { it.type == TombstoneType.ACTIVITY }
                                .map { it.naturalKey.split("|")[0].replace("/", "-") }.distinct()
                            for (kc in activityDates.chunked(100)) {
                                supabase.from("day_activities").update(buildJsonObject { put("deleted_at", nowIsoUtc()) }) {
                                    filter { eq("agent_id", uid); isIn("date_text", kc) }
                                }
                            }
                            houseRepository.runInTransaction {
                                houseRepository.deleteTombstones(tombstones.map { it.id })
                            }
                        }

                        // 2. HOUSES (upsert em lote; dedup incremental com soft-delete)
                        if (housesToPush.isNotEmpty()) {
                            if (!shouldReplace) {
                                val affectedDates = housesToPush.map { it.data.toDashDate() }.toSet()
                                for (date in affectedDates) {
                                    try {
                                        val iso = dashToIso(date) ?: continue
                                        val cloudRows = supabase.from("houses").select {
                                            filter { eq("agent_id", uid); eq("data_date", iso) }
                                        }.decodeList<JsonObject>()
                                        val localHousesForDate = houseRepository.getHousesByDateAndAgent(date, uid)
                                        val localKeys = localHousesForDate.map { it.generateNaturalKey() }.toSet()
                                        val localIdentities = localHousesForDate.map { it.generateIdentityKey() }.toSet()
                                        val toSoftDelete = cloudRows.mapNotNull { row ->
                                            val map = row.toAnyMap()
                                            val key = map["natural_key"] as? String ?: return@mapNotNull null
                                            val cloudHouse = map.toHouseSafe(key, uid, officialAgentName) ?: return@mapNotNull null
                                            if (key !in localKeys && cloudHouse.generateIdentityKey() in localIdentities) key else null
                                        }
                                        for (kc in toSoftDelete.chunked(100)) {
                                            supabase.from("houses").update(buildJsonObject { put("deleted_at", nowIsoUtc()) }) {
                                                filter { eq("agent_id", uid); isIn("natural_key", kc) }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        AppLogger.w("SupabasePush", "Cloud Deduplication context skip for $date: ${e.message}")
                                    }
                                }
                            }

                            housesToPush.chunked(200).forEach { chunk ->
                                val rows = chunk.map { house ->
                                    val officialHouse = house.copy(
                                        agentName = officialAgentName.ifBlank { house.agentName },
                                        agentUid = uid,
                                        editedByAdmin = isAdminPush || house.editedByAdmin
                                    )
                                    officialHouse.toSupabaseRow(uid, officialHouse.agentName, officialHouse.editedByAdmin) to officialHouse
                                }
                                supabase.from("houses").upsert(JsonArray(rows.map { it.first })) {
                                    onConflict = "agent_id,natural_key"
                                }
                                houseRepository.runInTransaction {
                                    rows.forEach { (_, officialHouse) ->
                                        val orig = chunk.find { it.generateNaturalKey() == officialHouse.generateNaturalKey() }
                                        houseRepository.markHouseAsSynced(
                                            orig?.id ?: officialHouse.id,
                                            officialHouse.lastUpdated, uid, officialAgentName
                                        )
                                    }
                                }
                            }
                        }

                        // 3. ACTIVITIES (chave estável = upsert puro)
                        if (activitiesToPush.isNotEmpty()) {
                            activitiesToPush.chunked(200).forEach { chunk ->
                                val rows = chunk.map { activity ->
                                    val officialActivity = activity.copy(
                                        agentName = officialAgentName,
                                        agentUid = uid,
                                        editedByAdmin = isAdminPush || activity.editedByAdmin
                                    )
                                    officialActivity.toSupabaseRow(uid, officialAgentName, officialActivity.editedByAdmin)
                                }
                                supabase.from("day_activities").upsert(JsonArray(rows)) {
                                    onConflict = "agent_id,date_text"
                                }
                                houseRepository.runInTransaction {
                                    chunk.forEach { activity ->
                                        houseRepository.markActivityAsSynced(activity.date, officialAgentName, uid, activity.lastUpdated)
                                    }
                                }
                            }
                        }

                        // 4. SUMMARY AGGREGATION (mesma regra Firebase)
                        val allAffectedDates = (
                            housesToPush.map { it.data } +
                            activitiesToPush.map { it.date } +
                            tombstones.map { it.dataDate }.filter { it.isNotBlank() }
                        ).map { it.replace("/", "-") }.distinct()
                        val monthsToUpdate = allAffectedDates.map {
                            it.split("-").let { parts -> if (parts.size >= 3) "${parts[1]}-${parts[2]}" else "" }
                        }.filter { it.isNotBlank() }.toSet()

                        for (monthYear in monthsToUpdate) {
                            val isProxyPush = targetUid != null && targetUid != authUid
                            if (isProxyPush && !shouldReplace) {
                                AppLogger.i("SupabasePush", "Skipping monthly_summaries update for $monthYear (proxy push). Agent will recalculate on next sync.")
                                continue
                            }
                            val todayInt = com.antigravity.healthagent.utils.DateUtils.COMPACT_DATE.get().format(java.util.Date()).toInt()

                            val housesInMonthRaw = if (shouldReplace) {
                                housesToPush.filter { it.data.replace("/", "-").contains(monthYear) }
                            } else {
                                houseRepository.getHousesByMonth(uid, monthYear)
                            }
                            val housesInMonth = housesInMonthRaw.filter { house ->
                                try {
                                    val parts = house.data.replace("/", "-").split("-")
                                    if (parts.size == 3) {
                                        String.format("%04d%02d%02d", parts[2].toInt(), parts[1].toInt(), parts[0].toInt()).toInt() <= todayInt
                                    } else true
                                } catch (e: Exception) { true }
                            }
                            val activitiesInMonthRaw = if (shouldReplace) {
                                activitiesToPush.filter { it.date.replace("/", "-").contains(monthYear) }
                            } else {
                                houseRepository.getDayActivitiesByMonth(uid, monthYear)
                            }
                            val activitiesInMonth = activitiesInMonthRaw.filter { activity ->
                                try {
                                    val parts = activity.date.replace("/", "-").split("-")
                                    if (parts.size == 3) {
                                        String.format("%04d%02d%02d", parts[2].toInt(), parts[1].toInt(), parts[0].toInt()).toInt() <= todayInt
                                    } else true
                                } catch (e: Exception) { true }
                            }

                            val situationCounts = housesInMonth.groupingBy {
                                if (it.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) "NONE" else it.situation.name
                            }.eachCount()
                            val propertyTypeCounts = housesInMonth.groupingBy { it.propertyType.name }.eachCount()
                            supabase.from("monthly_summaries").upsert(buildJsonObject {
                                put("agent_id", uid)
                                put("month_year", monthYear)
                                put("treated_count", housesInMonth.count { house ->
                                    (house.treatment.a1 + house.treatment.a2 + house.treatment.b + house.treatment.c + house.treatment.d1 + house.treatment.d2 + house.treatment.e + house.treatment.eliminados) > 0 ||
                                    house.treatment.larvicida > 0.0 || house.treatment.comFoco
                                })
                                put("focus_count", housesInMonth.count { it.treatment.comFoco })
                                put("situation_counts", buildJsonObject { situationCounts.forEach { (k, v) -> put(k, v) } })
                                put("property_type_counts", buildJsonObject { propertyTypeCounts.forEach { (k, v) -> put(k, v) } })
                                put("total_houses", housesInMonth.size)
                                put("days_worked", activitiesInMonth.size)
                            }) { onConflict = "agent_id,month_year" }
                        }

                        // 5. METADADOS do agente
                        supabase.from("agents").update(buildJsonObject {
                            put("last_sync_time", nowIsoUtc())
                            put("app_version_code", (pInfo?.versionCode ?: 0))
                            put("app_version_name", (pInfo?.versionName ?: "Unknown"))
                            if (userEmail != "Remote Sync" && userEmail != "Unknown Email") put("email", userEmail)
                            val photoUrl = supabase.auth.currentUserOrNull()?.let { u ->
                                u.userMetadata?.get("picture")?.toString()?.trim('"')
                            }
                            if (photoUrl != null) put("photo_url", photoUrl)
                            if (officialAgentName.isNotBlank()) put("agent_name", officialAgentName)
                        }) { filter { eq("id", uid) } }

                        // 6. TIMELINE BACKUP: worker Supabase chega na próxima fatia; aqui nada
                        // (nunca escreve no backend errado).
                        Result.success(Unit)
                    } catch (e: Exception) {
                        AppLogger.e("SupabasePush", "Push failed: ${e.message}", e)
                        Result.failure(e)
                    }
                }
            }
        } ?: Result.failure(Exception("Sincronização atingiu o tempo limite. Verifique sua conexão ou tente novamente se o backup for muito grande."))
        return result
    }

    private fun dashToIso(dash: String): String? {
        val m = Regex("^(\\d{2})-(\\d{2})-(\\d{4})$").matchEntire(dash.trim()) ?: return null
        return "${m.groupValues[3]}-${m.groupValues[2]}-${m.groupValues[1]}"
    }
}
