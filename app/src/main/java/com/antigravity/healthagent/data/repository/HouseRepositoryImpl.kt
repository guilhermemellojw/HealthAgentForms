package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.DayActivityDao
import androidx.room.withTransaction
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import com.antigravity.healthagent.domain.repository.HouseRepository

import com.antigravity.healthagent.data.sync.SyncScheduler
import com.antigravity.healthagent.data.local.dao.TombstoneDao
import com.antigravity.healthagent.data.local.model.Tombstone
import com.antigravity.healthagent.data.local.model.TombstoneType
import com.antigravity.healthagent.utils.withRetry
import com.antigravity.healthagent.utils.normalize
import com.antigravity.healthagent.utils.toDashDate
import com.antigravity.healthagent.domain.model.heal
import com.antigravity.healthagent.data.local.model.Situation

class HouseRepositoryImpl @Inject constructor(
    private val houseDao: HouseDao,
    private val dayActivityDao: DayActivityDao,
    private val tombstoneDao: TombstoneDao,
    private val database: com.antigravity.healthagent.data.local.AppDatabase,
    private val syncSchedulerProvider: javax.inject.Provider<SyncScheduler>,
    private val soundManager: com.antigravity.healthagent.utils.SoundManager
) : HouseRepository {

    private suspend fun <T> runInTransactionWithRetry(block: suspend () -> T): T {
        return database.withRetry {
            database.withTransaction { block() }
        }
    }

    override fun getAllHouses(agentUid: String): Flow<List<House>> = houseDao.getAllHouses(agentUid)

    override fun getPersonalHousesFlow(agentUid: String, agentName: String): Flow<List<House>> = houseDao.getHousesByAgentWithOrphans(agentUid, agentName)

    override fun getDistinctAgentNames(): Flow<List<String>> {
        return houseDao.getDistinctAgentNames()
    }

    override val allActivitiesFlow: Flow<List<DayActivity>> = dayActivityDao.getAllDayActivitiesFlow()

    override fun getAllHousesOrderedByBlock(agentUid: String): Flow<List<House>> = houseDao.getAllHousesOrderedByBlock(agentUid)

    override suspend fun getHouseById(id: Long): House? {
        return houseDao.getHouseById(id)
    }

    override suspend fun getAllHousesOnce(agentUid: String): List<House> = houseDao.getHousesByAgentSnapshot(agentUid)

    override suspend fun getAllHousesSnapshot(): List<House> {
        return houseDao.getAllHousesSnapshot()
    }

    override suspend fun getHousesByAgentSnapshot(agentUid: String): List<House> = houseDao.getHousesByAgentSnapshot(agentUid)
    override suspend fun getLastHouseForAgent(agentUid: String): House? {
        val house = houseDao.getLastHouseForAgent(agentUid)
        return house?.let { healOrphanHouse(it, agentUid) }
    }

    override suspend fun getLastHouseForAgentOnDate(agentUid: String, date: String): House? {
        val house = houseDao.getLastHouseForAgentOnDate(agentUid, date)
        return house?.let { healOrphanHouse(it, agentUid) }
    }
    
    private fun healOrphanHouse(house: House, correctUid: String): House {
        if (house.agentUid.isBlank() && correctUid.isNotBlank()) {
            AppLogger.i("HouseRepository", "Healing orphan house ${house.id} with UID $correctUid")
            return house.copy(agentUid = correctUid, isSynced = false)
        }
        return house
    }

    override fun getAllHousesSnapshotFlow(): Flow<List<House>> {
        return houseDao.getAllHousesSnapshotFlow()
    }

    override fun getHousesByAgentSnapshotFlow(agentUid: String): Flow<List<House>> = houseDao.getHousesByAgentSnapshotFlow(agentUid)

    override fun getParticipatoryHousesFlow(agentUid: String): Flow<List<House>> = houseDao.getParticipatoryHousesFlow(agentUid)

    override suspend fun insertHouse(house: House, force: Boolean): Long {
        val id = runInTransactionWithRetry {
            // CRITICAL: Cleanup any stale local tombstone for this same house key
            tombstoneDao.deleteByNaturalKey(house.generateNaturalKey(), house.agentUid)

            // DUPLICATE GUARD: Prevent DB-level duplicates from stale in-memory state
            // (e.g. rapid adds where Room Flow hasn't emitted yet)
            val existingHouses = houseDao.getHousesByDateAndAgent(house.data, house.agentUid)
            val existingDuplicate = existingHouses.find {
                it.id != house.id && it.generateIdentityKey() == house.generateIdentityKey()
            }
            if (existingDuplicate != null) {
                AppLogger.w("HouseRepository", "DUPLICATE_GUARD: Skipped insert of house id=${house.id}. Existing id=${existingDuplicate.id} key=${house.generateIdentityKey()}")
                return@runInTransactionWithRetry existingDuplicate.id.toLong()
            }

            // ADMIN AUTHORITY: Mark as edited by admin if forced
            val houseToInsert = house.copy(
                isSynced = false, 
                editedByAdmin = force,
                lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
            )
            
            houseDao.insertHouse(houseToInsert)
        }
        soundManager.vibrateTick()
        syncSchedulerProvider.get().scheduleSync()
        return id
    }

    override suspend fun updateHouse(house: House, force: Boolean) {
        runInTransactionWithRetry {
            // Check for potential clashes, but don't block manual correction (allow temporary duplicates to be flagged in UI)
            val clashCount = houseDao.checkNaturalKeyConflict(
                excludeId = house.id,
                date = house.data,
                agentUid = house.agentUid,
                blockNumber = house.address.blockNumber,
                blockSequence = house.address.blockSequence,
                streetName = house.address.streetName,
                number = house.address.number,
                sequence = house.address.sequence,
                complement = house.address.complement,
                bairro = house.address.bairro,
                visitSegment = house.visitSegment
            )
            if (clashCount > 0) {
                AppLogger.w("HouseRepository", "Manual update created an address conflict for house ${house.id}. UI will flag this.")
            }

            val existing = houseDao.getHouseById(house.id.toLong())
            if (existing != null) {
                val oldKey = existing.generateNaturalKey()
                val newKey = house.generateNaturalKey()
                if (oldKey != newKey) {
                    tombstoneDao.insertTombstone(Tombstone(type = TombstoneType.HOUSE, naturalKey = oldKey, agentUid = existing.agentUid))
                }
                // Also ensures NO tombstone exists for the NEW key (re-added or restored)
                tombstoneDao.deleteByNaturalKey(newKey, house.agentUid)
            }

            houseDao.updateHouse(house.copy(
                isSynced = false, 
                editedByAdmin = force,
                lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
            ))
        }
        syncSchedulerProvider.get().scheduleSync()
    }

    private fun hasHouseDataChanged(a: House, b: House): Boolean {
        return a.address != b.address ||
               a.treatment != b.treatment ||
               a.context != b.context ||
               a.geo != b.geo ||
               a.propertyType != b.propertyType ||
               a.situation != b.situation ||
               a.data != b.data ||
               a.agentName != b.agentName ||
               a.localidadeConcluida != b.localidadeConcluida ||
               a.quarteiraoConcluido != b.quarteiraoConcluido ||
               a.listOrder != b.listOrder ||
               a.visitSegment != b.visitSegment ||
               a.agentUid != b.agentUid ||
               a.observation != b.observation
    }

    override suspend fun updateHouses(houses: List<House>, force: Boolean) {
        if (houses.isEmpty()) return
        
        runInTransactionWithRetry {
            val existingHouses = houseDao.getHousesByIds(houses.map { it.id })
            val existingMap = existingHouses.associateBy { it.id }

            val changedHouses = houses.filter { house ->
                val existing = existingMap[house.id]
                existing == null || hasHouseDataChanged(house, existing)
            }

            if (changedHouses.isEmpty()) {
                AppLogger.w("PERSIST_DEBUG", "REPO_NOCHANGE: ${houses.size} houses passed, 0 changed (all matched existing DB)")
                return@runInTransactionWithRetry
            }

            changedHouses.forEach { house ->
                // Check for potential clashes, but don't block the update in batch mode (allow temporary duplicates during reordering)
                val clashCount = houseDao.checkNaturalKeyConflict(
                    excludeId = house.id,
                    date = house.data,
                    agentUid = house.agentUid,
                    blockNumber = house.address.blockNumber,
                    blockSequence = house.address.blockSequence,
                    streetName = house.address.streetName,
                    number = house.address.number,
                    sequence = house.address.sequence,
                    complement = house.address.complement,
                    bairro = house.address.bairro,
                    visitSegment = house.visitSegment
                )
                // We log it but don't throw, letting the UI validation (Red Highlight) handle the user notification.
                if (clashCount > 0) {
                    AppLogger.w("HouseRepository", "Batch update created a temporary address conflict for house ${house.id}")
                }

                val existing = existingMap[house.id]
                if (existing != null) {
                    val oldKey = existing.generateNaturalKey()
                    val newKey = house.generateNaturalKey()
                    if (oldKey != newKey) {
                        tombstoneDao.insertTombstone(Tombstone(type = TombstoneType.HOUSE, naturalKey = oldKey, agentUid = existing.agentUid))
                    }
                    // Also clear any tombstone for the new key
                    tombstoneDao.deleteByNaturalKey(newKey, house.agentUid)
                }
            }
            val preparedHouses = changedHouses.map { house ->
                house.copy(
                    isSynced = false, 
                    editedByAdmin = force,
                    lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                )
            }
            preparedHouses.forEach { h ->
                AppLogger.d("PERSIST_DEBUG", "REPO_UPSERT: id=${h.id} n='${h.address.number}' s=${h.address.sequence} c=${h.address.complement} pt=${h.propertyType.code} lastUpdated=${h.lastUpdated}")
            }
            houseDao.upsertHouses(preparedHouses)
        }
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun updateHousesDate(oldDate: String, newDate: String, agentUid: String, force: Boolean) {
        val finalUid = agentUid
        
        runInTransactionWithRetry {
            val houses = houseDao.getHousesByDateAndAgent(oldDate, finalUid)
            val tombstones = houses.map { Tombstone(type = TombstoneType.HOUSE, naturalKey = it.generateNaturalKey(), agentUid = it.agentUid) }
            tombstoneDao.insertTombstones(tombstones)
            
            houseDao.updateHousesDate(oldDate, newDate, finalUid)
        }
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun deleteHouse(house: House, force: Boolean) {
        runInTransactionWithRetry {
            houseDao.deleteHouse(house)
            tombstoneDao.insertTombstone(Tombstone(type = TombstoneType.HOUSE, naturalKey = house.generateNaturalKey(), agentUid = house.agentUid, dataDate = house.data))
        }
        soundManager.vibrateTick()
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun replaceAllHouses(houses: List<House>) {
        houseDao.replaceHouses(houses)
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun getHousesByDateAndAgent(date: String, agentUid: String): List<House> {
        return houseDao.getHousesByDateAndAgent(date, agentUid)
    }

    override fun getHousesByDateAndAgentFlow(date: String, agentUid: String): Flow<List<House>> {
        return houseDao.getHousesByDateAndAgentFlow(date, agentUid)
    }

    override fun getDayActivities(dates: List<String>, agentUid: String?): Flow<List<DayActivity>> {
        return dayActivityDao.getDayActivities(dates, agentUid ?: "")
    }

    override fun getDayActivityFlow(date: String, agentUid: String?): Flow<DayActivity?> {
        return dayActivityDao.getDayActivityFlow(date, agentUid ?: "")
    }

    override suspend fun updateDayActivity(dayActivity: DayActivity, force: Boolean) {
        runInTransactionWithRetry {
            // Tombstone cleanup for re-opened/re-added activities
            tombstoneDao.deleteByNaturalKey("${dayActivity.date}|${dayActivity.agentUid}", dayActivity.agentUid)
            
            dayActivityDao.insertDayActivity(dayActivity.copy(
                date = dayActivity.date.toDashDate(),
                isSynced = false, 
                editedByAdmin = force,
                lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
            ))
        }
        syncSchedulerProvider.get().scheduleSync()
    }
    
    override suspend fun deleteDayActivity(date: String, agentUid: String?) {
        dayActivityDao.deleteDayActivity(date, agentUid ?: "")
    }
    
    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        return runInTransactionWithRetry { block() }
    }

    override suspend fun getDayActivity(date: String, agentUid: String?): DayActivity? {
        return dayActivityDao.getDayActivity(date, agentUid ?: "")
    }

    override suspend fun getAllDayActivitiesOnce(agentUid: String): List<DayActivity> {
        return dayActivityDao.getAllDayActivities(agentUid)
    }

    override suspend fun getAllDayActivitiesSnapshot(): List<DayActivity> {
        return dayActivityDao.getAllDayActivitiesSnapshot()
    }

    override suspend fun getDayActivitiesByAgentSnapshot(agentUid: String): List<DayActivity> {
        return dayActivityDao.getDayActivitiesByAgentSnapshot(agentUid)
    }

    override suspend fun replaceAllDayActivities(activities: List<DayActivity>) {
        dayActivityDao.replaceDayActivities(activities)
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun restoreAgentData(houses: List<House>, activities: List<DayActivity>, agentUid: String?) {
        val finalUid = agentUid ?: ""
        runInTransactionWithRetry {
            // 1. Identify all dates being restored (normalized)
            val restoredDatesList = (houses.map { it.data.toDashDate() } + activities.map { it.date.toDashDate() }).distinct()

            // 2. Perform Atomic CLEANUP of local data for these dates FIRST
            if (restoredDatesList.isNotEmpty()) {
                houseDao.deleteByAgentAndDates(finalUid, restoredDatesList)
                dayActivityDao.deleteByAgentAndDates(finalUid, restoredDatesList)
            }

            // 3. Normalize and Prepare Data
            val normalizedActivities = activities.map { 
                val normalized = it.copy(
                    agentUid = finalUid,
                    date = it.date.toDashDate(),
                    isSynced = false,
                    lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                ) 
                // Clear local tombstone for restored activity
                tombstoneDao.deleteByNaturalKey("${normalized.date}|${normalized.agentUid}", normalized.agentUid)
                normalized
            }

            val housesToUpsert = houses.map { restoredHouse ->
                
                // Healing logic: Default EMPTY to NONE (Aberto) for restored data
                val finalSituation = restoredHouse.situation.heal()

                val finalHouse = restoredHouse.copy(
                    id = 0, // Reset ID to allow auto-generation and prevent collision with unrelated local records
                    agentUid = finalUid,
                    data = restoredHouse.data.toDashDate(),
                    situation = finalSituation,
                    isSynced = false,
                    lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                )
                
                // Clear local tombstone for restored house
                tombstoneDao.deleteByNaturalKey(finalHouse.generateNaturalKey(), finalHouse.agentUid)
                
                finalHouse
            }

            // 4. Upsert Data
            houseDao.upsertHouses(housesToUpsert)
            dayActivityDao.upsertDayActivities(normalizedActivities)
        }
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun deleteProduction(originalDate: String, agentUid: String?, force: Boolean) {
        val date = originalDate.toDashDate()
        val finalUid = agentUid ?: ""
        runInTransactionWithRetry {
            // 1. Get all houses for this date to record tombstones
            val housesToDelete = houseDao.getHousesByDateAndAgent(date, finalUid)
            val houseTombstones = housesToDelete.map { Tombstone(type = TombstoneType.HOUSE, naturalKey = it.generateNaturalKey(), agentUid = it.agentUid) }
            
            // 2. Record activity tombstone
            val activityTombstone = Tombstone(type = TombstoneType.ACTIVITY, naturalKey = "$date|${finalUid}", agentUid = finalUid)
            
            // 3. Perform Deletions
            dayActivityDao.deleteDayActivity(date, finalUid)
            houseDao.deleteHousesByDateAndAgent(date, finalUid)
            
            // 4. Record Tombstones
            tombstoneDao.insertTombstones(houseTombstones)
            tombstoneDao.insertTombstone(activityTombstone)
        }
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun deleteByAgentAndDates(dates: List<String>, agentUid: String?, force: Boolean) {
        val normalizedDates = dates.map { it.toDashDate() }
        val finalUid = agentUid ?: ""

        runInTransactionWithRetry {
            // 1. Get all houses and activities for these dates to record tombstones
            val housesToDelete = houseDao.getHousesByAgentAndDates(finalUid, normalizedDates)
            val houseTombstones = housesToDelete.map { Tombstone(type = TombstoneType.HOUSE, naturalKey = it.generateNaturalKey(), agentUid = it.agentUid, dataDate = it.data) }
            
            val activityTombstones = normalizedDates.map { Tombstone(type = TombstoneType.ACTIVITY, naturalKey = "$it|$finalUid", agentUid = finalUid, dataDate = it) }
            
            // 2. Perform Deletions
            dayActivityDao.deleteByAgentAndDates(finalUid, normalizedDates)
            houseDao.deleteByAgentAndDates(finalUid, normalizedDates)
            
            // 3. Record Tombstones
            tombstoneDao.insertTombstones(houseTombstones)
            tombstoneDao.insertTombstones(activityTombstones)
        }
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun countOpenDays(agentUid: String?): Int {
        return dayActivityDao.countOpenDays(agentUid ?: "")
    }

    override suspend fun closeAllDays(agentUid: String?) {
        val finalUid = agentUid ?: ""
        runInTransactionWithRetry {
            // 1. Close all existing activity records for this agent
            dayActivityDao.closeAllActivities(finalUid)

            // 2. Ensure days with houses have a (closed) activity record
            val dates = houseDao.getDistinctDates(finalUid)
            
            dates.forEach { rawDate ->
                val date = rawDate.toDashDate()
                val activity = dayActivityDao.getDayActivity(date, finalUid)
                if (activity == null) {
                    dayActivityDao.insertDayActivity(DayActivity(
                        date = date, 
                        status = "NORMAL", 
                        isClosed = true, 
                        isManualUnlock = false,
                        agentUid = finalUid, 
                        isSynced = false, 
                        lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                    ))
                } else if (!activity.isClosed || activity.isManualUnlock) {
                    dayActivityDao.insertDayActivity(activity.copy(
                        date = date, 
                        isClosed = true, 
                        isManualUnlock = false,
                        agentUid = finalUid, 
                        isSynced = false, 
                        lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                    ))
                }
            }
        }
        syncSchedulerProvider.get().scheduleSync()
    }


    override suspend fun clearAllData() {
        runInTransactionWithRetry {
            houseDao.deleteAll()
            dayActivityDao.deleteAll()
            tombstoneDao.deleteAll()
            database.customStreetDao().deleteAll()
            database.agentCacheDao().clearAgents()
            database.agentCacheDao().clearSummaries()
            try {
                database.openHelper.writableDatabase.execSQL("DELETE FROM sqlite_sequence")
            } catch (e: Exception) {
                AppLogger.w("HouseRepository", "Failed to reset sqlite_sequence: ${e.message}")
            }
        }
    }

    override suspend fun clearAgentData(agentUid: String) {
        runInTransactionWithRetry {
            houseDao.deleteByAgent(agentUid)
            dayActivityDao.deleteByAgent(agentUid)
            tombstoneDao.deleteByAgent(agentUid)
        }
    }

    override suspend fun migrateLocalData(agentName: String, email: String, targetUid: String, isCurrentAgent: Boolean) {
        if (targetUid.isBlank()) return
        runInTransactionWithRetry {
            try {
                val emailPrefix = email.substringBefore("@").uppercase()
                val properName = agentName.trim().uppercase()

                // 1. Standardize Houses (Smarter Merge Migration)
                val orphans = houseDao.getAllOrphanHouses()
                val misattributed = houseDao.getHousesToReclaim(email, emailPrefix, targetUid, properName)
                val allOrphans = (orphans + misattributed).distinctBy { it.id }
                
                val targetHouses = houseDao.getHousesByAgentSnapshot(targetUid)
                
                allOrphans.forEach { house ->
                        // Match by Natural Identity (Includes Segment)
                        val naturalKey = house.generateNaturalKey().replaceFirst("_", "${targetUid}_")
                        val conflict = targetHouses.find { it.generateNaturalKey() == naturalKey }
                        
                        if (conflict != null) {
                            val isHouseClosed = dayActivityDao.getDayActivity(house.data.toDashDate(), targetUid)?.let { it.isClosed && !it.isManualUnlock } ?: false
                            val isConflictClosed = dayActivityDao.getDayActivity(conflict.data.toDashDate(), targetUid)?.let { it.isClosed && !it.isManualUnlock } ?: false
                            
                            if (isHouseClosed || isConflictClosed) {
                                // CLOSED-DAY GUARD: skip deletion of duplicate conflict or house
                                if (!isHouseClosed) {
                                    houseDao.updateHouseIdentity(house.id, targetUid, agentName)
                                }
                            } else {
                                // MERGE LOGIC: Prefer the house that has actual fieldwork data (treatment)
                                val localHasWork = house.treatment.a1 > 0 || house.treatment.a2 > 0 || house.treatment.comFoco || house.observation.isNotBlank()
                                val conflictHasWork = conflict.treatment.a1 > 0 || conflict.treatment.a2 > 0 || conflict.treatment.comFoco || conflict.observation.isNotBlank()
                                
                                if (localHasWork && !conflictHasWork) {
                                    AppLogger.i("HouseRepository", "Migration: Overwriting empty cloud skeleton with local production for ${house.id}")
                                    houseDao.deleteHouse(conflict)
                                    houseDao.updateHouseIdentity(house.id, targetUid, agentName)
                                } else {
                                    // Conflict already has work or local is also empty
                                    houseDao.deleteHouseById(house.id)
                                }
                            }
                        } else {
                            houseDao.updateHouseIdentity(house.id, targetUid, agentName)
                        }
                }

                // 2. Smarter DayActivity Migration
                val orphanActs = dayActivityDao.getAllOrphanActivities()
                val misattributedActs = dayActivityDao.getActivitiesToReclaim(email, emailPrefix, targetUid, properName)
                val orphanActivities = (orphanActs + misattributedActs).distinctBy { it.date }

                orphanActivities.forEach { local ->
                        val conflict = getDayActivity(local.date, targetUid)
                        if (conflict != null) {
                            // Conflict resolution: Keep local if it has a specific status AND cloud is default/empty
                            if (local.status != "NORMAL" && (conflict.status == "NORMAL" || conflict.status.isBlank())) {
                                dayActivityDao.deleteDayActivity(conflict.date, conflict.agentUid)
                                dayActivityDao.insertDayActivity(local.copy(agentUid = targetUid, agentName = agentName))
                            } else {
                                // Prefer incoming cloud data for conflict (already in DB)
                                dayActivityDao.deleteDayActivity(local.date, local.agentUid)
                            }
                        } else {
                            dayActivityDao.insertDayActivity(local.copy(agentUid = targetUid, agentName = agentName))
                        }
                }
                
                // 3. Final Deduplication Pass
                deduplicateAgentData(targetUid)
            } catch (e: Exception) {
                AppLogger.e("HouseRepository", "Error during migration", e)
            }
        }
    }

    override suspend fun deduplicateAgentData(agentUid: String) {
        runInTransactionWithRetry {
            val allHouses = getAllHousesOnce(agentUid)
            if (allHouses.isEmpty()) return@runInTransactionWithRetry

            // RESTORED: Group by NaturalKey to respect different visitSegments.
            // Since we now have unified normalization, this will safely unify 
            // exact duplicates while preserving legitimate multi-turn visits.
            val groups = allHouses.groupBy { it.generateNaturalKey() }
            val toDelete = mutableListOf<House>()

            groups.forEach { (_, matches) ->
                if (matches.size > 1) {
                    // Keep the best record: prioritize synced, then most recent, then highest listOrder
                    val kept = matches.sortedWith(
                        compareByDescending<House> { it.isSynced }
                            .thenByDescending { it.lastUpdated }
                            .thenByDescending { it.listOrder }
                    ).first()
                    
                    matches.forEach { house ->
                        if (house.id != kept.id) {
                            toDelete.add(house)
                        }
                    }
                }
            }

            if (toDelete.isNotEmpty()) {
                // CLOSED DAY GUARD: Preserve duplicates in closed days (let admin handle manually)
                val closedDatesForDedup = toDelete.map { it.data.toDashDate() }.distinct().filter { date ->
                    val activity = dayActivityDao.getDayActivity(date, agentUid)
                    activity?.isClosed == true && !activity.isManualUnlock
                }.toSet()
                val safeToDeleteDedup = toDelete.filter { it.data.toDashDate() !in closedDatesForDedup }
                if (closedDatesForDedup.isNotEmpty()) {
                    AppLogger.w("HouseRepository", "Dedup: Preserved ${toDelete.size - safeToDeleteDedup.size} duplicates in ${closedDatesForDedup.size} closed days.")
                }
                safeToDeleteDedup.forEach { houseDao.deleteHouse(it) }
                if (safeToDeleteDedup.isNotEmpty()) {
                    syncSchedulerProvider.get().scheduleSync()
                }
            }
        }
    }

    override suspend fun cleanMisattributedData(inspectedUid: String, adminUid: String) {
        runInTransactionWithRetry {
            // Fetch full local state to allow cross-UID comparison
            val allHouses = houseDao.getAllHousesSnapshot()
            val inspectedHouses = allHouses.filter { it.agentUid == inspectedUid }
            val adminHouses = allHouses.filter { it.agentUid == adminUid }
            
            if (inspectedHouses.isEmpty() || adminHouses.isEmpty()) return@runInTransactionWithRetry
            
            val closedDates = dayActivityDao.getDayActivitiesByAgentSnapshot(inspectedUid)
                .filter { it.isClosed && !it.isManualUnlock }
                .map { it.date.toDashDate() }
                .toSet()

            // BUG FIX: naturalKey includes agentUid, so they will NEVER match between different users.
            // We must use identityKey (Logical Identity) to detect cross-UID leaks.
            val adminKeys = adminHouses.map { it.generateIdentityKey() }.toSet()
            val toDelete = inspectedHouses.filter { 
                it.generateIdentityKey() in adminKeys && 
                it.data.toDashDate() !in closedDates 
            }
            
            if (toDelete.isNotEmpty()) {
                AppLogger.i("HouseRepository", "Surgical clean: removing ${toDelete.size} identity duplicates from $inspectedUid")
                toDelete.forEach { houseDao.deleteHouse(it) }
            }

            // Also clean Activities (Surgical: only if the activity is truly duplicate in identity)
            // Note: Since Identity is date|agentName, and adminUid != inspectedUid, 
            // they can only clash if agentName is the same, which we already handle 
            // in the houses section. Activities per-se are isolated by UID.
            // WE REMOVE the date-based collision check as it was purging legitimate 
            // concurrent production of different agents on the same day.

            if (toDelete.isNotEmpty()) {
                syncSchedulerProvider.get().scheduleSync()
            }
        }
    }

    override suspend fun normalizeLocalDates() {
        runInTransactionWithRetry {
            val allHouses = houseDao.getAllHousesSnapshot()
            val housesToUpdate = allHouses.filter { it.data.contains("/") }
            if (housesToUpdate.isNotEmpty()) {
                val updated = housesToUpdate.map { 
                    it.copy(
                        data = it.data.toDashDate(),
                        isSynced = false,
                        lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                    )
                }
                houseDao.upsertHouses(updated)
            }

            val allActivities = dayActivityDao.getAllDayActivitiesSnapshot()
            val activitiesToMigrate = allActivities.filter { it.date.contains("/") }
            if (activitiesToMigrate.isNotEmpty()) {
                for (activity in activitiesToMigrate) {
                    val newActivity = activity.copy(
                        date = activity.date.toDashDate(),
                        isSynced = false,
                        lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                    )
                    dayActivityDao.deleteDayActivity(activity.date, activity.agentUid)
                    dayActivityDao.insertDayActivity(newActivity)
                }
            }
        }
    }

    override suspend fun fixEmailNamesForUid(uid: String, properName: String) {
        runInTransactionWithRetry {
            houseDao.fixEmailNamesForUid(uid, properName)
            dayActivityDao.fixEmailNamesForUid(uid, properName)
        }
    }

    override suspend fun getUnsyncedHouses(agentUid: String): List<House> = houseDao.getUnsyncedHouses(agentUid)
    override suspend fun getUnsyncedActivities(agentUid: String): List<DayActivity> = dayActivityDao.getUnsyncedActivities(agentUid)
    override suspend fun countHouses(): Int = houseDao.count()
    override suspend fun getActiveBairros(agentUid: String): List<String> = houseDao.getActiveBairros(agentUid)
    override suspend fun getActiveBlockNumbers(): List<String> = houseDao.getActiveBlockNumbers()
    override suspend fun getHousesByBlocks(blocks: List<String>): List<House> = houseDao.getHousesByBlocks(blocks)
    override suspend fun getHousesByMonth(agentUid: String, monthYear: String): List<House> = houseDao.getHousesByMonth(agentUid, monthYear)
    override suspend fun getDayActivitiesByMonth(agentUid: String, monthYear: String): List<DayActivity> = dayActivityDao.getDayActivitiesByMonth(agentUid, monthYear)
    override suspend fun getEmptyHouses(agentUid: String): List<House> = houseDao.getEmptyHouses(agentUid)

    override suspend fun upsertHousesRaw(houses: List<House>) = houseDao.upsertHouses(houses)
    override suspend fun upsertDayActivitiesRaw(activities: List<DayActivity>) = dayActivityDao.upsertDayActivities(activities)

    override suspend fun updateBatchSegments(updates: List<Pair<Int, Int>>) {
        runInTransactionWithRetry {
            houseDao.updateBatchSegments(updates)
        }
    }

    override suspend fun updateBatchOrders(updates: List<Triple<Int, Long, Int>>) {
        runInTransactionWithRetry {
            houseDao.updateBatchOrders(updates)
        }
    }
    override suspend fun deleteHousesByDateAndAgent(date: String, agentUid: String) = houseDao.deleteHousesByDateAndAgent(date, agentUid)
    override suspend fun deleteHouseById(id: Int) = houseDao.deleteHouseById(id)
    override suspend fun markHouseAsSynced(id: Int, lastUpdated: Long, agentUid: String, agentName: String) = houseDao.markAsSyncedWithTimestamp(id, lastUpdated, agentUid, agentName)
    override suspend fun markActivityAsSynced(date: String, agentName: String, agentUid: String, lastUpdated: Long) = dayActivityDao.markAsSyncedWithTimestamp(date, agentName, agentUid, lastUpdated)
    override suspend fun cleanupZeroValues() = houseDao.cleanupZeroValues()

    override suspend fun getAllTombstones(agentUid: String): List<Tombstone> = tombstoneDao.getAllTombstones(agentUid)
    override suspend fun insertTombstones(tombstones: List<Tombstone>) = tombstoneDao.insertTombstones(tombstones)
    override suspend fun insertTombstone(tombstone: Tombstone) = tombstoneDao.insertTombstone(tombstone)
    override suspend fun deleteTombstoneByNaturalKey(naturalKey: String, agentUid: String) = tombstoneDao.deleteByNaturalKey(naturalKey, agentUid)
    override suspend fun deleteTombstones(ids: List<Int>) = tombstoneDao.deleteTombstones(ids)
    override suspend fun deleteTombstonesByAgent(agentUid: String) = tombstoneDao.deleteByAgent(agentUid)
    override suspend fun pruneOldTombstones(threshold: Long) = tombstoneDao.deleteOldTombstones(threshold)
}
