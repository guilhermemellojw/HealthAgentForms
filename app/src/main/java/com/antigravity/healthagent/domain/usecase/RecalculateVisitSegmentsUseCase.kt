package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.utils.formatStreetName
import javax.inject.Inject

class RecalculateVisitSegmentsUseCase @Inject constructor() {

    fun recalculateVisitSegments(houses: List<House>): List<House> {
        if (houses.isEmpty()) return emptyList()
        
        var currentSegment = 0
        var lastStreet = ""
        
        return houses.sortedBy { it.listOrder }.map { house ->
            val street = house.address.streetName.formatStreetName()
            if (lastStreet.isNotEmpty() && street != lastStreet) {
                currentSegment++
            }
            lastStreet = street
            house.copy(visitSegment = currentSegment)
        }
    }
}
