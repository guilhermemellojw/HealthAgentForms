package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.ui.home.delegates.HomeState
import com.antigravity.healthagent.utils.SoundManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ValidationViewModel @Inject constructor(
    private val houseValidationUseCase: HouseValidationUseCase,
    private val soundManager: SoundManager
) {
    private var validationJob: Job? = null
    private var scrollJob: Job? = null

    fun cancel() {
        validationJob?.cancel()
        validationJob = null
        scrollJob?.cancel()
        scrollJob = null
    }

    fun validateCurrentDay(
        state: HomeState,
        showDialog: Boolean,
        strict: Boolean = true
    ): Boolean {
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
        state: HomeState,
        latestHousesList: () -> List<House>,
        delayMs: Long = 3000
    ) {
        validationJob?.cancel()
        validationJob = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            delay(delayMs)
            validateCurrentDay(state, latestHousesList(), showDialog = false)
        }
    }

    fun onHouseClick(state: HomeState, houseId: Int) {
        state.scrollToHouseId.value = houseId
        state.integrityDialogMessage.value = null
        scrollJob?.cancel()
        scrollJob = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            delay(500)
            state.scrollToHouseId.value = null
        }
    }
}
