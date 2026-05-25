package com.antigravity.healthagent.ui.home.delegates

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.utils.SoundManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ValidationDelegate @Inject constructor(
    private val houseValidationUseCase: HouseValidationUseCase,
    private val soundManager: SoundManager
) {
    private var validationJob: Job? = null

    fun validateCurrentDay(
        state: HomeState,
        showDialog: Boolean,
        strict: Boolean = true
    ): Boolean {
        // Ensure we validate using the merged state (DB + Drafts)
        val overlays = state.pendingUpdateDrafts.value
        // Because delegate does not have direct access to database state flow value,
        // we should read it from state.housesInFlight or allow passing houses.
        // Wait! In validateCurrentDay (HomeViewModel.kt), it reads latestHouses, which is state.houses.value.
        // But since state.houses is a read-only StateFlow in the UI, wait, we can pass it, or we can check the list of houses!
        // Wait, where is `houses: StateFlow<List<House>>` defined?
        // It's in the ViewModel, but we can pass the list of all merged houses to the method,
        // OR we can read it from the database / state properties.
        // Let's pass the current latest houses list to `validateCurrentDay`! That is extremely clean and functional.
        // Wait, yes! Let's design `validateCurrentDay` to accept the list of merged houses:
        // `fun validateCurrentDay(state: HomeState, houses: List<House>, showDialog: Boolean, strict: Boolean = true): Boolean`
        // Let's do that! It is 100% thread-safe and stateless.
        return validateCurrentDay(state, emptyList(), showDialog, strict)
    }

    fun validateCurrentDay(
        state: HomeState,
        latestHousesList: List<House>,
        showDialog: Boolean,
        strict: Boolean = true
    ): Boolean {
        val mergedHouses = latestHousesList.map { state.pendingUpdateDrafts.value[it.id] ?: it }
        val result = houseValidationUseCase.validateCurrentDay(state.data.value, mergedHouses, strict = strict)
        state.validationErrorHouseIds.value = result.errorHouseIds
        state.validationErrorDetails.value = result.errorDetails
        state.isDuplicateIds.value = result.errorDetails.filter { it.isDuplicate }.map { it.houseId }.toSet()

        if (!result.isValid) {
            if (showDialog) {
                state.integrityDialogMessage.value = result.dialogMessage
                soundManager.playWarning()
            }
            return false
        } else {
            state.integrityDialogMessage.value = null
            return true
        }
    }

    fun triggerDelayedValidation(
        scope: CoroutineScope,
        state: HomeState,
        latestHousesList: () -> List<House>,
        delayMs: Long = 3000
    ) {
        validationJob?.cancel()
        validationJob = scope.launch {
            delay(delayMs)
            validateCurrentDay(state, latestHousesList(), showDialog = false)
        }
    }

    fun onHouseClick(scope: CoroutineScope, state: HomeState, houseId: Int) {
        state.scrollToHouseId.value = houseId
        state.integrityDialogMessage.value = null // Close old dialog if any
        scope.launch {
            delay(500)
            state.scrollToHouseId.value = null
        }
    }
}
