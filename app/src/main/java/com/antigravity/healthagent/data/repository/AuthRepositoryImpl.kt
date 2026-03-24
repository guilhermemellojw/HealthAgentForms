package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.AuthUser
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.antigravity.healthagent.domain.repository.UserRole
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch
import com.google.firebase.firestore.FirebaseFirestore
import com.antigravity.healthagent.domain.repository.AccessRequest
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import com.google.firebase.firestore.Source
import javax.inject.Inject
import javax.inject.Singleton
import com.antigravity.healthagent.domain.repository.SyncRepository


@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val syncRepository: SyncRepository,
    private val settingsManager: com.antigravity.healthagent.data.settings.SettingsManager,
    private val houseDao: com.antigravity.healthagent.data.local.dao.HouseDao,
    private val activityDao: com.antigravity.healthagent.data.local.dao.DayActivityDao
) : AuthRepository {

    private val BOOTSTRAP_ADMINS = listOf("guigomelo9@gmail.com")

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
                        // OPTIMIZATION: Proactively emit cached user data for immediate UI responsiveness
                        val cached = settingsManager.cachedUser.firstOrNull()
                        if (cached != null && cached.uid == firebaseUser.uid) {
                            trySend(cached)
                        }

                        val user = getFullUserData(firebaseUser)
                        trySend(user)

                        firestoreListener?.remove()
                        firestoreListener = firestore.collection("users").document(firebaseUser.uid)
                            .addSnapshotListener { snapshot, error ->
                                if (error != null || snapshot == null) return@addSnapshotListener
                                
                                // Return early if doc doesn't exist (might be a new user)
                                if (!snapshot.exists()) {
                                     // At least we sent the basic user from getFullUserData already
                                     return@addSnapshotListener
                                }

                                val roleStr = snapshot.getString("role") ?: "AGENT"
                                val isAuthorized = snapshot.getBoolean("isAuthorized") ?: false
                                val finalRole = try { UserRole.valueOf(roleStr) } catch(_:Exception){ UserRole.AGENT }
                                
                                val updatedUser = AuthUser(
                                    uid = firebaseUser.uid,
                                    email = firebaseUser.email ?: "",
                                    displayName = snapshot.getString("displayName") ?: firebaseUser.displayName,
                                    photoUrl = firebaseUser.photoUrl?.toString(),
                                    role = finalRole,
                                    isAuthorized = isAuthorized,
                                    agentName = snapshot.getString("agentName")?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                                )
                                trySend(updatedUser)
                                // Proactively update cache with the latest cloud data
                                launch { settingsManager.saveUserProfile(updatedUser) }
                            }
                    } catch (e: Exception) {
                        android.util.Log.e("AuthRepository", "Error fetching user data, trying cache fallback", e)
                        
                        // DEEP FALLBACK: If getFullUserData fails (e.g. timeout + cache error), 
                        // try one last time to get ANYTHING from cache to avoid kicking user out.
                        val lastResortCached = settingsManager.cachedUser.firstOrNull()
                        if (lastResortCached != null && lastResortCached.uid == firebaseUser.uid) {
                             trySend(lastResortCached)
                        } else {
                            // Only if we have ABSOLUTELY NO CACHE, send the generic unauthorized user
                            trySend(AuthUser(
                                uid = firebaseUser.uid,
                                email = firebaseUser.email ?: "",
                                displayName = firebaseUser.displayName,
                                photoUrl = firebaseUser.photoUrl?.toString(),
                                role = UserRole.AGENT,
                                isAuthorized = false
                            ))
                        }
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
        // Final Sync: Try to push any unsynced local data to the cloud before clearing
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            try {
                val firebaseUser = auth.currentUser
                if (firebaseUser != null) {
                    val userProfile = try { getFullUserData(firebaseUser) } catch(e: Exception) { 
                        // Fallback: search settingsManager cache if offline
                        settingsManager.cachedUser.firstOrNull() ?: AuthUser(firebaseUser.uid, firebaseUser.email ?: "", firebaseUser.displayName, null, UserRole.AGENT, false)
                    }
                    val agentName = userProfile.agentName ?: ""
                    val uid = firebaseUser.uid

                    val houses = houseDao.getAllHouses(agentName, uid).first()
                    val activities = activityDao.getAllDayActivities(agentName, uid)
                    
                    if (houses.isNotEmpty() || activities.isNotEmpty()) {
                        android.util.Log.i("AuthRepository", "Performing final sync before logout for $agentName (${houses.size} houses)...")
                        val result = syncRepository.pushLocalDataToCloud(houses, activities, uid)
                        if (result.isSuccess) {
                            android.util.Log.i("AuthRepository", "Final sync successful.")
                        } else {
                            android.util.Log.e("AuthRepository", "Final sync failed: ${result.exceptionOrNull()?.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthRepository", "Error during final sync preparation: ${e.message}", e)
            }
            
            android.util.Log.i("AuthRepository", "Clearing local data and signing out...")
            settingsManager.clearSessionSettings()
            syncRepository.clearLocalData()
            auth.signOut()
        }
    }

    override suspend fun isUserAdmin(): Boolean {
        return try {
            val firebaseUser = auth.currentUser ?: return false
            val user = getFullUserData(firebaseUser)
            user.isAdmin
        } catch (_: Exception) {
            false
        }
    }

    override fun getCurrentUserUid(): String? = auth.currentUser?.uid

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

    private suspend fun getFullUserData(firebaseUser: FirebaseUser): AuthUser {
        val uid = firebaseUser.uid
        val email = (firebaseUser.email ?: "").trim().lowercase()
        
        // 0. CHECK BOOTSTRAP ADMINS
        if (BOOTSTRAP_ADMINS.contains(email)) {
            android.util.Log.i("AuthRepository", "Bootstrap Admin detected: $email")
            // Proactively ensure they are in the 'admins' collection and authorized with timeout
            try {
                withTimeoutOrNull(2000) {
                    firestore.collection("admins").document(uid).set(mapOf("email" to email)).await()
                    firestore.collection("users").document(uid).update(
                        "role", UserRole.ADMIN.name,
                        "isAuthorized", true
                    ).await()
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthRepository", "Failed to persist bootstrap admin status or timed out", e)
                // If update fails, user might not exist yet, try to create with timeout
                try {
                    withTimeoutOrNull(2000) {
                        val newUser = mapOf(
                            "email" to email,
                            "displayName" to firebaseUser.displayName,
                            "role" to UserRole.ADMIN.name,
                            "isAuthorized" to true,
                            "createdAt" to System.currentTimeMillis()
                        )
                        firestore.collection("users").document(uid).set(newUser).await()
                        // Ensure they also have an entry in 'agents' collection for sync consistency
                        firestore.collection("agents").document(uid).set(mapOf(
                            "email" to email,
                            "agentName" to null,
                            "isPreRegistered" to false
                        ), com.google.firebase.firestore.SetOptions.merge()).await()
                    }
                } catch(e2: Exception) {
                    android.util.Log.e("AuthRepository", "Failed to create bootstrap admin profile or timed out", e2)
                }
            }
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

        // 1. Immediate Cache Check
        val cached = settingsManager.cachedUser.firstOrNull()
        if (cached != null && cached.uid == uid) {
             android.util.Log.i("AuthRepository", "Checking for updates for $email (cached role: ${cached.role})")
             // Performance Optimization: If we have a cached user, we can return it if the network is likely off
             // But we still want to try a refresh to ensure roles/permissions are up to date.
        }

        // 2. Combined Network Check: Try to refresh user status and fetch doc in one go
        // Use a single timeout for EVERYTHING network-related to avoid sequential delays
        val userStatus = withTimeoutOrNull(2500) {
            try {
                // Parallel-ish check (await blocks sequentially but within one timeout)
                firebaseUser.reload().await()
                val doc = firestore.collection("users").document(uid).get(Source.SERVER).await()
                val isAdminInColl = firestore.collection("admins").document(uid).get().await().exists()
                Triple(true, doc, isAdminInColl)
            } catch (e: Exception) { 
                android.util.Log.w("AuthRepository", "Online check failed: ${e.message}")
                null 
            }
        }

        val isOnline = userStatus?.first ?: false
        var userDoc = userStatus?.second
        val isAdminInCollection = userStatus?.third ?: false

        if (!isOnline) {
            // OFFLINE: Try Firestore cache immediately with very short timeout
            try {
                userDoc = withTimeoutOrNull(800) { 
                    firestore.collection("users").document(uid).get(Source.CACHE).await() 
                }
            } catch (_: Exception) {}
        }
        
        if (userDoc == null && isAdminInCollection) {
            return getAdminFallback(firebaseUser, uid, email)
        }

        // 5. Migration / Sync check: If user doc is new OR not authorized, check for pre-registered data
        // Only perform migration checks if ONLINE to avoid hanging the offline flow
        if (isOnline && (userDoc == null || !userDoc.exists() || !(userDoc.getBoolean("isAuthorized") ?: false))) {
            try {
                // Use a predictable document ID for pre-registration based on email
                val preDocId = "pre_${email.replace(".", "_").replace("@", "_")}"
                
                var migratedIsAuthorized = userDoc?.getBoolean("isAuthorized") ?: false
                var migratedRole = try { UserRole.valueOf(userDoc?.getString("role") ?: "AGENT") } catch(e:Exception){ UserRole.AGENT }
                var migratedAgentName = userDoc?.getString("agentName")?.takeIf { it.isNotBlank() }
                var migratedCreatedAt = userDoc?.getLong("createdAt") ?: System.currentTimeMillis()
                
                var migrationFound = false

                // 1. Check direct 'users' pre-registration doc
                val preUserDoc = withTimeoutOrNull(3000) { firestore.collection("users").document(preDocId).get().await() }
                if (preUserDoc != null && preUserDoc.exists()) {
                    android.util.Log.i("AuthRepository", "Found pre-registered user profile: $preDocId")
                    migratedRole = try { UserRole.valueOf(preUserDoc.getString("role") ?: "AGENT") } catch(e:Exception){ UserRole.AGENT }
                    migratedIsAuthorized = preUserDoc.getBoolean("isAuthorized") ?: migratedIsAuthorized
                    if (migratedAgentName == null) migratedAgentName = preUserDoc.getString("agentName")?.takeIf { it.isNotBlank() }
                    migratedCreatedAt = preUserDoc.getLong("createdAt") ?: migratedCreatedAt
                    
                    migrationFound = true
                    // Delete old doc
                    firestore.collection("users").document(preDocId).delete()
                }

                // 2. Check direct 'agents' pre-registration doc (from restoration)
                val hasPreAgent = withTimeoutOrNull(3000) { firestore.collection("agents").document(preDocId).get().await().exists() }
                if (hasPreAgent == true) {
                    android.util.Log.i("AuthRepository", "Found pre-registered agent data: $preDocId")
                    
                    // If they have restored data, they are authorized!
                    migratedIsAuthorized = true
                    migrationFound = true
                    
                    // Migrate atomically using the helper
                    migratePreRegistration(email, uid)
                    
                    // After migration, try to get the new document
                    val newAgentDoc = firestore.collection("agents").document(uid).get().await()
                    if (newAgentDoc.exists()) {
                         migratedAgentName = newAgentDoc.getString("agentName")
                    }
                }

                // 3. Apply migration if something was found
                if (migrationFound) {
                    val isAdmin = firestore.collection("admins").document(uid).get().await().exists()
                    val finalRole = if (isAdmin) UserRole.ADMIN else migratedRole
                    val finalIsAuthorized = if (isAdmin) true else migratedIsAuthorized

                    val newUser = mapOf(
                        "email" to email,
                        "displayName" to firebaseUser.displayName,
                        "role" to finalRole.name,
                        "isAuthorized" to finalIsAuthorized,
                        "agentName" to migratedAgentName,
                        "createdAt" to migratedCreatedAt,
                        "updatedAt" to System.currentTimeMillis(),
                        "isPreRegistered" to false
                    )
                    
                    firestore.collection("users").document(uid).set(newUser, com.google.firebase.firestore.SetOptions.merge()).await()
                    userDoc = firestore.collection("users").document(uid).get().await()
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthRepository", "Non-blocking migration error: ${e.message}")
            }
        }

        if (userDoc == null || !userDoc.exists()) {
            // OFFLINE FALLBACK: If we are offline or fetch failed, try local cache
            try {
                val cached = settingsManager.cachedUser.first()
                if (cached != null && cached.uid == uid) {
                    android.util.Log.i("AuthRepository", "Using CACHED user profile for $email")
                    return cached
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthRepository", "Error reading cached user", e)
            }

            // Emergency Fallback: If we couldn't fetch data but it's the admin email, 
            // allow them in as admin anyway.
            val isAdminEmail = if (isOnline) {
                try { firestore.collection("admins").document(uid).get().await().exists() } catch(e: Exception) { false }
            } else false

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
            
            // If still not exists and online, create new user
            var role = UserRole.AGENT
            var isAuthorized = false

            if (isOnline) {
                val isAdmin = try { firestore.collection("admins").document(uid).get().await().exists() } catch(e: Exception) { false }
                role = if (isAdmin) UserRole.ADMIN else UserRole.AGENT
                isAuthorized = isAdmin
                
                val newUser = mapOf(
                    "email" to email,
                    "displayName" to firebaseUser.displayName,
                    "role" to role.name,
                    "isAuthorized" to isAuthorized,
                    "createdAt" to System.currentTimeMillis()
                )
                
                try {
                    firestore.collection("users").document(uid).set(newUser).await()
                    if (role == UserRole.ADMIN) {
                        firestore.collection("admins").document(uid).set(mapOf("email" to email)).await()
                    }
                } catch(e: Exception) {
                    android.util.Log.e("AuthRepository", "Failed to create new user profile offline", e)
                }
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
        
        val roleStr = userDoc?.getString("role") ?: "AGENT"
        var isAuthorized = userDoc?.getBoolean("isAuthorized") ?: false
        val displayName = userDoc?.getString("displayName") ?: firebaseUser.displayName
        val agentName = userDoc?.getString("agentName")
        
        // At this point we are sure userDoc is not null because of the early returns above,
        // but Kotlin needs the safe calls or !! for nullable vars.

        var finalRole = try { UserRole.valueOf(roleStr) } catch (e: Exception) { UserRole.AGENT }

        // Proactive fix for admin
        if (isAdminInCollection && (finalRole != UserRole.ADMIN || !isAuthorized)) {
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
        
        val user = AuthUser(
            uid = uid,
            email = email,
            displayName = displayName,
            photoUrl = firebaseUser.photoUrl?.toString(),
            role = finalRole,
            isAuthorized = isAuthorized,
            agentName = agentName?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        )

        // Proactive ID Recovery: Link any orphaned local data to this real UID
        if (isAuthorized) {
            try {
                houseDao.updateAgentUidForAll(user.agentName ?: "", user.email ?: "", user.uid)
                activityDao.updateAgentUidForAll(user.agentName ?: "", user.email ?: "", user.uid)
            } catch (e: Exception) {
                android.util.Log.e("AuthRepository", "Proactive migration failed", e)
            }
        }

        // Cache the successful profile
        settingsManager.saveUserProfile(user)
        
        return user
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
            
            // 1. Update the target document
            userRef.update("isAuthorized", isAuthorized).await()
            
            // 2. If authorizing a pre-registered user, look for a matching real user to migrate to
            if (isAuthorized && email != null && uid.startsWith("pre_")) {
                val realUsers = firestore.collection("users")
                    .whereEqualTo("email", email)
                    .get().await()
                
                for (realUserDoc in realUsers.documents) {
                    if (realUserDoc.id != uid && !realUserDoc.id.startsWith("pre_")) {
                        val realUid = realUserDoc.id
                        android.util.Log.i("AuthRepository", "Admin authorizing pre-registered user. Auto-migrating to real UID: $realUid")
                        
                        // Authorize the real user too
                        firestore.collection("users").document(realUid).update("isAuthorized", true).await()
                        
                        // Perform migration using Admin permissions
                        migratePreRegistration(email, realUid)
                    }
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun migratePreRegistration(user: AuthUser): Result<Unit> {
        val email = user.email ?: return Result.failure(Exception("Email do usuário não disponível para migração"))
        return migratePreRegistration(email, user.uid)
    }

    internal suspend fun migratePreRegistration(email: String, targetUid: String): Result<Unit> {
        val preDocId = "pre_${email.replace(".", "_").replace("@", "_")}"
        android.util.Log.i("AuthRepository", "Starting atomic migration for $email to $targetUid")
        
        val batch = firestore.batch()
        var preAgentName: String? = null

        // 1. Prepare Metadata Migration (users collection)
        try {
            val preUserDoc = firestore.collection("users").document(preDocId).get().await()
            if (preUserDoc.exists()) {
                val updates = mutableMapOf<String, Any?>()
                preAgentName = preUserDoc.getString("agentName")
                preAgentName?.let { updates["agentName"] = it }
                preUserDoc.getString("role")?.let { updates["role"] = it }
                updates["isAuthorized"] = true
                updates["isPreRegistered"] = false
                
                batch.set(firestore.collection("users").document(targetUid), updates, com.google.firebase.firestore.SetOptions.merge())
                batch.delete(firestore.collection("users").document(preDocId))
            }
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Error preparing metadata migration", e)
        }

        // 2. Prepare Agent Data Migration (agents collection + subcollections)
        try {
            val preAgentDoc = firestore.collection("agents").document(preDocId).get().await()
            if (preAgentDoc.exists()) {
                val agentData = preAgentDoc.data?.toMutableMap() ?: mutableMapOf()
                agentData["isPreRegistered"] = false
                agentData["uid"] = targetUid
                
                val newAgentRef = firestore.collection("agents").document(targetUid)
                batch.set(newAgentRef, agentData, com.google.firebase.firestore.SetOptions.merge())
                batch.delete(firestore.collection("agents").document(preDocId))

                // Migrate subcollections (Houses/Activities)
                // Firestore batch limit is 500. Each sub-doc migration is 2 ops (set+delete).
                // We'll commit the batch whenever it approaches 450 ops and START A NEW ONE.
                var opsInBatch = 2 // Metadata + AgentDoc
                var currentBatch = batch

                val houses = firestore.collection("agents").document(preDocId).collection("houses").get().await()
                for (h in houses) {
                    if (opsInBatch >= 450) {
                        currentBatch.commit().await()
                        currentBatch = firestore.batch()
                        opsInBatch = 0
                    }
                    currentBatch.set(newAgentRef.collection("houses").document(h.id), h.data ?: emptyMap<String, Any>())
                    currentBatch.delete(h.reference)
                    opsInBatch += 2
                }
                
                val acts = firestore.collection("agents").document(preDocId).collection("day_activities").get().await()
                for (a in acts) {
                    if (opsInBatch >= 450) {
                        currentBatch.commit().await()
                        currentBatch = firestore.batch()
                        opsInBatch = 0
                    }
                    currentBatch.set(newAgentRef.collection("day_activities").document(a.id), a.data ?: emptyMap<String, Any>())
                    currentBatch.delete(a.reference)
                    opsInBatch += 2
                }
                
                // Final commit if there are pending ops in the latest batch
                if (opsInBatch > 0) {
                    currentBatch.commit().await()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Error preparing agent data migration", e)
        }

        // 3. Perform Local Migration (Very Important for offline visibility)
        try {
            // We use both name and email to find local records (placeholders) and link to targetUid
            houseDao.updateAgentUidForAll(preAgentName ?: "", email, targetUid)
            activityDao.updateAgentUidForAll(preAgentName ?: "", email, targetUid)
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Local migration failed", e)
        }
        return Result.success(Unit)
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
            
            // Auto-sync and normalize agentName if updated
            val newAgentName = (updates["agentName"] as? String)?.trim()?.uppercase()
            if (newAgentName != null && newAgentName.isNotBlank()) {
                finalUpdates["agentName"] = newAgentName
                syncRepository.addAgentName(newAgentName)
                
                // Propagate to agents collection for data consistency
                try {
                    firestore.collection("agents").document(uid).update("agentName", newAgentName).await()
                } catch(e: Exception) {
                    // Agent doc might not exist yet, ignoring is fine as push will create it later
                }
            }
            
            
            firestore.collection("users").document(uid).update(finalUpdates).await()
            
            // Also ensure agents collection has the basic metadata if it doesn't exist
            val agentMetadata = mutableMapOf<String, Any?>()
            if (newAgentName != null) agentMetadata["agentName"] = newAgentName
            updates["email"]?.let { agentMetadata["email"] = it }
            
            if (agentMetadata.isNotEmpty()) {
                firestore.collection("agents").document(uid).set(agentMetadata, com.google.firebase.firestore.SetOptions.merge()).await()
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
            val docId = "pre_${normalizedEmail.replace(".", "_").replace("@", "_")}"
            
            val normalizedAgentName = agentName?.trim()?.uppercase()
            
            val userData = mutableMapOf(
                "email" to normalizedEmail,
                "role" to role.name,
                "isAuthorized" to isAuthorized,
                "agentName" to normalizedAgentName,
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
            // Robust cleanup: Delete both Auth-related docs and visit data
            // We use syncRepository.deleteAgent (which handles subcollections) to avoid orphaned data
            syncRepository.deleteAgent(uid).onFailure { 
                android.util.Log.e("AuthRepository", "Failed to delete agent data during user deletion: ${it.message}")
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
            val request = mapOf(
                "id" to uid,
                "uid" to uid,
                "email" to email,
                "displayName" to displayName,
                "requestedName" to requestedName,
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
            val doc = firestore.collection("access_requests").document(requestId).get().await()
            val uid = doc.getString("uid") ?: requestId
            val email = doc.getString("email")
            
            val batch = firestore.batch()
            val requestRef = firestore.collection("access_requests").document(requestId)
            batch.update(requestRef, "status", if (approved) "APPROVED" else "REJECTED")
            
            if (approved) {
                val userRef = firestore.collection("users").document(uid)
                val updates = mutableMapOf<String, Any>("isAuthorized" to true)
                
                // Prioritize manual name from admin, then requested name from user, then null
                val finalAgentName = agentName?.takeIf { it.isNotBlank() } 
                    ?: doc.getString("requestedName")?.takeIf { it.isNotBlank() }
                
                if (finalAgentName != null) updates["agentName"] = finalAgentName
                batch.update(userRef, updates)
                
                // If they have a pre-registration, migrate it now using Admin permissions
                if (email != null) {
                    migratePreRegistration(email, uid)
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
                if (error != null) {
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val requests = snapshot.toObjects(AccessRequest::class.java)
                    trySend(requests)
                }
            }
        awaitClose { listener.remove() }
    }
}
