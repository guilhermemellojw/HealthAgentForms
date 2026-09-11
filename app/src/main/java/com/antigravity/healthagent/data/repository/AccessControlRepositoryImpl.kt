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
            // Fase 2: authorizing ONLY flips the flag. Any pending pre-registered
            // data is migrated exclusively through the explicit, admin-confirmed
            // migratePreRegistrationExplicit() flow (never implicitly here).
            firestore.collection("users").document(uid).update("isAuthorized", isAuthorized).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun findPendingPreMigration(targetUid: String): Result<AccessControlRepository.PendingPreMigration?> {
        return try {
            if (targetUid.startsWith("pre_")) return Result.success(null)
            val targetDoc = firestore.collection("users").document(targetUid).get().await()
            if (!targetDoc.exists()) return Result.success(null)
            val email = targetDoc.getString("email") ?: return Result.success(null)
            val normalizedEmail = email.trim().lowercase()
            val preDocId = "pre_${normalizedEmail.replace(".", "_").replace("@", "_")}"
            val preDoc = firestore.collection("users").document(preDocId).get().await()
            if (!preDoc.exists()) return Result.success(null)
            Result.success(
                AccessControlRepository.PendingPreMigration(
                    preUid = preDocId,
                    targetUid = targetUid,
                    preAgentName = preDoc.getString("agentName"),
                    targetAgentName = targetDoc.getString("agentName")
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun migratePreRegistrationExplicit(
        preUid: String,
        targetUid: String,
        resolvedAgentName: String?
    ): Result<Unit> {
        return try {
            if (targetUid.startsWith("pre_")) {
                return Result.failure(Exception("Migração inválida: o destino não pode ser um pré-registro"))
            }
            val preDoc = firestore.collection("users").document(preUid).get().await()
            if (!preDoc.exists()) {
                return Result.failure(Exception("Pré-registro não encontrado (já migrado?)"))
            }
            val targetDoc = firestore.collection("users").document(targetUid).get().await()
            if (!targetDoc.exists()) {
                return Result.failure(Exception("Conta de destino não encontrada"))
            }
            val preEmail = preDoc.getString("email")?.trim()?.lowercase()
            val targetEmail = targetDoc.getString("email")?.trim()?.lowercase()
            if (preEmail == null || targetEmail == null || preEmail != targetEmail) {
                return Result.failure(Exception("E-mails incompatíveis: migração bloqueada"))
            }

            val resolved = resolvedAgentName?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            if (resolved != null) {
                // Strict 1:1: the name must not belong to another UID
                // (the pre_ doc itself is allowed to hold it).
                val ownerResult = isAgentNameTaken(resolved, exceptUid = targetUid)
                if (ownerResult.isFailure) {
                    return Result.failure(ownerResult.exceptionOrNull() ?: Exception("Falha ao validar nome"))
                }
                val owner = ownerResult.getOrNull()
                if (owner != null && owner != preEmail && owner != preUid) {
                    return Result.failure(Exception("Nome já vinculado a $owner"))
                }
            }

            val migration = authRepository.migratePreRegistration(preEmail, targetUid, resolved)
            if (migration.isFailure) {
                return Result.failure(migration.exceptionOrNull() ?: Exception("Falha na migração"))
            }
            android.util.Log.i(
                "AccessControlRepository",
                "Explicit migration confirmed: $preUid -> $targetUid (name=$resolved)"
            )
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

    override suspend fun isEmailTaken(email: String, exceptUid: String?): Result<Boolean> {
        return try {
            val normalizedEmail = email.trim().lowercase()
            if (normalizedEmail.isBlank()) return Result.success(false)
            val preDocId = "pre_${normalizedEmail.replace(".", "_").replace("@", "_")}"
            if (preDocId != exceptUid) {
                val preDoc = firestore.collection("users").document(preDocId).get().await()
                if (preDoc.exists()) return Result.success(true)
            }
            val matches = firestore.collection("users")
                .whereEqualTo("email", normalizedEmail)
                .get().await()
            Result.success(matches.documents.any { it.id != exceptUid })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isAgentNameTaken(agentName: String, exceptUid: String?): Result<String?> {
        return try {
            val normalized = agentName.trim().uppercase()
            if (normalized.isBlank()) return Result.success(null)
            val matches = firestore.collection("users")
                .whereEqualTo("agentName", normalized)
                .get().await()
            val owner = matches.documents.firstOrNull { it.id != exceptUid }
            Result.success(owner?.getString("email") ?: owner?.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateUserProfile(uid: String, updates: Map<String, Any?>): Result<Unit> {
        return try {
            val finalUpdates = updates.toMutableMap()
            val nameKeyPresent = updates.containsKey("agentName")
            val rawName = updates["agentName"] as? String
            val newAgentName = rawName?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            if (nameKeyPresent && newAgentName == null) {
                // Explicit unlink: remove the field instead of writing null
                // (Firestore update() rejects null values).
                finalUpdates["agentName"] = com.google.firebase.firestore.FieldValue.delete()
                try {
                    firestore.collection("agents").document(uid)
                        .update("agentName", com.google.firebase.firestore.FieldValue.delete()).await()
                } catch (e: Exception) { }
            } else if (newAgentName != null) {
                // Strict 1:1: refuse names already linked to another UID.
                val ownerResult = isAgentNameTaken(newAgentName, exceptUid = uid)
                val owner = ownerResult.getOrNull()
                if (ownerResult.isFailure) {
                    return Result.failure(ownerResult.exceptionOrNull() ?: Exception("Falha ao validar nome"))
                }
                if (owner != null) {
                    return Result.failure(Exception("Nome já vinculado a $owner"))
                }
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
            if (normalizedEmail.isBlank()) {
                return Result.failure(Exception("E-mail inválido"))
            }
            val docId = "pre_${normalizedEmail.replace(".", "_").replace("@", "_")}"
            val normalizedAgentName = agentName?.trim()?.uppercase()?.takeIf { it.isNotBlank() }

            // Strict 1:1 backstop (UI pre-validates; repo enforces).
            val emailTaken = isEmailTaken(normalizedEmail).getOrElse { return Result.failure(it) }
            if (emailTaken) {
                return Result.failure(Exception("E-mail já cadastrado"))
            }
            if (normalizedAgentName != null) {
                val owner = isAgentNameTaken(normalizedAgentName).getOrElse { return Result.failure(it) }
                if (owner != null) {
                    return Result.failure(Exception("Nome já vinculado a $owner"))
                }
            }
            
            val userData = mutableMapOf(
                "email" to normalizedEmail,
                "role" to role.name,
                "isAuthorized" to isAuthorized,
                "agentName" to normalizedAgentName,
                "createdAt" to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis(),
                "isPreRegistered" to true
            )
            
            firestore.collection("users").document(docId).set(userData).await()
            if (normalizedAgentName != null) {
                agentRepository.addAgentName(normalizedAgentName)
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
                // Resolve the name FIRST (dialog choice > requestedName), then
                // migrate with it as preferred so the migration never overwrites
                // the admin-confirmed name with the stale pre_ one.
                val finalAgentName = agentName?.takeIf { it.isNotBlank() }
                    ?: doc.getString("requestedName")?.takeIf { it.isNotBlank() }
                val resolvedName = finalAgentName?.trim()?.uppercase()

                if (email != null) {
                    val migration = authRepository.migratePreRegistration(email, uid, resolvedName)
                    if (migration.isFailure) {
                        return Result.failure(
                            migration.exceptionOrNull()
                                ?: Exception("Falha ao migrar pré-registro; aprovação não aplicada")
                        )
                    }
                }

                val userRef = firestore.collection("users").document(uid)
                val updates = mutableMapOf<String, Any>("isAuthorized" to true)

                if (resolvedName != null) updates["agentName"] = resolvedName
                batch.update(userRef, updates)
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
