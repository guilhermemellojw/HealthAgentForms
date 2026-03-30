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

    private suspend fun ensureDayNotLocked(date: String, agentName: String, agentUid: String? = "") {
        val activity = dayActivityDao.getDayActivity(date, agentName.uppercase(), agentUid ?: "")
        if (activity?.isClosed == true && !activity.isManualUnlock) {
            throw IllegalStateException("Este dia ($date) está bloqueado para edições (Auditoria Concluída).")
        }
    }

    override fun getAllHouses(agentName: String, agentUid: String): Flow<List<House>> {
        return houseDao.getAllHouses(agentName, agentUid)
    }

    override fun getDistinctAgentNames(): Flow<List<String>> {
        return houseDao.getDistinctAgentNames()
    }

    override fun getAllHousesOrderedByBlock(agentName: String, agentUid: String): Flow<List<House>> {
        return houseDao.getAllHousesOrderedByBlock(agentName, agentUid)
    }

    override suspend fun getHouseById(id: Long): House? {
        return houseDao.getHouseById(id)
    }

    override suspend fun getAllHousesOnce(agentName: String, agentUid: String): List<House> {
        return houseDao.getAllHouses(agentName, agentUid).first()
    }

    override suspend fun getAllHousesSnapshot(): List<House> {
        return houseDao.getAllHousesSnapshot()
    }

    override fun getAllHousesSnapshotFlow(): kotlinx.coroutines.flow.Flow<List<House>> {
        return houseDao.getAllHousesSnapshotFlow()
    }

    override suspend fun insertHouse(house: House) {
        ensureDayNotLocked(house.data, house.agentName, house.agentUid)
        houseDao.insertHouse(house.copy(isSynced = false, lastUpdated = System.currentTimeMillis()))
        syncScheduler.scheduleSync()
    }

    override suspend fun updateHouse(house: House) {
        ensureDayNotLocked(house.data, house.agentName, house.agentUid)
        database.withTransaction {
            val existing = houseDao.getHouseById(house.id.toLong())
            if (existing != null) {
                val oldKey = existing.generateNaturalKey()
                val newKey = house.generateNaturalKey()
                if (oldKey != newKey) {
                    tombstoneDao.insertTombstone(Tombstone(type = TombstoneType.HOUSE, naturalKey = oldKey, agentName = existing.agentName, agentUid = existing.agentUid))
                }
            }
            houseDao.updateHouse(house.copy(isSynced = false, lastUpdated = System.currentTimeMillis()))
        }
        syncScheduler.scheduleSync()
    }

    override suspend fun updateHouses(houses: List<House>) {
        if (houses.isEmpty()) return
        
        // Ensure all affected days are not locked
        val distinctContexts = houses.map { Triple(it.data, it.agentName, it.agentUid) }.distinct()
        distinctContexts.forEach { (date, agent, uid) ->
            ensureDayNotLocked(date, agent, uid)
        }
        
        database.withTransaction {
            houses.forEach { house ->
                val existing = houseDao.getHouseById(house.id.toLong())
                if (existing != null) {
                    val oldKey = existing.generateNaturalKey()
                    val newKey = house.generateNaturalKey()
                    if (oldKey != newKey) {
                        tombstoneDao.insertTombstone(Tombstone(type = TombstoneType.HOUSE, naturalKey = oldKey, agentName = existing.agentName, agentUid = existing.agentUid))
                    }
                }
            }
            houseDao.upsertHouses(houses.map { it.copy(isSynced = false, lastUpdated = System.currentTimeMillis()) })
        }
        syncScheduler.scheduleSync()
    }

    override suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String, agentUid: String?) {
        val finalUid = agentUid ?: ""
        ensureDayNotLocked(oldDate, agentName, finalUid)
        ensureDayNotLocked(newDate, agentName, finalUid)
        
        database.withTransaction {
            val houses = houseDao.getHousesByDateAndAgent(oldDate, agentName.uppercase(), finalUid)
            val tombstones = houses.map { Tombstone(type = TombstoneType.HOUSE, naturalKey = it.generateNaturalKey(), agentName = it.agentName, agentUid = it.agentUid) }
            tombstoneDao.insertTombstones(tombstones)
            
            houseDao.updateHousesDate(oldDate, newDate, agentName, finalUid)
        }
        syncScheduler.scheduleSync()
    }

    override suspend fun deleteHouse(house: House) {
        ensureDayNotLocked(house.data, house.agentName, house.agentUid)
        database.withTransaction {
            houseDao.deleteHouse(house)
            tombstoneDao.insertTombstone(Tombstone(type = TombstoneType.HOUSE, naturalKey = house.generateNaturalKey(), agentName = house.agentName, agentUid = house.agentUid))
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
    
    override suspend fun deleteDayActivity(date: String, agentName: String, agentUid: String?) {
        dayActivityDao.deleteDayActivity(date, agentName.uppercase(), agentUid ?: "")
    }
    
    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        return database.withTransaction { block() }
    }

    override suspend fun getDayActivity(date: String, agentName: String, agentUid: String?): DayActivity? {
        return dayActivityDao.getDayActivity(date, agentName, agentUid ?: "")
    }

    override suspend fun getAllDayActivitiesOnce(agentName: String, agentUid: String): List<DayActivity> {
        return dayActivityDao.getAllDayActivities(agentName, agentUid)
    }

    override suspend fun getAllDayActivitiesSnapshot(): List<DayActivity> {
        return dayActivityDao.getAllDayActivitiesSnapshot()
    }

    override suspend fun replaceAllDayActivities(activities: List<DayActivity>) {
        dayActivityDao.replaceDayActivities(activities)
        syncScheduler.scheduleSync()
    }

    override suspend fun restoreAgentData(agentName: String, houses: List<House>, activities: List<DayActivity>, agentUid: String?) {
        val finalUid = agentUid ?: ""
        database.withTransaction {
            // Deduplicate Houses locally to prevent auto-generated ID duplicates.
            // We group by the natural key and store a mutable list of existing IDs 
            // to ensure a 1-to-1 mapping during restoration.
            val localHouses = houseDao.getAllHouses(agentName, finalUid).first()
            val localHouseGroups = localHouses.groupBy { it.generateNaturalKey() }
                .mapValues { (_, houses) -> houses.toMutableList() }
            
            val normalizedActivities = activities.map { 
                it.copy(
                    agentName = agentName.uppercase(),
                    agentUid = finalUid,
                    date = it.date.replace("/", "-")
                ) 
            }
            
            // Restoration is a high-level repair operation. We bypass ensureDayNotLocked
            // because if an admin or user is restoring a full backup, they intend to 
            // overwrite the current local state regardless of its lock status.
            normalizedActivities.forEach { 
                // No ensureDayNotLocked call here
            }

            val housesToUpsert = mutableListOf<House>()

            houses.forEach { restoredHouse ->
                val key = restoredHouse.generateNaturalKey()
                // 1-to-1 Mapping: Consume an existing ID if available for this specific key.
                // This prevents multiple houses from the backup overwriting the same local record.
                val existingId = localHouseGroups[key]?.removeFirstOrNull()?.id ?: 0
                
                housesToUpsert.add(restoredHouse.copy(
                    id = existingId,
                    agentName = agentName.uppercase(),
                    agentUid = finalUid,
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

    override suspend fun deleteProduction(date: String, agentName: String, agentUid: String?) {
        val finalUid = agentUid ?: ""
        ensureDayNotLocked(date, agentName, finalUid)
        val upperName = agentName.uppercase()
        database.withTransaction {
            // 1. Get all houses for this date to record tombstones
            val housesToDelete = houseDao.getHousesByDateAndAgent(date, upperName, finalUid)
            val houseTombstones = housesToDelete.map { Tombstone(type = TombstoneType.HOUSE, naturalKey = it.generateNaturalKey(), agentName = it.agentName, agentUid = it.agentUid) }
            
            // 2. Record activity tombstone
            val activityTombstone = Tombstone(type = TombstoneType.ACTIVITY, naturalKey = "$date|$upperName", agentName = upperName, agentUid = finalUid)
            
            // 3. Perform Deletions
            dayActivityDao.deleteDayActivity(date, upperName, finalUid)
            houseDao.deleteHousesByDateAndAgent(date, upperName, finalUid)
            
            // 4. Record Tombstones
            tombstoneDao.insertTombstones(houseTombstones)
            tombstoneDao.insertTombstone(activityTombstone)
        }
        syncScheduler.scheduleSync()
    }

    override suspend fun deleteByAgentAndDates(agentName: String, dates: List<String>, agentUid: String?) {
        val upperName = agentName.uppercase()
        val finalUid = agentUid ?: ""
        // Lock Check: Verify all dates
        dates.forEach { ensureDayNotLocked(it, upperName, finalUid) }

        database.withTransaction {
            // 1. Get all houses and activities for these dates to record tombstones
            val housesToDelete = houseDao.getHousesByAgentAndDates(upperName, finalUid, dates)
            val houseTombstones = housesToDelete.map { Tombstone(type = TombstoneType.HOUSE, naturalKey = it.generateNaturalKey(), agentName = it.agentName, agentUid = it.agentUid) }
            
            val activityTombstones = dates.map { Tombstone(type = TombstoneType.ACTIVITY, naturalKey = "$it|$upperName", agentName = upperName, agentUid = finalUid) }
            
            // 2. Perform Deletions
            dayActivityDao.deleteByAgentAndDates(upperName, finalUid, dates)
            houseDao.deleteByAgentAndDates(upperName, finalUid, dates)
            
            // 3. Record Tombstones
            tombstoneDao.insertTombstones(houseTombstones)
            tombstoneDao.insertTombstones(activityTombstones)
        }
        syncScheduler.scheduleSync()
    }

    override suspend fun countOpenDays(agentName: String, agentUid: String?): Int {
        return dayActivityDao.countOpenDays(agentName, agentUid ?: "")
    }

    override suspend fun closeAllDays(agentName: String, agentUid: String?) {
        val upperName = agentName.uppercase()
        val finalUid = agentUid ?: ""
        database.withTransaction {
            // 1. Close all existing activity records for this agent
            dayActivityDao.closeAllActivities(upperName, finalUid)

            // 2. Ensure days with houses have a (closed) activity record
            val dates = houseDao.getDistinctDates(upperName, finalUid)
            
            dates.forEach { date ->
                val activity = dayActivityDao.getDayActivity(date, upperName, finalUid)
                if (activity == null) {
                    dayActivityDao.insertDayActivity(DayActivity(date = date, status = "NORMAL", isClosed = true, agentName = upperName, agentUid = finalUid, isSynced = false))
                } else if (!activity.isClosed) {
                    dayActivityDao.insertDayActivity(activity.copy(isClosed = true, agentName = upperName, agentUid = finalUid, isSynced = false, lastUpdated = System.currentTimeMillis()))
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

    override suspend fun migrateLocalData(agentName: String, email: String, targetUid: String) {
        if (targetUid.isBlank()) return
        database.withTransaction {
            try {
                // 0. If current name is email prefix and we have a better name, migrate local records
                val emailPrefix = email.substringBefore("@").uppercase()
                val properName = agentName.trim().uppercase().ifBlank { emailPrefix }
                if (properName.isNotBlank() && properName != emailPrefix) {
                    houseDao.updateAgentNameForAll(emailPrefix, properName, targetUid)
                    dayActivityDao.updateAgentNameForAll(emailPrefix, properName, targetUid)
                }

                // 1. Standardize Houses (Lenient/Aggressive migration)
                val allOrphans = houseDao.getAllOrphanHouses()
                allOrphans.forEach { house ->
                    val isPossibleMatch = house.agentName.isBlank() || 
                        house.agentName.equals("AGENTE", true) ||
                        house.agentName.equals(agentName, true) ||
                        house.agentName.equals(email, true) ||
                        house.agentName.equals(emailPrefix, true)

                    if (isPossibleMatch) {
                        val hasClash = houseDao.checkClash(
                            targetUid, properName, house.data, house.blockNumber, house.blockSequence, 
                            house.streetName, house.number, house.sequence, house.complement, house.bairro, house.visitSegment
                        ) > 0
                        
                        if (hasClash) {
                            houseDao.deleteHouseById(house.id)
                        } else {
                            houseDao.updateHouseIdentity(house.id, targetUid, properName)
                        }
                    }
                }

                // 2. Smarter DayActivity Migration
                val orphanActivities = dayActivityDao.getAllOrphanActivities()
                orphanActivities.forEach { local ->
                    val isPossibleMatch = local.agentName.isBlank() || 
                        local.agentName.equals("AGENTE", true) ||
                        local.agentName.equals(agentName, true) ||
                        local.agentName.equals(email, true) ||
                        local.agentName.equals(emailPrefix, true)

                    if (isPossibleMatch) {
                        val conflict = getDayActivity(local.date, agentName, targetUid)
                        if (conflict != null) {
                            // Conflict resolution: Keep local if it has a specific status AND cloud is default/empty
                            if (local.status != "NORMAL" && (conflict.status == "NORMAL" || conflict.status.isBlank())) {
                                dayActivityDao.deleteDayActivity(conflict.date, conflict.agentName, conflict.agentUid)
                                dayActivityDao.insertDayActivity(local.copy(agentName = properName, agentUid = targetUid))
                            } else {
                                // Prefer incoming cloud data for conflict (already in DB)
                                dayActivityDao.deleteDayActivity(local.date, local.agentName, local.agentUid)
                            }
                        } else {
                            dayActivityDao.insertDayActivity(local.copy(agentName = properName, agentUid = targetUid))
                        }
                    }
                }
                
                // 3. Final Deduplication Pass
                deduplicateAgentData(properName, targetUid)
            } catch (e: Exception) {
                android.util.Log.e("HouseRepository", "Error during migration", e)
            }
        }
    }

    override suspend fun deduplicateAgentData(agentName: String, agentUid: String) {
        database.withTransaction {
            val allHouses = getAllHousesOnce(agentName, agentUid)
            if (allHouses.isEmpty()) return@withTransaction

            val groups = allHouses.groupBy { it.generateNaturalKey() }
            val toDelete = mutableListOf<House>()

            groups.forEach { (_, matches) ->
                if (matches.size > 1) {
                    // Keep the best record: prioritize synced, then most recent
                    val kept = matches.sortedWith(
                        compareByDescending<House> { it.isSynced }
                            .thenByDescending { it.lastUpdated }
                    ).first()
                    
                    matches.forEach { house ->
                        if (house.id != kept.id) {
                            toDelete.add(house)
                        }
                    }
                }
            }

            if (toDelete.isNotEmpty()) {
                toDelete.forEach { houseDao.deleteHouse(it) }
            }
        }
    }
}
