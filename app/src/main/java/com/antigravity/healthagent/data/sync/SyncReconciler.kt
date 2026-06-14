package com.antigravity.healthagent.data.sync

import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Tombstone
import com.antigravity.healthagent.data.local.model.TombstoneType
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.utils.AppConstants
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncReconciler @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val houseRepository: HouseRepository
) {
    private class HouseWithKeys(
        val house: House,
        val naturalKey: String = house.generateNaturalKey(),
        val identityKey: String = house.generateIdentityKey()
    )

    suspend fun reconcile(
        uid: String,
        finalAgentName: String,
        isTargetDifferentUser: Boolean,
        cloudHouses: List<House>,
        cloudDayActivities: List<DayActivity>,
        cloudDeletedHouses: MutableSet<String>,
        cloudDeletedActivities: MutableSet<String>,
        teammateHouses: List<House>
    ): Result<Unit> {
        return try {
            // --- SELF-HEALING: Clean up zombie tombstones in Firestore ---
            val cloudHousesWithKeys = cloudHouses.map { HouseWithKeys(it) }
            val validCloudHouseKeys = cloudHousesWithKeys.map { it.naturalKey }.toSet()
            val validCloudActivityKeys = cloudDayActivities.map { "${it.date.replace("/", "-")}|${it.agentName.uppercase()}" }.toSet()

            val zombieActivities = cloudDeletedActivities.filter { tombstoneKey ->
                val datePart = tombstoneKey.split("|")[0].replace("/", "-")
                validCloudActivityKeys.any { it.startsWith(datePart) }
            }
            val zombieHouses = cloudDeletedHouses.filter { it in validCloudHouseKeys }

            if (zombieActivities.isNotEmpty() || zombieHouses.isNotEmpty()) {
                cloudDeletedActivities.removeAll(zombieActivities.toSet())
                cloudDeletedHouses.removeAll(zombieHouses.toSet())

                try {
                    val batch = firestore.batch()
                    val docRef = firestore.collection("agents").document(uid)
                    if (zombieActivities.isNotEmpty()) {
                        val dateOnlyZombies = zombieActivities.map { it.split("|")[0] }
                        batch.update(docRef, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayRemove(*(zombieActivities + dateOnlyZombies).toTypedArray()))
                    }
                    if (zombieHouses.isNotEmpty()) {
                        batch.update(docRef, "deleted_house_ids", com.google.firebase.firestore.FieldValue.arrayRemove(*zombieHouses.toTypedArray()))
                    }
                    batch.commit().await()
                    AppLogger.i("SyncReconciler", "Self-Healing: Removed ${zombieActivities.size} zombie activity tombstones and ${zombieHouses.size} house tombstones from Firestore.")
                } catch (e: Exception) {
                    AppLogger.w("SyncReconciler", "Self-Healing failed: ${e.message}")
                }
            }

            cloudDeletedHouses.removeAll { it in validCloudHouseKeys }
            cloudDeletedActivities.removeAll { it in validCloudActivityKeys }

            val allLocalHouses = houseRepository.getHousesByAgentSnapshot(uid)
            val combinedLocalHouses = allLocalHouses + teammateHouses
            val allLocalHousesWithKeys = combinedLocalHouses.map { HouseWithKeys(it) }

            val cloudActiveIdentities = cloudHousesWithKeys.map { it.identityKey }.toSet()

            val cloudDeletedIdentities = cloudDeletedHouses.mapNotNull { deletedKey ->
                val parts = deletedKey.split("_")
                if (parts.size >= 11) {
                    try {
                        "${parts[0]}_${parts[2]}_${parts[3]}_${parts[4]}_${parts[5]}_${parts[6]}_${parts[7]}_${parts[8]}_${parts[9]}".uppercase()
                    } catch (e: Exception) { null }
                } else null
            }.toSet()

            val allLocalActivities = houseRepository.getDayActivitiesByAgentSnapshot(uid)
            val closedDates = allLocalActivities.filter { it.isClosed && !it.isManualUnlock }.map { it.date.replace("/", "-") }.toSet()

            val housesToDelete = allLocalHousesWithKeys.filter { wrapper ->
                val house = wrapper.house
                if (house.agentUid != uid) return@filter false // Teammate deletions are handled in performTeamworkSync
                if (house.data.replace("/", "-") in closedDates) return@filter false // CLOSED-DAY GUARD
                val key = wrapper.naturalKey
                val identityKey = wrapper.identityKey

                val isRealDeletion = (key in cloudDeletedHouses) || 
                                     (identityKey in cloudDeletedIdentities && identityKey !in cloudActiveIdentities) ||
                                     ("${house.data.replace("/", "-")}|${house.agentName.uppercase()}" in cloudDeletedActivities)

                if (isRealDeletion) {
                    val timeSinceLastUpdate = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis() - house.lastUpdated
                    if (house.isSynced) {
                        true
                    } else if (timeSinceLastUpdate > 900000L) {
                        AppLogger.i("SyncReconciler", "Admin Authority / Ghost Cleanup: Deleting unsynced house ${house.id} due to cloud deletion.")
                        true
                    } else {
                        AppLogger.i("SyncReconciler", "Agent Priority: Preserving actively typed house ${house.id} despite cloud deletion.")
                        false
                    }
                } else false
            }.map { it.house }

            allLocalActivities.filter { it.date.replace("/", "-") in cloudDeletedActivities && it.date.replace("/", "-") !in closedDates }.forEach {
                AppLogger.i("SyncReconciler", "Cloud Deletion Sync: Deleting local activity ${it.date} for $finalAgentName")
                houseRepository.runInTransaction {
                    houseRepository.deleteDayActivity(it.date, it.agentUid)
                }
            }

            val activitiesToDelete = allLocalActivities.filter { activity ->
                val dateKey = "${activity.date}|${activity.agentUid}"

                if (dateKey in cloudDeletedActivities) {
                    val timeSinceLastUpdate = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis() - activity.lastUpdated
                    if (activity.isSynced) {
                        true
                    } else if (timeSinceLastUpdate > 900000L) {
                        AppLogger.i("SyncReconciler", "Admin Authority / Ghost Cleanup: Deleting unsynced activity ${activity.date} due to cloud deletion.")
                        true
                    } else {
                        AppLogger.i("SyncReconciler", "Agent Priority: Preserving actively typed activity ${activity.date} despite cloud deletion.")
                        false
                    }
                } else false
            }

            // Reconciliation
            val localHousesByNaturalKey = allLocalHousesWithKeys.associateBy { it.naturalKey }
            val localActivities = allLocalActivities.groupBy { "${it.date.replace("/", "-")}|${it.agentUid}" }

            val localTombstones = houseRepository.getAllTombstones(uid)
            val localHouseTombstoneKeys = localTombstones.filter { it.type == TombstoneType.HOUSE }.map { it.naturalKey }.toSet()
            val localActivityTombstoneKeys = localTombstones.filter { it.type == TombstoneType.ACTIVITY }.map { it.naturalKey }.toSet()

            val housesDelta = cloudHousesWithKeys.filter {
                it.naturalKey !in cloudDeletedHouses && it.naturalKey !in localHouseTombstoneKeys
            }
            val activitiesDelta = cloudDayActivities.filter {
                val dateKey = "${it.date.replace("/", "-")}|${it.agentUid}"
                dateKey !in cloudDeletedActivities && dateKey !in localActivityTombstoneKeys
            }

            houseRepository.runInTransaction {
                if (housesToDelete.isNotEmpty()) {
                    val tombstonesToInsert = mutableListOf<Tombstone>()
                    for (house in housesToDelete) {
                        houseRepository.deleteHouse(house)
                        tombstonesToInsert.add(
                            Tombstone(
                                type = TombstoneType.HOUSE,
                                naturalKey = house.generateNaturalKey(),
                                agentName = house.agentName,
                                agentUid = house.agentUid,
                                dataDate = house.data
                            )
                        )
                    }
                    if (tombstonesToInsert.isNotEmpty()) {
                        houseRepository.insertTombstones(tombstonesToInsert)
                    }
                }

                if (activitiesToDelete.isNotEmpty()) {
                    val tombstonesToInsert = mutableListOf<Tombstone>()
                    for (activity in activitiesToDelete) {
                        houseRepository.deleteDayActivity(activity.date, activity.agentUid)
                        tombstonesToInsert.add(
                            Tombstone(
                                type = TombstoneType.ACTIVITY,
                                naturalKey = "${activity.date.replace("/", "-")}|${activity.agentUid}",
                                agentName = activity.agentName,
                                agentUid = activity.agentUid,
                                dataDate = activity.date
                            )
                        )
                    }
                    if (tombstonesToInsert.isNotEmpty()) {
                        houseRepository.insertTombstones(tombstonesToInsert)
                    }
                }

                val localIdentityMap = if (housesDelta.isNotEmpty()) allLocalHousesWithKeys.groupBy { it.identityKey } else emptyMap()

                val housesToUpsert = housesDelta.mapNotNull { cloudWrapper ->
                    val cloudHouse = cloudWrapper.house

                    val isTeammate = cloudHouse.agentUid.isNotBlank() && cloudHouse.agentUid != uid
                    if (!isTeammate) {
                        val normalizedDate = cloudHouse.data.replace("/", "-")
                        val dateKey = "$normalizedDate|$uid"
                        val dayActivity = localActivities[dateKey]?.firstOrNull()
                        val cloudActivity = activitiesDelta.find { it.date.replace("/", "-") == normalizedDate }

                        val isCloudUnlocked = cloudActivity?.isManualUnlock == true
                        val isLocallyClosed = dayActivity?.isClosed == true && dayActivity.isManualUnlock != true

                        if (isLocallyClosed && !isCloudUnlocked && !cloudHouse.editedByAdmin && !isTargetDifferentUser) {
                            return@mapNotNull null
                        }
                    }

                    val key = cloudWrapper.naturalKey
                    var existing = localHousesByNaturalKey[key]?.house

                    if (existing == null) {
                        val identityKey = cloudWrapper.identityKey
                        existing = localIdentityMap[identityKey]?.find { it.house.agentUid == cloudHouse.agentUid }?.house
                    }

                    if (existing != null && !existing.isSynced) {
                        val isAdminOverride = cloudHouse.editedByAdmin && !existing.editedByAdmin &&
                            (System.currentTimeMillis() - existing.lastUpdated > 120000L)

                        if (!isAdminOverride) {
                            val threshold = AppConstants.SYNC_CONFLICT_THRESHOLD_MS
                            if (existing.lastUpdated > (cloudHouse.lastUpdated + threshold)) {
                                return@mapNotNull null
                            }
                        }
                    }

                    cloudHouse.copy(
                        id = existing?.id ?: 0,
                        agentName = if (isTeammate) cloudHouse.agentName else finalAgentName,
                        agentUid = if (isTeammate) cloudHouse.agentUid else uid,
                        isSynced = true
                    )
                }

                val activitiesToUpsert = activitiesDelta.mapNotNull { activity ->
                    val normalizedDate = activity.date.replace("/", "-")
                    val key = "$normalizedDate|$uid"
                    val dateKey = "$normalizedDate|$uid"

                    if (dateKey in cloudDeletedActivities) {
                        return@mapNotNull null
                    }

                    val existing = localActivities[key]?.firstOrNull()

                    if (existing != null && !existing.isSynced) {
                        val isRemoteUnlock = activity.isManualUnlock && !existing.isManualUnlock
                        val isAdminOverride = activity.editedByAdmin && !existing.editedByAdmin &&
                            (System.currentTimeMillis() - existing.lastUpdated > 120000L)

                        val threshold = AppConstants.SYNC_CONFLICT_THRESHOLD_MS

                        if (!isRemoteUnlock && !isAdminOverride && existing.lastUpdated > (activity.lastUpdated + threshold)) {
                            return@mapNotNull null
                        }
                    }

                    // CLOSED DAY GUARD: Preserve local isClosed state when cloud tries to reopen
                    val preserveClosedState = existing != null && existing.isClosed && !existing.isManualUnlock
                        && !activity.isClosed && !activity.editedByAdmin && !activity.isManualUnlock

                    activity.copy(
                        date = normalizedDate,
                        agentName = finalAgentName,
                        agentUid = uid,
                        isSynced = true,
                        isClosed = if (preserveClosedState) true else activity.isClosed,
                        isManualUnlock = if (preserveClosedState) false else activity.isManualUnlock
                    )
                }

                houseRepository.upsertHousesRaw(housesToUpsert)
                houseRepository.upsertDayActivitiesRaw(activitiesToUpsert)

                housesToUpsert.forEach { house ->
                    houseRepository.deleteTombstoneByNaturalKey(house.generateNaturalKey(), house.agentUid)
                }
                activitiesToUpsert.forEach { activity ->
                    houseRepository.deleteTombstoneByNaturalKey("${activity.date.replace("/", "-")}|${activity.agentUid}", activity.agentUid)
                }

                housesToUpsert.forEach { pulledHouse ->
                    val key = pulledHouse.generateNaturalKey()
                    val localMatches = allLocalHousesWithKeys.filter { it.naturalKey == key && it.house.id != pulledHouse.id && it.house.id != 0 }
                    localMatches.forEach { match ->
                        if (match.house.isSynced && match.house.data.replace("/", "-") !in closedDates) {
                            houseRepository.deleteHouse(match.house)
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e("SyncReconciler", "Reconciliation failed", e)
            Result.failure(e)
        }
    }
}
