package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.util.toHouseSafe
import com.antigravity.healthagent.data.util.toDayActivitySafe
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.domain.repository.DayTransfer
import com.antigravity.healthagent.domain.repository.DayTransferStatus
import com.antigravity.healthagent.domain.repository.DayTransferRepository
import com.antigravity.healthagent.utils.TimeManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DayTransferRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) : DayTransferRepository {

    private fun transfers() = firestore.collection("day_transfers")

    private fun normalizeName(name: String): String =
        name.trim().uppercase().replace(Regex("\\s+"), " ")

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
            val existingDoc = firestore.collection("day_transfers").document(docId).get().await()
            if (existingDoc.exists()) {
                val existingStatus = existingDoc.getString("status")
                if (existingStatus == DayTransferStatus.PENDING.name) {
                    return Result.failure(Exception("Já existe uma oferta deste dia para $toAgentNorm"))
                }
                // Doc determinístico com status FINAL (transferência já concluída,
                // recusada, cancelada) — o set() em cima da mesma id viraria update
                // (que só aceita transições de PENDING), então apagamos para re-ofertar.
                firestore.collection("day_transfers").document(docId).delete().await()
            }

            val now = TimeManager.currentTimeMillis()
            val data = mapOf(
                "fromUid" to fromUid,
                "fromName" to fromNameNorm,
                "toUid" to "",
                "toAgentName" to toAgentNorm,
                "toKey" to toAgentNorm,
                "fromDate" to normalizedDate,
                "finalDate" to "",
                "status" to DayTransferStatus.PENDING.name,
                "houseCount" to houseCount,
                "offeredAt" to now,
                "acceptedAt" to 0L
            )
            firestore.collection("day_transfers").document(docId).set(data).await()

            Result.success(
                DayTransfer(
                    id = docId,
                    fromUid = fromUid,
                    fromName = fromNameNorm,
                    toAgentName = toAgentNorm,
                    toKey = toAgentNorm,
                    fromDate = normalizedDate,
                    status = DayTransferStatus.PENDING.name,
                    houseCount = houseCount,
                    offeredAt = now
                )
            )
        } catch (e: Exception) {
            AppLogger.e("DayTransferRepository", "Falha ao criar oferta de transferência", e)
            val message = if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true) {
                "Permissão negada. Verifique se está logado e com internet."
            } else {
                e.message ?: "Erro ao criar oferta"
            }
            Result.failure(Exception(message))
        }
    }

    override suspend fun fetchTransfer(transferId: String): Result<DayTransfer?> {
        return try {
            val doc = transfers().document(transferId).get().await()
            if (!doc.exists()) return Result.success(null)
            Result.success(doc.toObject(DayTransfer::class.java)?.copy(id = doc.id))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchOutgoing(fromUid: String): Result<List<DayTransfer>> {
        return try {
            val snapshot = transfers()
                .whereEqualTo("fromUid", fromUid)
                .orderBy("offeredAt", Query.Direction.DESCENDING)
                .get().await()
            Result.success(mapDocuments(snapshot.documents))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchIncoming(myUid: String, myName: String): Result<List<DayTransfer>> {
        return try {
            val myKey = normalizeName(myName)
            val sources = mutableListOf<Query>()
            if (myKey.isNotBlank()) {
                sources.add(
                    transfers()
                        .whereEqualTo("status", DayTransferStatus.PENDING.name)
                        .whereEqualTo("toKey", myKey)
                )
            }
            if (myUid.isNotBlank()) {
                sources.add(
                    transfers()
                        .whereEqualTo("status", DayTransferStatus.PENDING.name)
                        .whereEqualTo("toUid", myUid)
                )
            }
            val unique = LinkedHashMap<String, DayTransfer>()
            for (query in sources) {
                val snapshot = query.get().await()
                snapshot.documents.forEach { doc ->
                    val transfer = doc.toObject(DayTransfer::class.java)?.copy(id = doc.id)
                    if (transfer != null && transfer.fromUid != myUid) {
                        unique[transfer.id] = transfer
                    }
                }
            }
            Result.success(unique.values.sortedByDescending { it.offeredAt })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun observeOutgoing(fromUid: String): Flow<List<DayTransfer>> = callbackFlow {
        val listener = transfers()
            .whereEqualTo("fromUid", fromUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                snapshot?.let { trySend(mapDocuments(it.documents)) }
            }
        awaitClose { listener.remove() }
    }

    override fun observeIncoming(myUid: String, myName: String): Flow<List<DayTransfer>> = callbackFlow {
        val myKey = normalizeName(myName)
        val listener = if (myKey.isNotBlank()) {
            transfers()
                .whereEqualTo("status", DayTransferStatus.PENDING.name)
                .whereEqualTo("toKey", myKey)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    snapshot?.let {
                        val filtered = mapDocuments(it.documents).filter { t -> t.fromUid != myUid }
                        trySend(filtered)
                    }
                }
        } else {
            transfers()
                .whereEqualTo("status", DayTransferStatus.PENDING.name)
                .whereEqualTo("toUid", myUid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    snapshot?.let { trySend(mapDocuments(it.documents)) }
                }
        }
        awaitClose { listener.remove() }
    }

    override suspend fun markAccepted(transferId: String, finalDate: String, toUid: String): Result<Unit> {
        return try {
            val updates = mapOf(
                "status" to DayTransferStatus.ACCEPTED.name,
                "finalDate" to finalDate.replace("/", "-"),
                "toUid" to toUid,
                "acceptedAt" to TimeManager.currentTimeMillis()
            )
            transfers().document(transferId).update(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markDeclined(transferId: String): Result<Unit> {
        return try {
            transfers().document(transferId).update("status", DayTransferStatus.DECLINED.name).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun cancelOffer(transferId: String): Result<Unit> {
        return try {
            transfers().document(transferId).update("status", DayTransferStatus.CANCELLED.name).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteTransfer(transferId: String): Result<Unit> {
        return try {
            transfers().document(transferId).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchSourceHouses(fromUid: String, fromName: String, fromDate: String): Result<List<House>> {
        return try {
            val dashDate = fromDate.replace("/", "-")
            val slashDate = fromDate.replace("-", "/")
            val snapshot = firestore.collection("agents")
                .document(fromUid).collection("houses")
                .whereIn("data", listOf(dashDate, slashDate).distinct())
                .get().await()
            val houses = snapshot.documents.mapNotNull { it.toHouseSafe(fromUid, fromName) }
            Result.success(houses)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchSourceActivity(fromUid: String, fromName: String, fromDate: String): Result<DayActivity?> {
        return try {
            val normalizedDate = fromDate.replace("/", "-")
            val snapshot = firestore.collection("agents")
                .document(fromUid).collection("day_activities")
                .whereEqualTo("date", normalizedDate)
                .get().await()
            val activity = snapshot.documents
                .mapNotNull { it.toDayActivitySafe(fromUid, fromName) }
                .firstOrNull()
            Result.success(activity)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun mapDocuments(documents: List<com.google.firebase.firestore.DocumentSnapshot>): List<DayTransfer> {
        val list = mutableListOf<DayTransfer>()
        documents.forEach { doc ->
            doc.toObject(DayTransfer::class.java)?.let { list.add(it.copy(id = doc.id)) }
        }
        return list.sortedByDescending { it.offeredAt }
    }
}