package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import javax.inject.Inject

class CheckWorkedHouseLimitUseCase @Inject constructor() {

    operator fun invoke(
        house: House,
        original: House?,
        dayHouses: List<House>,
        currentDate: String,
        maxOpenHouses: Int,
        isAdmin: Boolean,
        isManualUnlock: Boolean
    ): Result {
        val isWorked = house.situation == Situation.NONE || house.situation == Situation.EMPTY
        val wasWorked = original != null && (original.situation == Situation.NONE || original.situation == Situation.EMPTY)

        if (!isWorked || wasWorked) return Result.Allowed

        val workedCount = dayHouses.count {
            it.data == currentDate &&
            (it.situation == Situation.NONE || it.situation == Situation.EMPTY)
        }

        val limitExceeded = workedCount > maxOpenHouses && maxOpenHouses > 0 && !isManualUnlock && !isAdmin

        return if (limitExceeded) Result.LimitExceeded else Result.Allowed
    }

    sealed interface Result {
        data object Allowed : Result
        data object LimitExceeded : Result
    }
}
