package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase

/**
 * Incremental tracker for the "days with errors" summary.
 *
 * Instead of re-validating every day on every save (O(history)), it keeps per-day
 * cached summaries plus the last seen house ids/per-house timestamps. A day is
 * only re-validated when one of its houses is added, removed or its lastUpdated
 * changes — the steady-state cost per save is O(changedDay) + O(n) numeric bookkeeping.
 */
class DayErrorTracker(
    private val houseValidationUseCase: HouseValidationUseCase
) {

    private var cache = mutableMapOf<String, DayErrorSummary?>()
    private var idToDate = mutableMapOf<Int, String>()
    private var idToTs = mutableMapOf<Int, Long>()

    fun compute(all: List<House>): List<DayErrorSummary> {
        val grouped = HashMap<String, MutableList<House>>()
        val currentIds = HashSet<Int>(all.size)
        val dirty = HashSet<String>()

        for (h in all) {
            grouped.getOrPut(h.data) { mutableListOf() }.add(h)
            currentIds.add(h.id)
            val prevTs = idToTs[h.id]
            if (prevTs == null || prevTs != h.lastUpdated) dirty.add(h.data)
            idToTs[h.id] = h.lastUpdated
            idToDate[h.id] = h.data
        }

        // Removed houses mark their previous day as dirty
        val removedIt = idToTs.keys.iterator()
        while (removedIt.hasNext()) {
            val id = removedIt.next()
            if (id !in currentIds) {
                idToDate[id]?.let { dirty.add(it) }
                removedIt.remove()
                idToDate.remove(id)
            }
        }

        // Prune cache entries for dates that no longer exist
        cache.keys.retainAll(grouped.keys)

        val results = HashMap<String, DayErrorSummary>(cache.size)
        for ((date, houses) in grouped) {
            val hasCached = cache.containsKey(date)
            val summary = if (date in dirty || !hasCached) {
                val computed = computeEntry(date, houses)
                cache[date] = computed
                computed
            } else {
                cache[date]
            }
            if (summary != null) results[date] = summary
        }

        return results.values.sortedByDescending { HouseQueryHelper.getTimestamp(it.date) }
    }

    private fun computeEntry(date: String, houses: List<House>): DayErrorSummary? {
        val validation = houseValidationUseCase.validateCurrentDay(date, houses, strict = true)
        if (validation.isValid) return null
        val errorCount = houses.count { !houseValidationUseCase.isHouseValid(it, strict = true) }
        return if (errorCount > 0) DayErrorSummary(date, errorCount) else null
    }
}