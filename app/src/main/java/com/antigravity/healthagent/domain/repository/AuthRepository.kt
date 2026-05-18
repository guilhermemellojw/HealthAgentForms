package com.antigravity.healthagent.domain.repository

import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val currentUserAsync: Flow<AuthUser?>
    
    suspend fun signInWithGoogle(idToken: String): Result<AuthUser>
    suspend fun signOut()
    fun getCurrentUserUid(): String?
    suspend fun migratePreRegistration(user: AuthUser): Result<Unit>
    suspend fun migratePreRegistration(email: String, targetUid: String): Result<Unit>
    
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
    val agentName: String? = null,
    val requireDataReset: Boolean = false
) {
    val isAdmin: Boolean get() = role == UserRole.ADMIN
    val isSupervisor: Boolean get() = role == UserRole.SUPERVISOR
    val standardName: String get() = (agentName?.takeIf { it.isNotBlank() } 
        ?: displayName?.takeIf { it.isNotBlank() } 
        ?: email?.substringBefore("@")?.takeIf { it.isNotBlank() } 
        ?: "AGENTE").uppercase()
}

data class AccessRequest(
    val id: String = "",
    val uid: String = "",
    val email: String = "",
    val displayName: String? = null,
    val requestedName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "PENDING"
)
