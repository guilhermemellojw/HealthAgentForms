package com.antigravity.healthagent.domain.usecase

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized role-based access enforcement.
 *
 * Replaces 4+ duplicate role-check blocks in HomeViewModel:
 * addNewHouse, addNewHouseAt, deleteHouse, deduplicateCurrentDay.
 *
 * Enforces the rule that non-admin supervisors cannot modify remote agent data.
 */
@Singleton
class RoleEnforcer @Inject constructor() {

    /**
     * Result of a role enforcement check.
     */
    sealed interface RoleResult {
        /** The action is allowed. */
        data object Allowed : RoleResult

        /** The action is blocked due to insufficient permissions. */
        data class Blocked(val message: String) : RoleResult
    }

    /**
     * Checks whether the current user has permission to perform a data-modifying action.
     *
     * Rule: Non-admin supervisors viewing a remote agent's data cannot add/delete/modify.
     *
     * @param isSupervisor Whether the user has supervisor role.
     * @param isAdmin Whether the user has admin role (supersedes supervisor restrictions).
     * @param actionDescription Human-readable action name for the error message.
     * @return [RoleResult.Allowed] if permitted, [RoleResult.Blocked] otherwise.
     */
    fun enforce(
        isSupervisor: Boolean,
        isAdmin: Boolean,
        actionDescription: String = "modificar dados remotamente"
    ): RoleResult {
        if (!isSupervisor) return RoleResult.Allowed
        if (isAdmin) return RoleResult.Allowed

        return RoleResult.Blocked("Apenas administradores podem $actionDescription.")
    }
}
