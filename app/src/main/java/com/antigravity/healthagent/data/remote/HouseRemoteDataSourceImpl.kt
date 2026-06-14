package com.antigravity.healthagent.data.remote

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.util.toHouseSafe
import com.antigravity.healthagent.data.util.toDayActivitySafe
import com.antigravity.healthagent.data.util.toFirestoreMap
import com.antigravity.healthagent.utils.CollectionNames
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HouseRemoteDataSourceImpl @Inject constructor(
    private val firestore: FirebaseFirestore
) : HouseRemoteDataSource {

    override suspend fun fetchHousesInRange(uid: String, dates: List<String>, agentName: String): Result<List<House>> {
        return try {
            val agentRef = firestore.collection(CollectionNames.AGENTS).document(uid)
            val housesAccumulated = mutableListOf<House>()
            dates.chunked(30).forEach { batch ->
                if (batch.isNotEmpty()) {
                    val houseDocs = agentRef.collection(CollectionNames.HOUSES)
                        .whereIn("data", batch)
                        .get().await()
                    housesAccumulated.addAll(houseDocs.documents.mapNotNull { it.toHouseSafe(uid, agentName) })
                }
            }
            Result.success(housesAccumulated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchActivitiesInRange(uid: String, dates: List<String>, agentName: String): Result<List<DayActivity>> {
        return try {
            val agentRef = firestore.collection(CollectionNames.AGENTS).document(uid)
            val activitiesAccumulated = mutableListOf<DayActivity>()
            dates.chunked(30).forEach { batch ->
                if (batch.isNotEmpty()) {
                    val activityDocs = agentRef.collection(CollectionNames.DAY_ACTIVITIES)
                        .whereIn("date", batch)
                        .get().await()
                    activitiesAccumulated.addAll(activityDocs.documents.mapNotNull { it.toDayActivitySafe(uid, agentName) })
                }
            }
            Result.success(activitiesAccumulated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAgentHouse(uid: String, houseId: String, monthYear: String?): Result<Unit> {
        return try {
            val agentRef = firestore.collection(CollectionNames.AGENTS).document(uid)
            val houseRef = agentRef.collection(CollectionNames.HOUSES).document(houseId)
            val batch = firestore.batch()
            
            batch.delete(houseRef)
            
            batch.update(agentRef, 
                mapOf(
                    "deleted_house_ids" to FieldValue.arrayUnion(houseId),
                    "lastSyncTime" to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                )
            )
            
            if (monthYear != null) {
                batch.delete(agentRef.collection(CollectionNames.MONTHLY_SUMMARIES).document(monthYear))
            }
            
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAgentActivity(uid: String, activityDate: String, monthYear: String?): Result<Unit> {
        return try {
            val agentRef = firestore.collection(CollectionNames.AGENTS).document(uid)
            val dateKey = activityDate.replace("/", "-")
            val docRef = agentRef.collection(CollectionNames.DAY_ACTIVITIES).document(dateKey)
            
            val activityDateDashed = dateKey.replace("/", "-")
            val houses = agentRef.collection(CollectionNames.HOUSES)
                .whereEqualTo("data", activityDateDashed)
                .get().await()
            
            val batch = firestore.batch()
            batch.delete(docRef)
            houses.documents.forEach { batch.delete(it.reference) }
            
            batch.update(agentRef, "deleted_activity_dates", FieldValue.arrayUnion(dateKey))
            
            if (monthYear != null) {
                batch.delete(agentRef.collection(CollectionNames.MONTHLY_SUMMARIES).document(monthYear))
            }

            batch.update(agentRef, "lastSyncTime", System.currentTimeMillis())
            
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun transferAgentData(fromUid: String, toUid: String, targetAgentName: String): Result<Unit> {
        return try {
            val fromRef = firestore.collection(CollectionNames.AGENTS).document(fromUid)
            val toRef = firestore.collection(CollectionNames.AGENTS).document(toUid)
            
            val houses = fromRef.collection(CollectionNames.HOUSES).get().await()
            val activities = fromRef.collection(CollectionNames.DAY_ACTIVITIES).get().await()
            
            val totalOps = houses.documents.size + activities.documents.size
            if (totalOps == 0) return Result.success(Unit)

            val transferredHouseIds = mutableListOf<String>()
            val transferredDates = mutableSetOf<String>()

            houses.documents.chunked(100).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { docSnapshot ->
                    val houseObj = docSnapshot.toHouseSafe(toUid, targetAgentName)
                    if (houseObj != null) {
                        val newKey = houseObj.generateNaturalKey()
                        val targetDocRef = toRef.collection(CollectionNames.HOUSES).document(newKey)
                        
                        batch.set(targetDocRef, houseObj.toFirestoreMap())
                        batch.delete(docSnapshot.reference)
                        
                        transferredHouseIds.add(docSnapshot.id)
                        transferredDates.add(houseObj.data.replace("/", "-"))
                    }
                }
                batch.commit().await()
            }

            activities.documents.chunked(100).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { docSnapshot ->
                    val activityObj = docSnapshot.toDayActivitySafe(toUid, targetAgentName)
                    if (activityObj != null) {
                        val dateKey = activityObj.date.replace("/", "-")
                        val targetDocRef = toRef.collection(CollectionNames.DAY_ACTIVITIES).document(dateKey)
                        
                        batch.set(targetDocRef, activityObj.toFirestoreMap())
                        batch.delete(docSnapshot.reference)
                        
                        transferredDates.add(dateKey)
                    }
                }
                batch.commit().await()
            }

            if (transferredHouseIds.isNotEmpty() || transferredDates.isNotEmpty()) {
                val updates = mutableMapOf<String, Any>()
                if (transferredHouseIds.isNotEmpty()) {
                    updates["deleted_house_ids"] = FieldValue.arrayUnion(*transferredHouseIds.toTypedArray())
                }
                if (transferredDates.isNotEmpty()) {
                    updates["deleted_activity_dates"] = FieldValue.arrayUnion(*transferredDates.toTypedArray())
                }
                fromRef.update(updates).await()
            }

            toRef.update("lastSyncTime", System.currentTimeMillis()).await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun observeAgentProduction(
        uid: String,
        agentName: String,
        datePattern: String?
    ): Flow<Pair<List<House>, List<DayActivity>>> = callbackFlow {
        val agentRef = firestore.collection(CollectionNames.AGENTS).document(uid)
        val currentHouses = MutableStateFlow<List<House>>(emptyList())
        val currentActivities = MutableStateFlow<List<DayActivity>>(emptyList())

        fun sendUpdate() {
            trySend(Pair(currentHouses.value, currentActivities.value))
        }

        val housesListener = agentRef.collection(CollectionNames.HOUSES)
            .orderBy("lastUpdated", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                if (snapshot != null) {
                    val houses = snapshot.documents.mapNotNull { it.toHouseSafe(uid, agentName) }
                    val filteredHouses = if (datePattern != null) {
                        val cleanSuffix = datePattern.removePrefix("-")
                        houses.filter { it.data.contains(cleanSuffix) }
                    } else houses
                    currentHouses.value = filteredHouses
                    sendUpdate()
                }
            }

        val activitiesListener = agentRef.collection(CollectionNames.DAY_ACTIVITIES)
            .orderBy("lastUpdated", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(10)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                if (snapshot != null) {
                    val activities = snapshot.documents.mapNotNull { it.toDayActivitySafe(uid, agentName) }
                    currentActivities.value = activities
                    sendUpdate()
                }
            }

        awaitClose {
            housesListener.remove()
            activitiesListener.remove()
        }
    }
}
