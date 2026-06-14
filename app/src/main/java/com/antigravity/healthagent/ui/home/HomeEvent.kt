package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House

/**
 * Sealed interface representing all user intentions/events from the HomeScreen.
 */
sealed interface HomeEvent {
    // Sync actions
    data object SyncDataToCloud : HomeEvent
    data object PullDataFromCloud : HomeEvent
    data object GenerateMockData : HomeEvent
    data class FinishEditSession(val onComplete: () -> Unit) : HomeEvent

    // Date/Day Navigation actions
    data class MoveDate(val forward: Boolean) : HomeEvent
    data object SelectToday : HomeEvent
    data class OnDateSelected(val date: String) : HomeEvent
    data class NavigateToErroneousDay(val date: String) : HomeEvent

    // House Mutation actions
    data object AddNewHouse : HomeEvent
    data class AddNewHouseAt(val positionId: Int) : HomeEvent
    data class UpdateHouse(val house: House) : HomeEvent
    data class DeleteHouse(val house: House) : HomeEvent
    data object RestoreDeletedHouse : HomeEvent
    data class MoveHouse(val house: House, val moveUp: Boolean) : HomeEvent
    data class MoveHouseToDate(val house: House, val destinationDate: String) : HomeEvent
    data object ConfirmMoveHouse : HomeEvent
    data object DismissMoveConfirmation : HomeEvent
    data class PersistListOrder(val houses: List<House>) : HomeEvent
    data class OnHouseClick(val houseId: Int) : HomeEvent

    // Day Closing/Locking actions
    data object ToggleDayLock : HomeEvent
    data object ConfirmUnlockHistory : HomeEvent
    data object DismissHistoryUnlockConfirmation : HomeEvent
    data object DismissGoalReached : HomeEvent
    data object AdvanceToNextDay : HomeEvent
    data object StartDayClosingFlow : HomeEvent
    data class ConfirmAndCloseDay(val audit: AuditSummary) : HomeEvent
    data object DismissClosingAudit : HomeEvent

    // Header actions
    data class UpdateHeader(
        val municipality: String,
        val neighborhood: String,
        val category: String,
        val zone: String,
        val type: Int,
        val data: String,
        val cycle: String,
        val activity: Int
    ) : HomeEvent

    // Search query actions
    data class UpdateSearchQuery(val query: String) : HomeEvent

    // Dismiss dialog / confirmation actions
    data object DismissIntegrityDialog : HomeEvent
    data object DismissMultiDayErrorDialog : HomeEvent
    data object DismissSituationLimitConfirmation : HomeEvent
    data object ConfirmDuplicateMerge : HomeEvent
    data object DismissDuplicateConfirmation : HomeEvent
    data object ClearUiEvent : HomeEvent

    // Deduplication actions
    data object DeduplicateCurrentDay : HomeEvent
}
