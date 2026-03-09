package com.antigravity.healthagent.domain.repository

import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val currentUserAsync: Flow<AuthUser?>
    
    suspend fun signInWithGoogle(idToken: String): Result<AuthUser>
    suspend fun signOut()
    suspend fun isUserAdmin(): Boolean
}

data class AuthUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val isAdmin: Boolean = false
)
