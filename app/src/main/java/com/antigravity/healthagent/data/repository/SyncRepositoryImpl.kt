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
    private val firestore: FirebaseFirestore
) : SyncRepository {

    override suspend fun pushLocalDataToCloud(
        houses: List<House>,
        activities: List<DayActivity>
    ): Result<Unit> {
        return try {
            val user = auth.currentUser ?: return Result.failure(Exception("User not authenticated"))
            val uid = user.uid
            val userEmail = user.email ?: "Unknown Email"

            val batch = firestore.batch()
            val userDocRef = firestore.collection("agents").document(uid)

            // 1. Update basic agent metadata
            val metadata = mapOf(
                "email" to userEmail,
                "lastSyncTime" to System.currentTimeMillis()
            )
            batch.set(userDocRef, metadata)

            // 2. Clear old collections? Standard Firestore doesn't easily let you delete a whole collection via batch without querying it first.
            // For this app, simply overwriting the documents using their IDs works as an "upsert".
            
            val housesCollection = userDocRef.collection("houses")
            for (house in houses) {
                // We use House.id.toString() as the document ID for Upsert
                val docRef = housesCollection.document(house.id.toString())
                batch.set(docRef, house)
            }

            val activitiesCollection = userDocRef.collection("day_activities")
            for (activity in activities) {
                // We use "${date}_${agentName}" since primary key is compound
                val docId = "${activity.date}_${activity.agentName}"
                val docRef = activitiesCollection.document(docId)
                batch.set(docRef, activity)
            }

            // Execute the batch
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
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
            Result.failure(e)
        }
    }
}
