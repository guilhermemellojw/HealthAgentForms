package com.antigravity.healthagent.domain.repository

import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val currentUserAsync: Flow<AuthUser?>
    
    suspend fun signInWithGoogle(idToken: String): Result<AuthUser>
    suspend fun signOut()
    suspend fun isUserAdmin(): Boolean
    
    // User Management for Admins
    suspend fun fetchAllUsers(): Result<List<AuthUser>>
    suspend fun authorizeUser(uid: String, isAuthorized: Boolean): Result<Unit>
    suspend fun changeUserRole(uid: String, role: UserRole): Result<Unit>
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
    val isAuthorized: Boolean = false
) {
    val isAdmin: Boolean get() = role == UserRole.ADMIN
    val isSupervisor: Boolean get() = role == UserRole.SUPERVISOR
}
