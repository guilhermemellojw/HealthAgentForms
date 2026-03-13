package com.antigravity.healthagent.domain.repository

import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val currentUserAsync: Flow<AuthUser?>
    
    suspend fun signInWithGoogle(idToken: String): Result<AuthUser>
    suspend fun signOut()
    suspend fun isUserAdmin(): Boolean
    fun getCurrentUserUid(): String?
    
    // User Management for Admins
    suspend fun fetchAllUsers(): Result<List<AuthUser>>
    suspend fun authorizeUser(uid: String, isAuthorized: Boolean): Result<Unit>
    suspend fun changeUserRole(uid: String, role: UserRole): Result<Unit>
    suspend fun updateUserProfile(uid: String, updates: Map<String, Any?>): Result<Unit>
    suspend fun createUserProfile(email: String, role: UserRole, agentName: String?, isAuthorized: Boolean): Result<Unit>
    suspend fun deleteUser(uid: String): Result<Unit>
    
    // Authorization Requests
    suspend fun requestAccess(uid: String, email: String, displayName: String?): Result<Unit>
    suspend fun fetchAccessRequests(): Result<List<AccessRequest>>
    suspend fun respondToAccessRequest(requestId: String, approved: Boolean, agentName: String? = null): Result<Unit>
}

enum class UserRole {
    AGENT,
    SUPERVISOR,
    ADMIN
}

data class AuthUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val role: UserRole = UserRole.AGENT,
    val isAuthorized: Boolean = false,
    val agentName: String? = null
) {
    val isAdmin: Boolean get() = role == UserRole.ADMIN
    val isSupervisor: Boolean get() = role == UserRole.SUPERVISOR
}

data class AccessRequest(
    val id: String = "",
    val uid: String = "",
    val email: String = "",
    val displayName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "PENDING"
)
