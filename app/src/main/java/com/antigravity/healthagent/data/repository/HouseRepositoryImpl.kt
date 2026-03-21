package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.DayActivityDao
import androidx.room.withTransaction
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

import com.antigravity.healthagent.data.sync.SyncScheduler
import com.antigravity.healthagent.data.local.dao.TombstoneDao
import com.antigravity.healthagent.data.local.model.Tombstone
import com.antigravity.healthagent.data.local.model.TombstoneType

class HouseRepositoryImpl @Inject constructor(
    private val houseDao: HouseDao,
    private val dayActivityDao: DayActivityDao,
    private val tombstoneDao: TombstoneDao,
    private val database: com.antigravity.healthagent.data.local.AppDatabase,
    private val syncScheduler: SyncScheduler
) : HouseRepository {

    private suspend fun ensureDayNotLocked(date: String, agentName: String, agentUid: String? = null) {
        val activity = dayActivityDao.getDayActivity(date, agentName.uppercase(), agentUid)
        if (activity?.isClosed == true) {
            throw IllegalStateException("Este dia ($date) está bloqueado para edições (Auditoria Concluída).")
        }
    }

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
        ensureDayNotLocked(house.data, house.agentName)
        houseDao.insertHouse(house.copy(isSynced = false, lastUpdated = System.currentTimeMillis()))
        syncScheduler.scheduleSync()
    }

    override suspend fun updateHouse(house: House) {
        ensureDayNotLocked(house.data, house.agentName)
        houseDao.updateHouse(house.copy(isSynced = false, lastUpdated = System.currentTimeMillis()))
        syncScheduler.scheduleSync()
    }

    override suspend fun updateHouses(houses: List<House>) {
        if (houses.isEmpty()) return
        
        // Ensure all affected days are not locked
        val distinctContexts = houses.map { it.data to it.agentName }.distinct()
        distinctContexts.forEach { (date, agent) ->
            ensureDayNotLocked(date, agent)
        }
        
        houseDao.updateAll(houses.map { it.copy(isSynced = false, lastUpdated = System.currentTimeMillis()) })
        syncScheduler.scheduleSync()
    }

    override suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String) {
        ensureDayNotLocked(oldDate, agentName)
        ensureDayNotLocked(newDate, agentName)
        houseDao.updateHousesDate(oldDate, newDate, agentName)
        syncScheduler.scheduleSync()
    }

    override suspend fun deleteHouse(house: House) {
        ensureDayNotLocked(house.data, house.agentName)
        database.withTransaction {
            houseDao.deleteHouse(house)
            tombstoneDao.insertTombstone(Tombstone(type = TombstoneType.HOUSE, naturalKey = house.generateNaturalKey()))
        }
        syncScheduler.scheduleSync()
    }

    override suspend fun replaceAllHouses(houses: List<House>) {
        houseDao.replaceHouses(houses)
        syncScheduler.scheduleSync()
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
        syncScheduler.scheduleSync()
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
        syncScheduler.scheduleSync()
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
            
            // Lock Check: Ensure none of the restored activities are overwriting a locked local day
            normalizedActivities.forEach { 
                ensureDayNotLocked(it.date, agentName) 
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
        syncScheduler.scheduleSync()
    }

    override suspend fun deleteProduction(date: String, agentName: String) {
        ensureDayNotLocked(date, agentName)
        val upperName = agentName.uppercase()
        database.withTransaction {
            // 1. Get all houses for this date to record tombstones
            val housesToDelete = houseDao.getHousesByDateAndAgent(date, upperName)
            val houseTombstones = housesToDelete.map { Tombstone(type = TombstoneType.HOUSE, naturalKey = it.generateNaturalKey()) }
            
            // 2. Record activity tombstone
            val activityTombstone = Tombstone(type = TombstoneType.ACTIVITY, naturalKey = "$date|$upperName")
            
            // 3. Perform Deletions
            dayActivityDao.deleteDayActivity(date, upperName)
            houseDao.deleteHousesByDateAndAgent(date, upperName)
            
            // 4. Record Tombstones
            tombstoneDao.insertTombstones(houseTombstones)
            tombstoneDao.insertTombstone(activityTombstone)
        }
        syncScheduler.scheduleSync()
    }

    override suspend fun deleteByAgentAndDates(agentName: String, dates: List<String>) {
        val upperName = agentName.uppercase()
        // Lock Check: Verify all dates
        dates.forEach { ensureDayNotLocked(it, upperName) }

        database.withTransaction {
            // 1. Get all houses and activities for these dates to record tombstones
            val housesToDelete = houseDao.getHousesByAgentAndDates(upperName, dates)
            val houseTombstones = housesToDelete.map { Tombstone(type = TombstoneType.HOUSE, naturalKey = it.generateNaturalKey()) }
            
            val activityTombstones = dates.map { Tombstone(type = TombstoneType.ACTIVITY, naturalKey = "$it|$upperName") }
            
            // 2. Perform Deletions
            dayActivityDao.deleteByAgentAndDates(upperName, dates)
            houseDao.deleteByAgentAndDates(upperName, dates)
            
            // 3. Record Tombstones
            tombstoneDao.insertTombstones(houseTombstones)
            tombstoneDao.insertTombstones(activityTombstones)
        }
        syncScheduler.scheduleSync()
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
        syncScheduler.scheduleSync()
    }

    override suspend fun clearAllData() {
        database.withTransaction {
            houseDao.deleteAll()
            dayActivityDao.deleteAll()
        }
    }
}
