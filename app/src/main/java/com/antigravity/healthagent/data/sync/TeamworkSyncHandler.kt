package com.antigravity.healthagent.data.sync

import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.util.toHouseSafe
import com.antigravity.healthagent.domain.logger.AppLogger
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TeamworkSyncHandler @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val houseRepository: HouseRepository
) {
    suspend fun performTeamworkSync(
        uid: String,
        cloudHouses: MutableList<House>,
        isTargetDifferentUser: Boolean
    ): List<House> {
        val teammateHouses = mutableListOf<House>()
        if (isTargetDifferentUser) return teammateHouses

        try {
            val activeBairros = (houseRepository.getActiveBairros(uid) + cloudHouses.map { it.address.bairro })
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (activeBairros.isNotEmpty()) {
                val teamHouses = activeBairros.chunked(10).flatMap { bairroChunk ->
                    firestore.collectionGroup("houses")
                        .whereIn("bairro", bairroChunk)
                        .get().await().documents
                }.mapNotNull { it.toHouseSafe(it.getString("agentUid") ?: "", it.getString("agentName") ?: "") }
                
                val remoteForeignHouses = teamHouses.filter { it.agentUid != uid }
                
                val activeBlocks = (houseRepository.getActiveBlockNumbers() + cloudHouses.map { it.address.blockNumber })
                    .filter { it.isNotBlank() }
                    .distinct()
                val localTeamHouses = houseRepository.getHousesByBlocks(activeBlocks).filter { 
                    it.agentUid.isNotBlank() && it.agentUid != uid && it.address.bairro.trim().uppercase() in activeBairros
                }
                val remoteKeys = remoteForeignHouses.map { it.generateNaturalKey() }.toSet()
                
                val housesDeletedByTeam = localTeamHouses.filter { it.generateNaturalKey() !in remoteKeys }
                if (housesDeletedByTeam.isNotEmpty()) {
                    // CLOSED DAY GUARD: Preserve teammate houses in locally closed days
                    val closedDatesForTeam = housesDeletedByTeam.map { it.data.replace("/", "-") }.distinct().filter { date ->
                        val activity = houseRepository.getDayActivity(date, uid)
                        activity?.isClosed == true && !activity.isManualUnlock
                    }.toSet()
                    val safeToDeleteTeam = housesDeletedByTeam.filter { it.data.replace("/", "-") !in closedDatesForTeam }
                    if (closedDatesForTeam.isNotEmpty()) {
                        AppLogger.w("TeamworkSyncHandler", "Team Sync: Preserved ${housesDeletedByTeam.size - safeToDeleteTeam.size} teammate houses in ${closedDatesForTeam.size} closed days.")
                    }
                    if (safeToDeleteTeam.isNotEmpty()) {
                        AppLogger.i("TeamworkSyncHandler", "Team Sync: Deleting ${safeToDeleteTeam.size} houses removed by colleagues.")
                        houseRepository.runInTransaction {
                            safeToDeleteTeam.forEach { houseRepository.deleteHouse(it) }
                        }
                    }
                }
                
                cloudHouses.addAll(remoteForeignHouses)

                // Refresh local team houses after deletions to accurately reconstruct local IDs
                val remainingTeammateHouses = houseRepository.getHousesByBlocks(activeBlocks).filter { 
                    it.agentUid.isNotBlank() && it.agentUid != uid && it.address.bairro.trim().uppercase() in activeBairros
                }
                teammateHouses.addAll(remainingTeammateHouses)
            }
        } catch (e: Exception) {
            AppLogger.w("TeamworkSyncHandler", "Teamwork sync failed (skipping): ${e.message}")
        }
        return teammateHouses
    }
}
