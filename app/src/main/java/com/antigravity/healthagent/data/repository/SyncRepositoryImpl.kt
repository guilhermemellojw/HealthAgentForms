package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val houseDao: com.antigravity.healthagent.data.local.dao.HouseDao,
    private val dayActivityDao: com.antigravity.healthagent.data.local.dao.DayActivityDao
) : SyncRepository {

    override suspend fun pushLocalDataToCloud(
        houses: List<House>,
        activities: List<DayActivity>
    ): Result<Unit> {
        return try {
            val user = auth.currentUser ?: return Result.failure(Exception("User not authenticated"))
            val uid = user.uid
            val userEmail = user.email ?: "Unknown Email"

            val userDocRef = firestore.collection("agents").document(uid)
            val metadata = mapOf(
                "email" to userEmail,
                "lastSyncTime" to System.currentTimeMillis()
            )

            // Combine all operations into a list to chunk them
            val operations = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Any>>()
            
            // 1. Metadata op
            operations.add(userDocRef to metadata)

            // 2. Houses
            val housesCollection = userDocRef.collection("houses")
            for (house in houses) {
                operations.add(housesCollection.document(house.id.toString()) to house)
            }

            // 3. Activities
            val activitiesCollection = userDocRef.collection("day_activities")
            for (activity in activities) {
                val docId = "${activity.date}_${activity.agentName}"
                operations.add(activitiesCollection.document(docId) to activity)
            }

            android.util.Log.d("SyncRepository", "Starting sync: ${operations.size} operations")

            // Execute in chunks of 400 to stay well within the 500 limit
            operations.chunked(400).forEachIndexed { index, chunk ->
                val batch = firestore.batch()
                for ((docRef, data) in chunk) {
                    batch.set(docRef, data)
                }
                
                android.util.Log.d("SyncRepository", "Committing batch ${index + 1}/${(operations.size + 399) / 400}")
                
                // Increase timeout for large batches
                kotlinx.coroutines.withTimeout(30000L) {
                    batch.commit().await()
                }
            }

            Result.success(Unit)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            android.util.Log.e("SyncRepository", "Sync timeout", e)
            Result.failure(Exception("Tempo limite excedido ao sincronizar. Verifique sua conexão."))
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "Sync error: ${e.message}", e)
            if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true) {
                Result.failure(Exception("Acesso negado ao sincronizar. Verifique se sua conta foi autorizada e se as regras de segurança do Firestore permitem a escrita."))
            } else {
                Result.failure(e)
            }
        }
    }

    override suspend fun fetchAllAgentsData(): Result<List<AgentData>> {
        return try {
            val agentsSnapshot = firestore.collection("agents").get().await()
            val allAgents = mutableListOf<AgentData>()

            for (agentDoc in agentsSnapshot.documents) {
                val email = agentDoc.getString("email") ?: "Unknown"
                val lastSyncTime = agentDoc.getLong("lastSyncTime") ?: 0L
                val uid = agentDoc.id

                val housesSnapshot = agentDoc.reference.collection("houses").get().await()
                val houses = housesSnapshot.toObjects(House::class.java)

                val activitiesSnapshot = agentDoc.reference.collection("day_activities").get().await()
                val activities = activitiesSnapshot.toObjects(DayActivity::class.java)

                allAgents.add(
                    AgentData(
                        uid = uid,
                        email = email,
                        houses = houses,
                        activities = activities,
                        lastSyncTime = lastSyncTime
                    )
                )
            }
            Result.success(allAgents)
        } catch (e: Exception) {
            if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true) {
                Result.failure(Exception("Acesso negado ao carregar dados dos agentes. Verifique se você é um administrador e se as regras do Firestore permitem a leitura desta coleção."))
            } else {
                Result.failure(e)
            }
        }
    }

    override suspend fun pullCloudDataToLocal(): Result<Unit> {
        return try {
            val user = auth.currentUser ?: return Result.failure(Exception("User not authenticated"))
            val uid = user.uid

            val userDocRef = firestore.collection("agents").document(uid)
            
            // 1. Fetch Houses
            val housesSnapshot = userDocRef.collection("houses").get().await()
            val houses = housesSnapshot.toObjects(House::class.java)

            // 2. Fetch Activities
            val activitiesSnapshot = userDocRef.collection("day_activities").get().await()
            val activities = activitiesSnapshot.toObjects(DayActivity::class.java)

            // 3. Update Room (Replace all if it's a new device sync)
            if (houses.isNotEmpty() || activities.isNotEmpty()) {
                houseDao.replaceHouses(houses)
                dayActivityDao.replaceDayActivities(activities)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true) {
                Result.failure(Exception("Acesso negado ao baixar dados. Verifique se sua conta foi autorizada e se as regras do Firestore permitem a leitura."))
            } else {
                Result.failure(e)
            }
        }
    }
}
