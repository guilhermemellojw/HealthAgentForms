package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.AuthUser
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.antigravity.healthagent.domain.repository.UserRole
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import com.google.firebase.firestore.Source
import javax.inject.Inject
import javax.inject.Singleton
import com.antigravity.healthagent.domain.repository.SyncRepository


@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val syncRepository: SyncRepository,
    private val settingsManager: com.antigravity.healthagent.data.settings.SettingsManager
) : AuthRepository {

    init {
        // Explicitly enable network to ensure Firestore doesn't stay in offline mode
        firestore.enableNetwork()
    }

    override val currentUserAsync: Flow<AuthUser?> = callbackFlow {
        var firestoreListener: com.google.firebase.firestore.ListenerRegistration? = null
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val firebaseUser = firebaseAuth.currentUser
            if (firebaseUser == null) {
                firestoreListener?.remove()
                firestoreListener = null
                trySend(null)
            } else {
                // Fetch full user data in a coroutine
                launch {
                    try {
                        val user = getFullUserData(firebaseUser)
                        trySend(user)

                        firestoreListener?.remove()
                        firestoreListener = firestore.collection("users").document(firebaseUser.uid)
                            .addSnapshotListener { snapshot, error ->
                                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                                
                                val roleStr = snapshot.getString("role") ?: "AGENT"
                                val isAuthorized = snapshot.getBoolean("isAuthorized") ?: false
                                val finalRole = try { UserRole.valueOf(roleStr) } catch(e:Exception){ UserRole.AGENT }
                                
                                val updatedUser = AuthUser(
                                    uid = firebaseUser.uid,
                                    email = firebaseUser.email ?: "",
                                    displayName = snapshot.getString("displayName") ?: firebaseUser.displayName,
                                    photoUrl = firebaseUser.photoUrl?.toString(),
                                    role = finalRole,
                                    isAuthorized = isAuthorized,
                                    agentName = snapshot.getString("agentName")
                                )
                                trySend(updatedUser)
                            }
                    } catch (e: Exception) {
                        android.util.Log.e("AuthRepository", "Error fetching user data", e)
                        // If we can't get Firestore data, we still emit a basic user or null
                        trySend(null)
                    }
                }
            }
        }
        auth.addAuthStateListener(listener)
        awaitClose { 
            auth.removeAuthStateListener(listener)
            firestoreListener?.remove()
        }
    }

    override suspend fun signInWithGoogle(idToken: String): Result<AuthUser> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).await()
            val firebaseUser = authResult.user ?: throw Exception("Login failed: User is null")
            
            val user = getFullUserData(firebaseUser)
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun signOut() {
        settingsManager.clearSessionSettings()
        syncRepository.clearLocalData()
        auth.signOut()
    }

    override suspend fun isUserAdmin(): Boolean {
        return try {
            val firebaseUser = auth.currentUser ?: return false
            val user = getFullUserData(firebaseUser)
            user.isAdmin
        } catch (e: Exception) {
            false
        }
    }

    override fun getCurrentUserUid(): String? {
        return auth.currentUser?.uid
    }

    private suspend fun getFullUserData(firebaseUser: FirebaseUser): AuthUser {
        val uid = firebaseUser.uid
        val email = (firebaseUser.email ?: "").trim().lowercase()
        
        // Check if we actually have internet by reloading the user
        try {
            firebaseUser.reload().await()
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Firebase Auth reload failed", e)
            // If we can't even reload the user, we definitely have network issues
            throw Exception("Não foi possível verificar sua conta. Verifique sua internet.")
        }
        
        var userDoc: com.google.firebase.firestore.DocumentSnapshot? = null
        var lastException: Exception? = null
        
        // Retry logic for connectivity issues
        for (i in 1..3) {
            try {
                // Use default get() which is smarter about connectivity than Source.SERVER
                userDoc = firestore.collection("users").document(uid).get().await()
                if (userDoc != null) break
            } catch (e: Exception) {
                lastException = e
                if (e.message?.contains("permission", ignoreCase = true) == true && 
                    e.message?.contains("denied", ignoreCase = true) == true) {
                    // If it's permission denied, no point in retrying
                    android.util.Log.e("AuthRepository", "Firestore fetch permission denied")
                    break
                }
            }
        }

        // SAFETY FALLBACK: If Firestore failed but it's the admin email, return the fallback
        if (userDoc == null && email == "guigomelo9@gmail.com") {
            return getAdminFallback(firebaseUser, uid, email)
        }

        if (userDoc == null || !userDoc.exists()) {
            // New logic: Check if a user with this email already exists but with a different UID
            try {
                val existingUsers = firestore.collection("users")
                    .whereEqualTo("email", email)
                    .get()
                    .await()
                
                if (!existingUsers.isEmpty) {
                    val oldDoc = existingUsers.documents.first()
                    android.util.Log.i("AuthRepository", "Found existing user with email $email but different UID. Merging...")
                    
                    // Migrated metadata
                    val roleStr = oldDoc.getString("role") ?: "AGENT"
                    val isAuthorized = oldDoc.getBoolean("isAuthorized") ?: false
                    val agentName = oldDoc.getString("agentName") ?: ""
                    
                    val newUser = mapOf(
                        "email" to email,
                        "displayName" to firebaseUser.displayName,
                        "role" to roleStr,
                        "isAuthorized" to isAuthorized,
                        "agentName" to agentName,
                        "createdAt" to (oldDoc.getLong("createdAt") ?: System.currentTimeMillis()),
                        "updatedAt" to System.currentTimeMillis(),
                        "isPreRegistered" to false
                    )
                    
                    firestore.collection("users").document(uid).set(newUser).await()
                    
                    // If user was an admin in old doc, ensure they are in admins collection
                    if (roleStr == "ADMIN") {
                        firestore.collection("admins").document(uid).set(mapOf("email" to email)).await()
                    }

                    // Optionally delete old user document if UID is different
                    if (oldDoc.id != uid) {
                        firestore.collection("users").document(oldDoc.id).delete().await()
                    }
                    
                    // Re-fetch to get consistent state
                    userDoc = firestore.collection("users").document(uid).get().await()
                } else {
                    // Even if no 'users' doc, maybe an 'agents' pre-registration exists?
                    val existingAgents = firestore.collection("agents")
                        .whereEqualTo("email", email)
                        .get()
                        .await()
                    
                    if (!existingAgents.isEmpty) {
                        val oldAgentDoc = existingAgents.documents.first()
                        android.util.Log.i("AuthRepository", "Found pre-registered agent with email $email. Merging metadata and subcollections...")
                        
                        val agentName = oldAgentDoc.getString("agentName") ?: ""

                        if (oldAgentDoc.id != uid) {
                            val agentData = oldAgentDoc.data?.toMutableMap() ?: mutableMapOf()
                            agentData["isPreRegistered"] = false
                            
                            val batch = firestore.batch()
                            val newAgentRef = firestore.collection("agents").document(uid)
                            val oldAgentRef = firestore.collection("agents").document(oldAgentDoc.id)
                            
                            batch.set(newAgentRef, agentData)

                            // Migrate 'houses' subcollection
                            val oldHouses = oldAgentRef.collection("houses").get().await()
                            for (houseDoc in oldHouses.documents) {
                                val newHouseRef = newAgentRef.collection("houses").document(houseDoc.id)
                                houseDoc.data?.let { batch.set(newHouseRef, it) }
                                batch.delete(houseDoc.reference)
                            }

                            // Migrate 'day_activities' subcollection
                            val oldActivities = oldAgentRef.collection("day_activities").get().await()
                            for (activityDoc in oldActivities.documents) {
                                val newActivityRef = newAgentRef.collection("day_activities").document(activityDoc.id)
                                activityDoc.data?.let { batch.set(newActivityRef, it) }
                                batch.delete(activityDoc.reference)
                            }

                            batch.delete(oldAgentRef)
                            batch.commit().await()
                        }
                        
                        // Create user profile based on agent pre-registration
                        val role = if (email == "guigomelo9@gmail.com") UserRole.ADMIN else UserRole.AGENT
                        val isAuthorized = (email == "guigomelo9@gmail.com") 
                        
                        val newUser = mapOf(
                            "email" to email,
                            "displayName" to firebaseUser.displayName,
                            "role" to role.name,
                            "isAuthorized" to isAuthorized,
                            "agentName" to agentName.ifBlank { null },
                            "createdAt" to System.currentTimeMillis(),
                            "isPreRegistered" to false
                        )
                        
                        firestore.collection("users").document(uid).set(newUser).await()
                        if (role == UserRole.ADMIN) {
                            firestore.collection("admins").document(uid).set(mapOf("email" to email)).await()
                        }
                        
                        // Re-fetch
                        userDoc = firestore.collection("users").document(uid).get().await()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthRepository", "Error during email-based user merge", e)
            }
        }

        if (userDoc == null || !userDoc.exists()) {
            // Emergency Fallback: If we couldn't fetch data but it's the admin email, 
            // allow them in as admin anyway.
            val isAdminEmail = email == "guigomelo9@gmail.com"
            if (isAdminEmail) {
                android.util.Log.w("AuthRepository", "EMERGENCY: Admin mode via email match (Doc not found)")
                return AuthUser(
                    uid = uid,
                    email = email,
                    displayName = firebaseUser.displayName,
                    photoUrl = firebaseUser.photoUrl?.toString(),
                    role = UserRole.ADMIN,
                    isAuthorized = true,
                    agentName = null
                )
            }
            
            // If still not exists, create new user
            val role = if (email == "guigomelo9@gmail.com") UserRole.ADMIN else UserRole.AGENT
            val isAuthorized = email == "guigomelo9@gmail.com"
            
            val newUser = mapOf(
                "email" to email,
                "displayName" to firebaseUser.displayName,
                "role" to role.name,
                "isAuthorized" to isAuthorized,
                "createdAt" to System.currentTimeMillis()
            )
            
            firestore.collection("users").document(uid).set(newUser).await()
            if (role == UserRole.ADMIN) {
                firestore.collection("admins").document(uid).set(mapOf("email" to email)).await()
            }
            
            return AuthUser(
                uid = uid,
                email = email,
                displayName = firebaseUser.displayName,
                photoUrl = firebaseUser.photoUrl?.toString(),
                role = role,
                isAuthorized = isAuthorized,
                agentName = null
            )
        }
        
        val roleStr = userDoc.getString("role") ?: "AGENT"
        var isAuthorized = userDoc.getBoolean("isAuthorized") ?: false
        val displayName = userDoc.getString("displayName") ?: firebaseUser.displayName
        val agentName = userDoc.getString("agentName")

        val isAdminEmail = email == "guigomelo9@gmail.com"
        var finalRole = try { UserRole.valueOf(roleStr) } catch (e: Exception) { UserRole.AGENT }

        // Proactive fix for admin
        if (isAdminEmail && (finalRole != UserRole.ADMIN || !isAuthorized)) {
            try {
                firestore.collection("users").document(uid).update(
                    "role", UserRole.ADMIN.name,
                    "isAuthorized", true
                ).await()
                firestore.collection("admins").document(uid).set(mapOf("email" to email)).await()
                finalRole = UserRole.ADMIN
                isAuthorized = true
            } catch (e: Exception) {
                android.util.Log.e("AuthRepository", "Failed to self-correct admin permissions", e)
            }
        }
        
        return AuthUser(
            uid = uid,
            email = email,
            displayName = displayName,
            photoUrl = firebaseUser.photoUrl?.toString(),
            role = finalRole,
            isAuthorized = isAuthorized,
            agentName = agentName
        )
    }

    private suspend fun getAdminFallback(firebaseUser: FirebaseUser, uid: String, email: String): AuthUser {
        android.util.Log.i("AuthRepository", "Returning safety admin fallback for $email")
        return AuthUser(
            uid = uid,
            email = email,
            displayName = firebaseUser.displayName,
            photoUrl = firebaseUser.photoUrl?.toString(),
            role = UserRole.ADMIN,
            isAuthorized = true,
            agentName = null
        )
    }

    override suspend fun fetchAllUsers(): Result<List<AuthUser>> {
        return try {
            val snapshot = firestore.collection("users").get().await()
            val users = snapshot.documents.map { doc ->
                AuthUser(
                    uid = doc.id,
                    email = doc.getString("email"),
                    displayName = doc.getString("displayName"),
                    photoUrl = null,
                    role = try { UserRole.valueOf(doc.getString("role") ?: "AGENT") } catch (e: Exception) { UserRole.AGENT },
                    isAuthorized = doc.getBoolean("isAuthorized") ?: false,
                    agentName = doc.getString("agentName")
                )
            }
            Result.success(users)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun authorizeUser(uid: String, isAuthorized: Boolean): Result<Unit> {
        return try {
            firestore.collection("users").document(uid).update("isAuthorized", isAuthorized).await()
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
            firestore.collection("users").document(uid).update(updates).await()
            
            // Auto-sync agentName to metadata if updated
            val newAgentName = updates["agentName"] as? String
            if (newAgentName != null && newAgentName.isNotBlank()) {
                syncRepository.addAgentName(newAgentName)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createUserProfile(
        email: String,
        role: UserRole,
        agentName: String?,
        isAuthorized: Boolean
    ): Result<Unit> {
        return try {
            val normalizedEmail = email.trim().lowercase()
            val docId = "pre_${normalizedEmail.hashCode()}"
            
            val userData = mutableMapOf(
                "email" to normalizedEmail,
                "role" to role.name,
                "isAuthorized" to isAuthorized,
                "agentName" to agentName,
                "createdAt" to System.currentTimeMillis(),
                "isPreRegistered" to true
            )
            
            firestore.collection("users").document(docId).set(userData).await()
            
            // Auto-sync agentName to metadata if provided
            if (agentName != null && agentName.isNotBlank()) {
                syncRepository.addAgentName(agentName)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteUser(uid: String): Result<Unit> {
        return try {
            // Also clean up agent data (houses, activities) to prevent ghost data
            syncRepository.deleteAgent(uid)
            
            val batch = firestore.batch()
            batch.delete(firestore.collection("users").document(uid))
            batch.delete(firestore.collection("admins").document(uid))
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun requestAccess(uid: String, email: String, displayName: String?): Result<Unit> {
        return try {
            val request = mapOf(
                "id" to uid,
                "uid" to uid,
                "email" to email,
                "displayName" to displayName,
                "timestamp" to System.currentTimeMillis(),
                "status" to "PENDING"
            )
            firestore.collection("access_requests").document(uid).set(request).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchAccessRequests(): Result<List<com.antigravity.healthagent.domain.repository.AccessRequest>> {
        return try {
            val snapshot = firestore.collection("access_requests")
                .whereEqualTo("status", "PENDING")
                .get()
                .await()
            val requests = snapshot.toObjects(com.antigravity.healthagent.domain.repository.AccessRequest::class.java)
            Result.success(requests)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun respondToAccessRequest(requestId: String, approved: Boolean, agentName: String?): Result<Unit> {
        return try {
            val status = if (approved) "APPROVED" else "REJECTED"
            val requestDoc = firestore.collection("access_requests").document(requestId).get().await()
            val uid = requestDoc.getString("uid") ?: requestId
            
            val batch = firestore.batch()
            batch.update(firestore.collection("access_requests").document(requestId), "status", status)
            
            if (approved) {
                val userRef = firestore.collection("users").document(uid)
                val updates = mutableMapOf<String, Any>("isAuthorized" to true)
                if (agentName != null) {
                    updates["agentName"] = agentName
                    syncRepository.addAgentName(agentName)
                }
                batch.update(userRef, updates)
            }
            
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
