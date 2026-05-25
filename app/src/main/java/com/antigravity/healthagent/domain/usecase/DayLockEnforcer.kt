package com.antigravity.healthagent.domain.usecase

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized day-lock enforcement.
 *
 * Replaces 8+ duplicate lock-check blocks scattered across HomeViewModel methods:
 * addNewHouse, addNewHouseAt, updateHouse, deleteHouse, restoreDeletedHouse,
 * moveHouseToDate, moveHouse, persistListOrder.
 *
 * All mutations that affect house data on a given day must pass through this enforcer
 * before proceeding.
 */
@Singleton
class DayLockEnforcer @Inject constructor() {

    /**
     * Result of a lock enforcement check.
     */
    sealed interface LockResult {
        /** The action is allowed to proceed. */
        data object Allowed : LockResult

        /** The action is blocked because the day is closed. */
        data class Blocked(val message: String) : LockResult
    }

    /**
     * Checks whether a mutation is allowed on the given day.
     *
     * @param isDayClosed Whether the day is currently closed/locked.
     * @param isManualUnlock Whether the day has been manually unlocked.
     * @param isAdmin Whether the current user has admin privileges.
     * @param actionDescription Human-readable description of the blocked action (for error messages).
     * @return [LockResult.Allowed] if the mutation can proceed, [LockResult.Blocked] otherwise.
     */
    fun enforce(
        isDayClosed: Boolean,
        isManualUnlock: Boolean,
        isAdmin: Boolean,
        actionDescription: String = "editar"
    ): LockResult {
        if (!isDayClosed) return LockResult.Allowed
        if (isManualUnlock) return LockResult.Allowed
        if (isAdmin) return LockResult.Allowed

        return LockResult.Blocked("Este dia está FECHADO. Desbloqueie para $actionDescription.")
    }
}
