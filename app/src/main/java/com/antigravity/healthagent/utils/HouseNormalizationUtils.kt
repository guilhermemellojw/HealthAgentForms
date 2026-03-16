package com.antigravity.healthagent.utils

import com.antigravity.healthagent.data.local.model.House

object HouseNormalizationUtils {

    /**
     * Normalizes a list of houses by calculating visit segments.
     * A visit segment distinguishes return trips to the same street on the same day.
     * Logic: Every time the street name changes while traversing the list ordered by [listOrder],
     * we increment a counter. 
     */
    fun normalizeHouses(houses: List<House>): List<House> {
        if (houses.isEmpty()) return emptyList()

        // 1. Group by agent and date
        return houses.groupBy { "${it.agentName.uppercase()}_${it.data.replace("/", "-")}" }
            .flatMap { (_, dayHouses) ->
                // 2. Sort by listOrder to respect the sequence of entry
                val sortedHouses = dayHouses.sortedBy { it.listOrder }
                
                var currentSegment = 0
                var lastStreet = ""
                
                sortedHouses.map { house ->
                    val normalizedStreet = house.streetName.trim().uppercase()
                    
                    if (lastStreet.isNotEmpty() && normalizedStreet != lastStreet) {
                        currentSegment++
                    }
                    
                    lastStreet = normalizedStreet
                    
                    house.copy(
                        visitSegment = currentSegment,
                        agentName = house.agentName.uppercase(),
                        data = house.data.replace("/", "-")
                    )
                }
            }
    }
}
