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

    /**
     * Preview of a pending pre-registration migration for [targetUid].
     * Null when there is no pre_ doc for the target's email.
     */
    data class PendingPreMigration(
        val preUid: String,
        val targetUid: String,
        val preAgentName: String?,
        val targetAgentName: String?
    )

    suspend fun findPendingPreMigration(targetUid: String): Result<PendingPreMigration?>

    // Strict 1:1 link validation (Fase 3 decision).
    // isEmailTaken: true when another doc already uses the normalized email.
    // isAgentNameTaken: owner email/uid when another UID already links the
    // normalized name, null when the name is free.
    suspend fun isEmailTaken(email: String, exceptUid: String? = null): Result<Boolean>
    suspend fun isAgentNameTaken(agentName: String, exceptUid: String? = null): Result<String?>

    /**
     * Explicit, admin-confirmed migration of [preUid] into [targetUid].
     * Never called implicitly: the UI must confirm first (Fase 2 decision).
     * Fails when the emails don't match or [resolvedAgentName] belongs to
     * another UID (strict 1:1).
     */
    suspend fun migratePreRegistrationExplicit(
        preUid: String,
        targetUid: String,
        resolvedAgentName: String?
    ): Result<Unit>
    
    // Authorization Requests
    suspend fun requestAccess(uid: String, email: String, displayName: String?, requestedName: String? = null): Result<Unit>
    suspend fun fetchAccessRequests(): Result<List<AccessRequest>>
    suspend fun fetchAccessRequest(uid: String): Result<AccessRequest?>
    suspend fun respondToAccessRequest(requestId: String, approved: Boolean, agentName: String? = null): Result<Unit>

 
    val pendingAccessRequests: Flow<List<AccessRequest>>
}
