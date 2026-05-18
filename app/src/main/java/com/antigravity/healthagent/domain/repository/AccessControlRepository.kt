package com.antigravity.healthagent.domain.repository

import kotlinx.coroutines.flow.Flow

interface AccessControlRepository {
    suspend fun isUserAdmin(): Boolean

    // User Management for Admins
    suspend fun fetchAllUsers(): Result<List<AuthUser>>
    suspend fun authorizeUser(uid: String, isAuthorized: Boolean): Result<Unit>
    suspend fun changeUserRole(uid: String, role: UserRole): Result<Unit>
    suspend fun updateUserProfile(uid: String, updates: Map<String, Any?>): Result<Unit>
    suspend fun createUserProfile(email: String, role: UserRole, agentName: String?, isAuthorized: Boolean): Result<Unit>
    suspend fun deleteUser(uid: String): Result<Unit>
    
    // Authorization Requests
    suspend fun requestAccess(uid: String, email: String, displayName: String?, requestedName: String? = null): Result<Unit>
    suspend fun fetchAccessRequests(): Result<List<AccessRequest>>
    suspend fun fetchAccessRequest(uid: String): Result<AccessRequest?>
    suspend fun respondToAccessRequest(requestId: String, approved: Boolean, agentName: String? = null): Result<Unit>

 
    val pendingAccessRequests: Flow<List<AccessRequest>>
}
