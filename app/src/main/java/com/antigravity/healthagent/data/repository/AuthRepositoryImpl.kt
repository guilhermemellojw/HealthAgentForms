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

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : AuthRepository {

    init {
        // Explicitly enable network to ensure Firestore doesn't stay in offline mode
        firestore.enableNetwork()
    }

    override val currentUserAsync: Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val firebaseUser = firebaseAuth.currentUser
            if (firebaseUser == null) {
                trySend(null)
            } else {
                // Fetch full user data in a coroutine
                launch {
                    try {
                        val user = getFullUserData(firebaseUser)
                        trySend(user)
                    } catch (e: Exception) {
                        android.util.Log.e("AuthRepository", "Error fetching user data", e)
                        // If we can't get Firestore data, we still emit a basic user or null
                        trySend(null)
                    }
                }
            }
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
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

    private suspend fun getFullUserData(firebaseUser: FirebaseUser): AuthUser {
        val uid = firebaseUser.uid
        val email = (firebaseUser.email ?: "").trim().lowercase()
        
        // IMMEDIATE ADMIN FALLBACK: If this is the admin email, return early.
        // This ensures they can ALWAYS log in even if Firestore is completely blocked.
        if (email == "guigomelo9@gmail.com") {
            android.util.Log.i("AuthRepository", "Immediate admin fallback triggered for $email")
            return AuthUser(
                uid = uid,
                email = email,
                displayName = firebaseUser.displayName,
                photoUrl = firebaseUser.photoUrl?.toString(),
                role = UserRole.ADMIN,
                isAuthorized = true
            )
        }

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

        if (userDoc == null) {
            // Emergency Fallback: If we couldn't fetch data due to permissions but it's the admin email, 
            // allow them in as admin anyway. This is safe because Firebase Auth already verified their identity.
            val isAdminEmail = email.trim().lowercase() == "guigomelo9@gmail.com"
            android.util.Log.d("AuthRepository", "Fallback check: email='$email', isAdminEmail=$isAdminEmail")
            
            if (isAdminEmail) {
                android.util.Log.w("AuthRepository", "EMERGENCY: Entering admin mode via email match due to Firestore failure")
                return AuthUser(
                    uid = uid,
                    email = email,
                    displayName = firebaseUser.displayName,
                    photoUrl = firebaseUser.photoUrl?.toString(),
                    role = UserRole.ADMIN,
                    isAuthorized = true
                )
            }
            throw lastException ?: Exception("Não foi possível conectar ao servidor de dados. Se persistir, contate o administrador.")
        }
        
        if (userDoc.exists()) {
            var roleStr = userDoc.getString("role") ?: "AGENT"
            var isAuthorized = userDoc.getBoolean("isAuthorized") ?: false
            val displayName = userDoc.getString("displayName") ?: firebaseUser.displayName

            val isAdminEmail = email.trim().lowercase() == "guigomelo9@gmail.com"
            // Proactive fix: If this is the admin email but not authorized/admin in Firestore, fix it.
            if (isAdminEmail && (roleStr != "ADMIN" || !isAuthorized)) {
                try {
                    val updates = mapOf(
                        "role" to UserRole.ADMIN.name,
                        "isAuthorized" to true
                    )
                    firestore.collection("users").document(uid).update(updates).await()
                    firestore.collection("admins").document(uid).set(mapOf("email" to email)).await()
                    
                    roleStr = UserRole.ADMIN.name
                    isAuthorized = true
                    android.util.Log.i("AuthRepository", "Admin permissions self-corrected for $email")
                } catch (e: Exception) {
                    android.util.Log.e("AuthRepository", "Failed to self-correct admin permissions: ${e.message}", e)
                    // Continue anyway, the return below will use the updated variables if they were set,
                    // or the original ones if the update failed but we are still in this block.
                }
            }
            
            return AuthUser(
                uid = uid,
                email = email,
                displayName = displayName,
                photoUrl = firebaseUser.photoUrl?.toString(),
                role = try { UserRole.valueOf(roleStr) } catch (e: Exception) { UserRole.AGENT },
                isAuthorized = isAuthorized
            )
        } else {
            // 2. Initial Registration Logic
            var role = UserRole.AGENT
            var isAuthorized = false

            val isAdminEmail = email.trim().lowercase() == "guigomelo9@gmail.com"
            // Auto-grant Admin for specific email
            if (isAdminEmail) {
                role = UserRole.ADMIN
                isAuthorized = true
            }

            val newUser = mapOf(
                "email" to email,
                "displayName" to firebaseUser.displayName,
                "role" to role.name,
                "isAuthorized" to isAuthorized,
                "createdAt" to System.currentTimeMillis()
            )

            // Save to Firestore
            try {
                firestore.collection("users").document(uid).set(newUser).await()
                // If it's an admin, also keep the 'admins' collection for legacy/security rules if needed
                if (role == UserRole.ADMIN) {
                    firestore.collection("admins").document(uid).set(mapOf("email" to email)).await()
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthRepository", "Failed to register new user in Firestore: ${e.message}", e)
                
                // Emergency Fallback even for new registration
                val isAdminEmail = email.trim().lowercase() == "guigomelo9@gmail.com"
                if (isAdminEmail) {
                    android.util.Log.w("AuthRepository", "EMERGENCY: Admin registered via email match despite registration error")
                    return AuthUser(
                        uid = uid,
                        email = email,
                        displayName = firebaseUser.displayName,
                        photoUrl = firebaseUser.photoUrl?.toString(),
                        role = UserRole.ADMIN,
                        isAuthorized = true
                    )
                }

                if (e.message?.contains("offline") == true) {
                    throw Exception("O Firestore está em modo offline. Verifique sua conexão.")
                }
                if (e.message?.contains("permission", ignoreCase = true) == true) {
                    throw Exception("Acesso negado. Sua conta (${email}) não tem permissão para usar este aplicativo.")
                }
                throw e
            }

            return AuthUser(
                uid = uid,
                email = email,
                displayName = firebaseUser.displayName,
                photoUrl = firebaseUser.photoUrl?.toString(),
                role = role,
                isAuthorized = isAuthorized
            )
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
                    photoUrl = null,
                    role = try { UserRole.valueOf(doc.getString("role") ?: "AGENT") } catch (e: Exception) { UserRole.AGENT },
                    isAuthorized = doc.getBoolean("isAuthorized") ?: false
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
}
