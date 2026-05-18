package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.domain.repository.AccessControlRepository
import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.domain.repository.AccessRequest
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessControlRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val authRepository: AuthRepository,
    private val agentRepository: AgentRepository
) : AccessControlRepository {

    override suspend fun isUserAdmin(): Boolean {
        return try {
            val firebaseUser = auth.currentUser ?: return false
            val uid = firebaseUser.uid
            val doc = firestore.collection("admins").document(uid).get().await()
            doc.exists()
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun fetchAllUsers(): Result<List<AuthUser>> {
        return try {
            val snapshot = firestore.collection("users").get().await()
            val users = snapshot.documents.map { doc ->
                AuthUser(
                    uid = doc.id,
                    email = doc.getString("email"),
                    displayName = doc.getString("displayName"),
                    photoUrl = doc.getString("photoUrl"),
                    role = try { UserRole.valueOf(doc.getString("role") ?: "AGENT") } catch (e: Exception) { UserRole.AGENT },
                    isAuthorized = doc.getBoolean("isAuthorized") ?: false,
                    agentName = doc.getString("agentName")?.uppercase()
                )
            }
            Result.success(users)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun authorizeUser(uid: String, isAuthorized: Boolean): Result<Unit> {
        return try {
            val userRef = firestore.collection("users").document(uid)
            val userDoc = userRef.get().await()
            val email = userDoc.getString("email")
            
            userRef.update("isAuthorized", isAuthorized).await()
            
            if (isAuthorized && email != null && uid.startsWith("pre_")) {
                val realUsers = firestore.collection("users")
                    .whereEqualTo("email", email)
                    .get().await()
                
                for (realUserDoc in realUsers.documents) {
                    if (realUserDoc.id != uid && !realUserDoc.id.startsWith("pre_")) {
                        val realUid = realUserDoc.id
                        android.util.Log.i("AccessControlRepository", "Admin authorizing pre-registered user. Auto-migrating to real UID: $realUid")
                        
                        firestore.collection("users").document(realUid).update("isAuthorized", true).await()
                        authRepository.migratePreRegistration(email, realUid)
                    }
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun changeUserRole(uid: String, role: UserRole): Result<Unit> {
        return try {
            firestore.collection("users").document(uid).update("role", role.name).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateUserProfile(uid: String, updates: Map<String, Any?>): Result<Unit> {
        return try {
            val finalUpdates = updates.toMutableMap()
            val newAgentName = (updates["agentName"] as? String)?.trim()?.uppercase()
            if (newAgentName != null && newAgentName.isNotBlank()) {
                finalUpdates["agentName"] = newAgentName
                agentRepository.addAgentName(newAgentName)
                try {
                    firestore.collection("agents").document(uid).update("agentName", newAgentName).await()
                } catch(e: Exception) { }
            }
            
            firestore.collection("users").document(uid).update(finalUpdates).await()
            
            val agentMetadata = mutableMapOf<String, Any?>()
            if (newAgentName != null) agentMetadata["agentName"] = newAgentName
            updates["email"]?.let { agentMetadata["email"] = it }
            updates["photoUrl"]?.let { agentMetadata["photoUrl"] = it }
            
            if (agentMetadata.isNotEmpty()) {
                firestore.collection("agents").document(uid).set(agentMetadata, com.google.firebase.firestore.SetOptions.merge()).await()
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createUserProfile(email: String, role: UserRole, agentName: String?, isAuthorized: Boolean): Result<Unit> {
        return try {
            val normalizedEmail = email.trim().lowercase()
            val docId = "pre_${normalizedEmail.replace(".", "_").replace("@", "_")}"
            val normalizedAgentName = agentName?.trim()?.uppercase()
            
            val userData = mutableMapOf(
                "email" to normalizedEmail,
                "role" to role.name,
                "isAuthorized" to isAuthorized,
                "agentName" to normalizedAgentName,
                "createdAt" to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis(),
                "isPreRegistered" to true
            )
            
            firestore.collection("users").document(docId).set(userData).await()
            if (agentName != null && agentName.isNotBlank()) {
                agentRepository.addAgentName(agentName)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteUser(uid: String): Result<Unit> {
        return try {
            agentRepository.deleteAgent(uid).onFailure { error -> 
                android.util.Log.e("AccessControlRepository", "Failed to delete agent data: ${error.message}")
            }

            val batch = firestore.batch()
            batch.delete(firestore.collection("users").document(uid))
            batch.delete(firestore.collection("admins").document(uid))
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun requestAccess(uid: String, email: String, displayName: String?, requestedName: String?): Result<Unit> {
        return try {
            val normalizedEmail = email.trim().lowercase()
            val normalizedName = requestedName?.trim()?.uppercase()
            
            try {
                val userRef = firestore.collection("users").document(uid)
                val userDoc = userRef.get(Source.SERVER).await()
                if (!userDoc.exists()) {
                    val newUser = mapOf(
                        "email" to normalizedEmail,
                        "displayName" to displayName,
                        "role" to UserRole.AGENT.name,
                        "isAuthorized" to false,
                        "createdAt" to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                    )
                    userRef.set(newUser).await()
                }
            } catch (e: Exception) {
                android.util.Log.w("AccessControlRepository", "Could not ensure user profile: ${e.message}")
            }

            val request = mapOf(
                "uid" to uid,
                "email" to normalizedEmail,
                "displayName" to displayName,
                "requestedName" to normalizedName,
                "timestamp" to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis(),
                "status" to "PENDING"
            )
            firestore.collection("access_requests").document(uid).set(request).await()
            Result.success(Unit)
        } catch (e: Exception) {
            val message = if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true) {
                "Permissão negada pelo servidor."
            } else {
                e.message ?: "Erro ao enviar solicitação"
            }
            Result.failure(Exception(message))
        }
    }

    override suspend fun fetchAccessRequests(): Result<List<AccessRequest>> {
        return try {
            val snapshot = firestore.collection("access_requests")
                .whereEqualTo("status", "PENDING")
                .get()
                .await()
            val requests = snapshot.toObjects(AccessRequest::class.java)
            Result.success(requests)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchAccessRequest(uid: String): Result<AccessRequest?> {
        return try {
            val doc = firestore.collection("access_requests").document(uid).get().await()
            if (doc.exists()) {
                Result.success<AccessRequest?>(doc.toObject(AccessRequest::class.java))
            } else {
                Result.success<AccessRequest?>(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun respondToAccessRequest(requestId: String, approved: Boolean, agentName: String?): Result<Unit> {
        return try {
            val doc = firestore.collection("access_requests").document(requestId).get().await()
            val uid = doc.getString("uid") ?: requestId
            val email = doc.getString("email")
            
            val batch = firestore.batch()
            val requestRef = firestore.collection("access_requests").document(requestId)
            batch.update(requestRef, "status", if (approved) "APPROVED" else "REJECTED")
            
            if (approved) {
                val userRef = firestore.collection("users").document(uid)
                val updates = mutableMapOf<String, Any>("isAuthorized" to true)
                
                val finalAgentName = agentName?.takeIf { it.isNotBlank() } 
                    ?: doc.getString("requestedName")?.takeIf { it.isNotBlank() }
                
                if (finalAgentName != null) updates["agentName"] = finalAgentName.trim().uppercase()
                batch.update(userRef, updates)
                
                if (email != null) {
                    authRepository.migratePreRegistration(email, uid)
                }
            }
            
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override val pendingAccessRequests: Flow<List<AccessRequest>> = callbackFlow {
        val listener = firestore.collection("access_requests")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                if (snapshot != null) {
                    val requests = snapshot.toObjects(AccessRequest::class.java)
                    trySend(requests)
                }
            }
        awaitClose { listener.remove() }
    }
}
