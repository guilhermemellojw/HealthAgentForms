package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.domain.repository.AccessControlRepository
import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.domain.repository.AccessRequest
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.domain.logger.AppLogger
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
            
            if (isAuthorized && email != null) {
                val normalizedEmail = email.trim().lowercase()
                val preDocId = "pre_${normalizedEmail.replace(".", "_").replace("@", "_")}"
                
                if (uid == preDocId) {
                    // Admin authorized the pre-registered profile card.
                    // Find the real user account if they have signed up
                    val realUsers = firestore.collection("users")
                        .whereEqualTo("email", normalizedEmail)
                        .get().await()
                    
                    for (realUserDoc in realUsers.documents) {
                        if (realUserDoc.id != uid && !realUserDoc.id.startsWith(com.antigravity.healthagent.utils.AppConstants.PRE_PREFIX)) {
                            val realUid = realUserDoc.id
                            AppLogger.i("AccessControlRepository", "Admin authorizing pre-registered profile. Auto-migrating to real UID: $realUid")
                            firestore.collection("users").document(realUid).update("isAuthorized", true).await()
                            authRepository.migratePreRegistration(normalizedEmail, realUid)
                        }
                    }
                } else if (!uid.startsWith(com.antigravity.healthagent.utils.AppConstants.PRE_PREFIX)) {
                    // Admin authorized the real user card.
                    // Check if there is a pre-registered profile to migrate
                    val preUserDoc = firestore.collection("users").document(preDocId).get().await()
                    if (preUserDoc.exists()) {
                        AppLogger.i("AccessControlRepository", "Admin authorizing real user account. Auto-migrating from pre-registered profile $preDocId")
                        authRepository.migratePreRegistration(normalizedEmail, uid)
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
            val batch = firestore.batch()
            val userRef = firestore.collection("users").document(uid)
            batch.update(userRef, "role", role.name)
            
            val adminRef = firestore.collection("admins").document(uid)
            val supervisorRef = firestore.collection("supervisors").document(uid)
            
            when (role) {
                UserRole.ADMIN -> {
                    val userDoc = userRef.get().await()
                    val email = userDoc.getString("email") ?: ""
                    batch.set(adminRef, mapOf("email" to email.trim().lowercase()))
                    batch.delete(supervisorRef)
                }
                UserRole.SUPERVISOR -> {
                    val userDoc = userRef.get().await()
                    val email = userDoc.getString("email") ?: ""
                    batch.set(supervisorRef, mapOf("email" to email.trim().lowercase()))
                    batch.delete(adminRef)
                }
                else -> {
                    batch.delete(adminRef)
                    batch.delete(supervisorRef)
                }
            }
            
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateUserProfile(uid: String, updates: Map<String, Any?>): Result<Unit> {
        return try {
            val finalUpdates = updates.toMutableMap()
            val nameChange = resolveProfileAgentNameChange(updates)
            when (nameChange) {
                is AgentNameChange.Set -> {
                    finalUpdates["agentName"] = nameChange.name
                    agentRepository.addAgentName(nameChange.name).onFailure { error ->
                        AppLogger.w("AccessControlRepository", "Failed to register agentName ${nameChange.name}: ${error.message}")
                    }
                    if (shouldRenameProduction(uid, nameChange.name)) {
                        renameAgentProduction(uid, nameChange.name)
                    }
                }
                is AgentNameChange.Clear -> {
                    finalUpdates["agentName"] = com.google.firebase.firestore.FieldValue.delete()
                }
                is AgentNameChange.None -> Unit
            }

            firestore.collection("users").document(uid).update(finalUpdates).await()

            val agentMetadata = mutableMapOf<String, Any?>()
            val newAgentName = (nameChange as? AgentNameChange.Set)?.name
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
            val userData = buildPreRegisteredUserData(
                email = normalizedEmail,
                role = role,
                agentName = agentName,
                isAuthorized = isAuthorized,
                createdAt = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
            ).toMutableMap()

            firestore.collection("users").document(docId).set(userData).await()
            (userData["agentName"] as? String)?.takeIf { it.isNotBlank() }?.let {
                agentRepository.addAgentName(it)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteUser(uid: String): Result<Unit> {
        return try {
            agentRepository.purgeAgentCompletely(uid).onFailure { error -> 
                AppLogger.e("AccessControlRepository", "Failed to purge agent data completely: ${error.message}")
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
                AppLogger.w("AccessControlRepository", "Could not ensure user profile: ${e.message}")
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
                
                val finalAgentName = resolveApprovalAgentName(agentName, doc.getString("requestedName"))
                
                if (finalAgentName != null) {
                    updates["agentName"] = finalAgentName
                    try {
                        agentRepository.addAgentName(finalAgentName)
                    } catch (e: Exception) {
                        AppLogger.w("AccessControlRepository", "Failed to register approved agentName $finalAgentName: ${e.message}")
                    }
                }
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

    private suspend fun shouldRenameProduction(uid: String, newName: String): Boolean {
        return try {
            val existing = firestore.collection("agents").document(uid)
                .get(Source.DEFAULT).await()
                .getString("agentName")?.trim()?.uppercase()
            existing != newName
        } catch (e: Exception) {
            AppLogger.w("AccessControlRepository", "Could not read current agentName for $uid, renaming anyway: ${e.message}")
            true
        }
    }

    private suspend fun renameAgentProduction(uid: String, newAgentName: String) {
        try {
            val agentRef = firestore.collection("agents").document(uid)
            renameCollectionField(agentRef.collection("houses"), newAgentName)
            renameCollectionField(agentRef.collection("day_activities"), newAgentName)
            AppLogger.d("AccessControlRepository", "Renamed production for $uid to $newAgentName")
        } catch (e: Exception) {
            AppLogger.e("AccessControlRepository", "Failed to rename production for $uid", e)
        }
    }

    private suspend fun renameCollectionField(ref: com.google.firebase.firestore.CollectionReference, value: String) {
        var lastDoc: com.google.firebase.firestore.DocumentSnapshot? = null
        while (true) {
            val query = if (lastDoc == null) {
                ref.orderBy(com.google.firebase.firestore.FieldPath.documentId()).limit(450).get(Source.SERVER).await()
            } else {
                ref.orderBy(com.google.firebase.firestore.FieldPath.documentId()).startAfter(lastDoc).limit(450).get(Source.SERVER).await()
            }
            if (query.isEmpty) return
            var batch = firestore.batch()
            var ops = 0
            query.documents.forEach { doc ->
                batch.update(doc.reference, "agentName", value)
                ops++
                lastDoc = doc
                if (ops == 450) {
                    batch.commit().await()
                    batch = firestore.batch()
                    ops = 0
                }
            }
            if (ops > 0) batch.commit().await()
        }
    }
}

internal fun buildPreRegisteredUserData(
    email: String,
    role: UserRole,
    agentName: String?,
    isAuthorized: Boolean,
    createdAt: Long
): Map<String, Any?> {
    val normalizedAgentName = agentName?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
    val userData = mutableMapOf<String, Any?>(
        "email" to email,
        "role" to role.name,
        "isAuthorized" to isAuthorized,
        "createdAt" to createdAt,
        "isPreRegistered" to true
    )
    if (normalizedAgentName != null) {
        userData["agentName"] = normalizedAgentName
    }
    return userData
}

internal sealed class AgentNameChange {
    data class Set(val name: String) : AgentNameChange()
    object Clear : AgentNameChange()
    object None : AgentNameChange()
}

internal fun resolveProfileAgentNameChange(updates: Map<String, Any?>): AgentNameChange {
    if (!updates.containsKey("agentName")) return AgentNameChange.None
    val name = (updates["agentName"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
    return if (name != null) AgentNameChange.Set(name) else AgentNameChange.Clear
}

internal fun resolveApprovalAgentName(dialogAgentName: String?, requestedName: String?): String? {
    return dialogAgentName?.takeIf { it.isNotBlank() }?.trim()?.uppercase()
        ?: requestedName?.takeIf { it.isNotBlank() }?.trim()?.uppercase()
}
