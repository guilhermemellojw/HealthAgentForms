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
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.domain.repository.SyncRepository


@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val syncRepository: SyncRepository,
    private val agentRepository: AgentRepository,
    private val settingsManager: com.antigravity.healthagent.data.settings.SettingsManager,
    private val houseDao: com.antigravity.healthagent.data.local.dao.HouseDao,
    private val activityDao: com.antigravity.healthagent.data.local.dao.DayActivityDao,
    private val workManager: androidx.work.WorkManager
) : AuthRepository {

    private val BOOTSTRAP_ADMINS = listOf("guigomelo9@gmail.com", "gmellobkp@gmail.com")

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
                                
                                try {
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
                                } catch (e: Exception) {
                                    android.util.Log.e("AuthRepository", "Crash prevented in SnapshotListener: ${e.message}", e)
                                }
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
        android.util.Log.i("AuthRepository", "SignOut requested - Performing immediate session clear...")
        
        // 1. CANCEL ACTIVE SYNC WORK
        workManager.cancelAllWorkByTag("sync")
        
        // 2. IMMEDIATE FIREBASE SIGNOUT (Safety First)
        // We sign out here to ensure that any subsequent login immediately sees a fresh context
        auth.signOut()

        // 3. CLEAR SESSION SETTINGS (UI Sync)
        settingsManager.clearSessionSettings()
        
        // 4. ENQUEUE BACKGROUND DATABASE WIPE
        // We still use LogoutWorker to ensure the database is wiped even if the app is killed.
        val logoutRequest = androidx.work.OneTimeWorkRequestBuilder<com.antigravity.healthagent.data.sync.LogoutWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        
        workManager.enqueue(logoutRequest)
    }



    override fun getCurrentUserUid(): String? = auth.currentUser?.uid



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
                            "createdAt" to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                        )
                        firestore.collection("users").document(uid).set(newUser).await()
                        // Ensure they also have an entry in 'agents' collection for sync consistency
                        firestore.collection("agents").document(uid).set(mapOf(
                            "email" to email,
                            "agentName" to null,
                            "photoUrl" to firebaseUser.photoUrl?.toString(),
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
        // 2. Combined Network Check: Try to refresh user status independently
        // We use a single timeout but separate checks to handle partial failures
        val userStatus = withTimeoutOrNull(2500) {
            try {
                // Determine online status via Firebase Auth reload (permission-independent)
                firebaseUser.reload().await()
                
                // Attempt to fetch user document (might fail if rules are strict)
                val doc = try {
                    firestore.collection("users").document(uid).get(Source.SERVER).await()
                } catch (e: Exception) {
                    android.util.Log.w("AuthRepository", "Failed to fetch user doc: ${e.message}")
                    null
                }

                // Attempt to check admin status (likely to fail with PERMISSION_DENIED for non-admins)
                val isAdminInColl = try {
                    firestore.collection("admins").document(uid).get().await().exists()
                } catch (e: Exception) {
                    android.util.Log.w("AuthRepository", "Admin check bypassed/restricted: ${e.message}")
                    false
                }
                
                Triple(true, doc, isAdminInColl)
            } catch (e: Exception) { 
                android.util.Log.e("AuthRepository", "Online check failed entirely: ${e.message}")
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

        // 5. Migration Check: If user doc is new OR not authorized, check for invitations
        // Only perform migration checks if ONLINE to avoid hanging the offline flow
        if (isOnline && (userDoc == null || !userDoc.exists() || !(userDoc.getBoolean("isAuthorized") ?: false))) {
            try {
                // Use a predictable document ID for pre-registration based on email
                val preDocId = "pre_${email.replace(".", "_").replace("@", "_")}"
                android.util.Log.i("AuthRepository", "Checking for pre-registration invitations: $preDocId")
                
                // Fetch the invited profile if it exists
                val preUserDoc = withTimeoutOrNull(3000) { firestore.collection("users").document(preDocId).get().await() }
                
                if (preUserDoc != null && preUserDoc.exists()) {
                    android.util.Log.i("AuthRepository", "INVITATION FOUND: Pre-registered user profile for $email")
                    
                    // CRITICAL: Perform phase 1 migration (Authorization) immediately
                    val migrationResult = migratePreRegistration(email, uid)
                    
                    if (migrationResult.isSuccess) {
                        // After success, re-fetch the now-migrated user document
                         userDoc = withTimeoutOrNull(2000) { firestore.collection("users").document(uid).get().await() }
                    }
                } else {
                    // 2. Check direct 'agents' pre-registration doc (from legacy restoration)
                    val preAgentDoc = withTimeoutOrNull(3000) { firestore.collection("agents").document(preDocId).get().await() }
                    if (preAgentDoc != null && preAgentDoc.exists()) {
                        android.util.Log.i("AuthRepository", "LEGACY DATA FOUND: Pre-registered agent data for $email")
                        migratePreRegistration(email, uid)
                        userDoc = withTimeoutOrNull(2000) { firestore.collection("users").document(uid).get().await() }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AuthRepository", "Migration attempt bypassed: ${e.message}")
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
                    "photoUrl" to firebaseUser.photoUrl?.toString(),
                    "createdAt" to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                )
                
                try {
                    firestore.collection("users").document(uid).set(newUser).await()
                    if (role == UserRole.ADMIN) {
                        firestore.collection("admins").document(uid).set(mapOf("email" to email)).await()
                    }
                    // Ensure basic agent doc exists with photoUrl for supervisor view
                    firestore.collection("agents").document(uid).set(mapOf(
                        "email" to email,
                        "photoUrl" to firebaseUser.photoUrl?.toString(),
                        "isPreRegistered" to false
                    ), com.google.firebase.firestore.SetOptions.merge()).await()
                } catch(e: Exception) {
                    android.util.Log.e("AuthRepository", "Failed to create new user profile", e)
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
                val emailPrefix = user.email?.substringBefore("@")?.uppercase() ?: ""
                val properName = user.standardName
                
                // 1. House Migration & Reclamation
                val misattributedHouses = houseDao.getHousesToReclaim(user.email ?: "", emailPrefix, user.uid, properName)
                for (house in misattributedHouses) {
                    val hasClash = houseDao.checkClash(
                        user.uid, house.data, house.address.blockNumber, house.address.blockSequence, 
                        house.address.streetName, house.address.number, house.address.sequence, house.address.complement, house.address.bairro, house.visitSegment
                    ) > 0
                    
                    if (hasClash) {
                        houseDao.deleteHouseById(house.id)
                    } else {
                        houseDao.updateHouseIdentity(house.id, user.uid, properName)
                    }
                }

                // We already reclaimed houses above during the clash check.

                // 2. Day Activity Migration & Reclamation
                val activitiesToReclaim = activityDao.getActivitiesToReclaim(user.email ?: "", emailPrefix, user.uid, properName)
                if (activitiesToReclaim.isNotEmpty()) {
                    android.util.Log.i("AuthRepository", "Reclaiming ${activitiesToReclaim.size} activities for ${user.email}")
                    activityDao.reclaimActivities(user.displayName ?: "", user.email ?: "", emailPrefix, user.uid)
                }

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



    override suspend fun migratePreRegistration(user: AuthUser): Result<Unit> {
        val email = user.email ?: return Result.failure(Exception("Email do usuário não disponível para migração"))
        return migratePreRegistration(email, user.uid)
    }

    override suspend fun migratePreRegistration(email: String, targetUid: String): Result<Unit> {
        val preDocId = "pre_${email.replace(".", "_").replace("@", "_")}"
        android.util.Log.i("AuthRepository", "Starting atomic migration for $email to $targetUid")
        
        var preAgentName: String? = null

        // 1. Prepare Metadata Migration (users collection)
        try {
            val preUserDoc = firestore.collection("users").document(preDocId).get().await()
            if (preUserDoc.exists()) {
                val metaBatch = firestore.batch()
                val updates = mutableMapOf<String, Any?>()
                preAgentName = preUserDoc.getString("agentName")
                preAgentName?.let { updates["agentName"] = it }
                preUserDoc.getString("role")?.let { updates["role"] = it }
                updates["isAuthorized"] = true
                updates["isPreRegistered"] = false
                
                metaBatch.set(firestore.collection("users").document(targetUid), updates, com.google.firebase.firestore.SetOptions.merge())
                metaBatch.delete(firestore.collection("users").document(preDocId))
                metaBatch.commit().await()
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
                var currentBatch = firestore.batch()
                currentBatch.set(newAgentRef, agentData, com.google.firebase.firestore.SetOptions.merge())
                currentBatch.delete(firestore.collection("agents").document(preDocId))

                // Migrate subcollections (Houses/Activities)
                // Firestore batch limit is 500. Each sub-doc migration is 2 ops (set+delete).
                // We'll commit the batch whenever it approaches 450 ops and START A NEW ONE.
                var opsInBatch = 2 // Metadata + AgentDoc
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
            val emailPrefix = email.substringBefore("@").uppercase()
            val properName = preAgentName?.trim()?.uppercase() ?: ""

            // 1. House Migration
            val housesToReclaim = houseDao.getHousesToReclaim(email, emailPrefix, targetUid, properName)
            for (house in housesToReclaim) {
                val hasClash = houseDao.checkClash(
                    targetUid, house.data, house.address.blockNumber, house.address.blockSequence, 
                    house.address.streetName, house.address.number, house.address.sequence, house.address.complement, house.address.bairro, house.visitSegment
                ) > 0
                
                if (hasClash) {
                    houseDao.deleteHouseById(house.id)
                } else {
                    houseDao.updateHouseIdentity(house.id, targetUid, properName)
                }
            }

            // 2. Activity Migration
            activityDao.reclaimActivities(properName, email, emailPrefix, targetUid)
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Local migration failed", e)
        }
        return Result.success(Unit)
    }

}
