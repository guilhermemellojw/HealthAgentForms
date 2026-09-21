package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.remote.supabase.toAnyMap
import com.antigravity.healthagent.data.remote.supabase.toDayTransfer
import com.antigravity.healthagent.data.util.epochMsToIso
import com.antigravity.healthagent.data.util.nowIsoUtc
import com.antigravity.healthagent.data.util.parseRemoteTimestamp
import com.antigravity.healthagent.data.util.toDayActivitySafe
import com.antigravity.healthagent.data.util.toHouseSafe
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.domain.repository.DayTransfer
import com.antigravity.healthagent.domain.repository.DayTransferRepository
import com.antigravity.healthagent.domain.repository.DayTransferStatus
import com.antigravity.healthagent.utils.TimeManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transfers no Supabase — mesma semântica do Firestore, com a state-machine
 * reforçada por trigger+RLS no banco. UIDs aqui são uuids Supabase.
 */
@Singleton
class SupabaseDayTransferRepository @Inject constructor(
    private val supabase: SupabaseClient
) : DayTransferRepository {

    private fun normalizeName(name: String): String =
        name.trim().uppercase().replace(Regex("\\s+"), " ")

    private fun rowToTransfer(row: JsonObject, id: String): DayTransfer {
        return row.toDayTransfer(id)
    }

    private suspend fun fetchRow(transferId: String): Pair<String, Map<String, Any?>>? {
        val row = supabase.from("day_transfers").select {
            filter { eq("id", transferId) }
        }.decodeSingleOrNull<JsonObject>() ?: return null
        return transferId to row.toAnyMap()
    }

    override suspend fun offerDay(
        fromUid: String,
        fromName: String,
        toAgentName: String,
        fromDate: String,
        houseCount: Int
    ): Result<DayTransfer> {
        return try {
            val normalizedDate = fromDate.replace("/", "-")
            val fromNameNorm = normalizeName(fromName)
            val toAgentNorm = normalizeName(toAgentName)
            val docId = "${fromUid}_${normalizedDate}_${toAgentNorm}"

            val existing = fetchRow(docId)
            if (existing != null) {
                val status = (existing.second["status"] as? String) ?: ""
                if (status == DayTransferStatus.PENDING.name) {
                    return Result.failure(Exception("Já existe uma oferta deste dia para $toAgentNorm"))
                }
                // Status final: apaga para re-ofertar (RLS permite delete da origem).
                supabase.from("day_transfers").delete { filter { eq("id", docId) } }
            }

            val now = TimeManager.currentTimeMillis()
            supabase.from("day_transfers").insert(buildJsonObject {
                put("id", docId)
                put("from_agent_id", fromUid)
                put("from_name", fromNameNorm)
                put("to_agent_name", toAgentNorm)
                put("to_key", toAgentNorm)
                put("from_date_text", normalizedDate)
                put("status", DayTransferStatus.PENDING.name)
                put("house_count", houseCount)
                put("offered_at", epochMsToIso(now))
            })
            Result.success(
                DayTransfer(
                    id = docId, fromUid = fromUid, fromName = fromNameNorm,
                    toAgentName = toAgentNorm, toKey = toAgentNorm,
                    fromDate = normalizedDate, status = DayTransferStatus.PENDING.name,
                    houseCount = houseCount, offeredAt = now
                )
            )
        } catch (e: Exception) {
            AppLogger.e("SupabaseTransfer", "Falha ao criar oferta de transferência", e)
            Result.failure(Exception(e.message ?: "Erro ao criar oferta"))
        }
    }

    override suspend fun fetchTransfer(transferId: String): Result<DayTransfer?> {
        return try {
            val row = supabase.from("day_transfers").select {
                filter { eq("id", transferId) }
            }.decodeSingleOrNull<JsonObject>()
            Result.success(row?.let { rowToTransfer(it, transferId) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchOutgoing(fromUid: String): Result<List<DayTransfer>> {
        return try {
            val rows = supabase.from("day_transfers").select {
                filter { eq("from_agent_id", fromUid) }
                order("offered_at", Order.DESCENDING)
                limit(50)
            }.decodeList<JsonObject>()
            Result.success(rows.map {
                val id = ((it["id"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content) ?: ""
                rowToTransfer(it, id)
            }.sortedByDescending { it.offeredAt })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchIncoming(myUid: String, myName: String): Result<List<DayTransfer>> {
        return try {
            val myKey = normalizeName(myName)
            val unique = LinkedHashMap<String, DayTransfer>()
            if (myKey.isNotBlank()) {
                supabase.from("day_transfers").select {
                    filter { eq("status", DayTransferStatus.PENDING.name); eq("to_key", myKey) }
                    limit(50)
                }.decodeList<JsonObject>().forEach { row ->
                    val id = ((row["id"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content) ?: return@forEach
                    val t = rowToTransfer(row, id)
                    if (t.fromUid != myUid) unique[t.id] = t
                }
            }
            if (myUid.isNotBlank()) {
                supabase.from("day_transfers").select {
                    filter { eq("status", DayTransferStatus.PENDING.name); eq("to_agent_id", myUid) }
                    limit(50)
                }.decodeList<JsonObject>().forEach { row ->
                    val id = ((row["id"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content) ?: return@forEach
                    val t = rowToTransfer(row, id)
                    if (t.fromUid != myUid) unique[t.id] = t
                }
            }
            Result.success(unique.values.sortedByDescending { it.offeredAt })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun observeOutgoing(fromUid: String): Flow<List<DayTransfer>> = callbackFlow {
        launch {
            trySend(fetchOutgoing(fromUid).getOrDefault(emptyList()))
        }
        val job = launch {
            try {
                val channel = supabase.channel("transfers-out-$fromUid")
                channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "day_transfers"
                }.collect {
                    trySend(fetchOutgoing(fromUid).getOrDefault(emptyList()))
                }
            } catch (e: Exception) {
                AppLogger.w("SupabaseTransfer", "observeOutgoing realtime failed: ${e.message}")
            }
        }
        awaitClose { job.cancel() }
    }

    override fun observeIncoming(myUid: String, myName: String): Flow<List<DayTransfer>> = callbackFlow {
        launch {
            trySend(fetchIncoming(myUid, myName).getOrDefault(emptyList()))
        }
        val job = launch {
            try {
                val channel = supabase.channel("transfers-in-$myUid")
                channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "day_transfers"
                }.collect {
                    trySend(fetchIncoming(myUid, myName).getOrDefault(emptyList()))
                }
            } catch (e: Exception) {
                AppLogger.w("SupabaseTransfer", "observeIncoming realtime failed: ${e.message}")
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun markAccepted(transferId: String, finalDate: String, toUid: String): Result<Unit> {
        return try {
            supabase.from("day_transfers").update(buildJsonObject {
                put("status", DayTransferStatus.ACCEPTED.name)
                put("final_date_text", finalDate.replace("/", "-"))
                put("to_agent_id", toUid)
                put("accepted_at", nowIsoUtc())
            }) { filter { eq("id", transferId) } }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markDeclined(transferId: String): Result<Unit> {
        return try {
            supabase.from("day_transfers").update(buildJsonObject {
                put("status", DayTransferStatus.DECLINED.name)
            }) { filter { eq("id", transferId) } }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun cancelOffer(transferId: String): Result<Unit> {
        return try {
            supabase.from("day_transfers").update(buildJsonObject {
                put("status", DayTransferStatus.CANCELLED.name)
            }) { filter { eq("id", transferId) } }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteTransfer(transferId: String): Result<Unit> {
        return try {
            supabase.from("day_transfers").delete { filter { eq("id", transferId) } }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchSourceHouses(fromUid: String, fromName: String, fromDate: String): Result<List<House>> {
        return try {
            val dashDate = fromDate.replace("/", "-")
            val rows = supabase.from("houses").select {
                filter { eq("agent_id", fromUid); eq("data_text", dashDate) }
            }.decodeList<JsonObject>()
            val houses = rows.mapNotNull { row ->
                val map = row.toAnyMap()
                val key = map["natural_key"] as? String ?: return@mapNotNull null
                if (map["deleted_at"] != null) return@mapNotNull null
                map.toHouseSafe(key, fromUid, fromName)
            }
            Result.success(houses)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchSourceActivity(fromUid: String, fromName: String, fromDate: String): Result<DayActivity?> {
        return try {
            val normalizedDate = fromDate.replace("/", "-")
            val rows = supabase.from("day_activities").select {
                filter { eq("agent_id", fromUid); eq("date_text", normalizedDate) }
            }.decodeList<JsonObject>()
            val activity = rows.mapNotNull { row ->
                val map = row.toAnyMap()
                if (map["deleted_at"] != null) return@mapNotNull null
                map.toDayActivitySafe(normalizedDate, fromUid, fromName)
            }.firstOrNull()
            Result.success(activity)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
