package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.DayActivityDao
import androidx.room.withTransaction
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class HouseRepositoryImpl @Inject constructor(
    private val houseDao: HouseDao,
    private val dayActivityDao: DayActivityDao,
    private val database: com.antigravity.healthagent.data.local.AppDatabase
) : HouseRepository {
    override fun getAllHouses(): Flow<List<House>> {
        return houseDao.getAllHouses()
    }

    override fun getDistinctAgentNames(): Flow<List<String>> {
        return houseDao.getDistinctAgentNames()
    }

    override fun getAllHousesOrderedByBlock(): Flow<List<House>> {
        return houseDao.getAllHousesOrderedByBlock()
    }

    override suspend fun getHouseById(id: Long): House? {
        return houseDao.getHouseById(id)
    }

    override suspend fun getAllHousesOnce(): List<House> {
        // Actually, dao.getAllHouses() returns Flow<List<House>>. We can just use first().
        // Room's Flow<List<T>> typically emits an empty list if there are no items,
        // so first() should always return a List<House> (possibly empty).
        return houseDao.getAllHouses().first()
    }

    override suspend fun insertHouse(house: House) {
        houseDao.insertHouse(house.copy(isSynced = false, lastUpdated = System.currentTimeMillis()))
    }

    override suspend fun updateHouse(house: House) {
        houseDao.updateHouse(house.copy(isSynced = false, lastUpdated = System.currentTimeMillis()))
    }

    override suspend fun updateHouses(houses: List<House>) {
        houseDao.updateAll(houses.map { it.copy(isSynced = false, lastUpdated = System.currentTimeMillis()) })
    }

    override suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String) {
        houseDao.updateHousesDate(oldDate, newDate, agentName)
    }

    override suspend fun deleteHouse(house: House) {
        houseDao.deleteHouse(house)
    }

    override suspend fun replaceAllHouses(houses: List<House>) {
        houseDao.replaceHouses(houses)
    }

    override fun getDayActivities(dates: List<String>, agentName: String, agentUid: String?): Flow<List<DayActivity>> {
        return dayActivityDao.getDayActivities(dates, agentName, agentUid ?: "")
    }

    override fun getDayActivityFlow(date: String, agentName: String, agentUid: String?): Flow<DayActivity?> {
        return dayActivityDao.getDayActivityFlow(date, agentName, agentUid ?: "")
    }

    override suspend fun updateDayActivity(dayActivity: DayActivity) {
        dayActivityDao.insertDayActivity(dayActivity.copy(
            agentName = dayActivity.agentName.uppercase(),
            isSynced = false, 
            lastUpdated = System.currentTimeMillis()
        ))
    }
    
    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        return database.withTransaction { block() }
    }

    override suspend fun getDayActivity(date: String, agentName: String, agentUid: String?): DayActivity? {
        return dayActivityDao.getDayActivity(date, agentName, agentUid ?: "")
    }

    override suspend fun getAllDayActivitiesOnce(): List<DayActivity> {
        return dayActivityDao.getAllDayActivities()
    }

    override suspend fun replaceAllDayActivities(activities: List<DayActivity>) {
        dayActivityDao.replaceDayActivities(activities)
    }

    override suspend fun restoreAgentData(agentName: String, houses: List<House>, activities: List<DayActivity>) {
        database.withTransaction {
            // Deduplicate Houses locally to prevent auto-generated ID duplicates.
            // We group by the natural key and store a mutable list of existing IDs 
            // to ensure a 1-to-1 mapping during restoration.
            val localHouses = houseDao.getAllHouses().first()
            val localHouseGroups = localHouses.groupBy { it.generateNaturalKey() }
                .mapValues { (_, houses) -> houses.toMutableList() }
            
            val normalizedActivities = activities.map { 
                it.copy(
                    agentName = agentName.uppercase(),
                    date = it.date.replace("/", "-")
                ) 
            }
            val housesToUpsert = mutableListOf<House>()
            val normalizedHouses = com.antigravity.healthagent.utils.HouseNormalizationUtils.normalizeHouses(houses)

            normalizedHouses.forEach { restoredHouse ->
                val key = restoredHouse.generateNaturalKey()
                // 1-to-1 Mapping: Consume an existing ID if available for this specific key.
                // This prevents multiple houses from the backup overwriting the same local record.
                val existingId = localHouseGroups[key]?.removeFirstOrNull()?.id ?: 0
                
                housesToUpsert.add(restoredHouse.copy(
                    id = existingId,
                    agentName = agentName.uppercase(),
                    data = restoredHouse.data.replace("/", "-")
                ))
            }

            houseDao.upsertHouses(housesToUpsert)
            dayActivityDao.upsertDayActivities(normalizedActivities)
            
            // Cleanup ghosts
            for ((key, matches) in localHouseGroups) {
                if (matches.isNotEmpty()) {
                    val keptId = housesToUpsert.find { it.generateNaturalKey() == key }?.id
                    matches.forEach { house -> if (house.id != keptId && house.id != 0) houseDao.deleteHouse(house) }
                }
            }
        }
    }

    override suspend fun deleteProduction(date: String, agentName: String) {
        database.withTransaction {
            dayActivityDao.deleteDayActivity(date, agentName)
            houseDao.deleteHousesByDateAndAgent(date, agentName)
        }
    }

    override suspend fun deleteByAgentAndDates(agentName: String, dates: List<String>) {
        database.withTransaction {
            dayActivityDao.deleteByAgentAndDates(agentName, dates)
            houseDao.deleteByAgentAndDates(agentName, dates)
        }
    }

    override suspend fun countOpenDays(agentName: String): Int {
        return dayActivityDao.countOpenDays(agentName)
    }

    override suspend fun closeAllDays(agentName: String) {
        val upperName = agentName.uppercase()
        database.withTransaction {
            // 1. Close all existing activity records for this agent
            dayActivityDao.closeAllActivities(upperName)

            // 2. Ensure days with houses have a (closed) activity record
            val dates = houseDao.getDistinctDates(upperName)
            
            dates.forEach { date ->
                val activity = dayActivityDao.getDayActivity(date, upperName)
                if (activity == null) {
                    dayActivityDao.insertDayActivity(DayActivity(date, "NORMAL", true, upperName, isSynced = false, lastUpdated = System.currentTimeMillis()))
                } else if (!activity.isClosed) {
                    dayActivityDao.insertDayActivity(activity.copy(isClosed = true, agentName = upperName, isSynced = false, lastUpdated = System.currentTimeMillis()))
                }
            }
        }
    }

    override suspend fun clearAllData() {
        database.withTransaction {
            houseDao.deleteAll()
            dayActivityDao.deleteAll()
        }
    }
}
