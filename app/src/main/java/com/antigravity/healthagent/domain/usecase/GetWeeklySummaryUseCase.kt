package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.ui.home.DaySummary
import javax.inject.Inject

class GetWeeklySummaryUseCase @Inject constructor() {
    operator fun invoke(
        dates: List<String>,
        allHouses: List<House>,
        activities: List<DayActivity>
    ): List<DaySummary> {
        return dates.map { date ->
            val dayHouses = allHouses.filter { it.data == date }
            val activity = activities.find { it.date == date }
            val openHousesCount = dayHouses.count { it.situation == Situation.NONE }
            DaySummary(date, openHousesCount, activity?.status ?: "")
        }
    }
}
