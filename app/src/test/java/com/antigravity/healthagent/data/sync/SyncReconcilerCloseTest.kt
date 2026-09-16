package com.antigravity.healthagent.data.sync

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncReconcilerCloseTest {

    private val uid = "uid_1"
    private val date = "25-05-2026"
    private val finalAgentName = "AGENTE"
    private val base = 1_000_000_000L

    private fun runReconcile(
        localHouses: List<House> = emptyList(),
        localActivities: List<DayActivity> = emptyList(),
        cloudHouses: List<House> = emptyList(),
        cloudActivities: List<DayActivity> = emptyList()
    ): Pair<List<DayActivity>, List<House>> {
        val firestore = mockk<FirebaseFirestore>()
        val repo = mockk<HouseRepository>()

        val activitiesSlot = slot<List<DayActivity>>()
        val housesSlot = slot<List<House>>()

        coEvery { repo.getHousesByAgentSnapshot(uid) } returns localHouses
        coEvery { repo.getDayActivitiesByAgentSnapshot(uid) } returns localActivities
        coEvery { repo.getAllTombstones(uid) } returns emptyList()
        coEvery { repo.runInTransaction(any<suspend () -> Any>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = args[0] as suspend () -> Any
            block()
        }
        coEvery { repo.upsertDayActivitiesRaw(capture(activitiesSlot)) } returns Unit
        coEvery { repo.upsertHousesRaw(capture(housesSlot)) } returns Unit
        coEvery { repo.deleteTombstoneByNaturalKey(any(), any()) } returns Unit

        runBlocking {
            SyncReconciler(firestore, repo).reconcile(
                uid = uid,
                finalAgentName = finalAgentName,
                isTargetDifferentUser = false,
                cloudHouses = cloudHouses,
                cloudDayActivities = cloudActivities,
                cloudDeletedHouses = mutableSetOf(),
                cloudDeletedActivities = mutableSetOf(),
                teammateHouses = emptyList()
            )
        }
        return activitiesSlot.captured to housesSlot.captured
    }

    @Test
    fun `local reclose newer than stale cloud unlock survives reconcile`() {
        val (upsertedActivities, _) = runReconcile(
            localActivities = listOf(
                DayActivity(
                    date = date, isClosed = true, isManualUnlock = false,
                    agentUid = uid, isSynced = false, lastUpdated = base + 60_000L
                )
            ),
            cloudActivities = listOf(
                DayActivity(
                    date = date, isClosed = false, isManualUnlock = true,
                    agentUid = uid, isSynced = true, lastUpdated = base
                )
            )
        )

        assertTrue("Re-close must NOT be overwritten by stale cloud unlock", upsertedActivities.none { it.date == date })
    }

    @Test
    fun `fresh remote unlock newer than local reclose wins`() {
        val (upsertedActivities, _) = runReconcile(
            localActivities = listOf(
                DayActivity(
                    date = date, isClosed = true, isManualUnlock = false,
                    agentUid = uid, isSynced = false, lastUpdated = base + 60_000L
                )
            ),
            cloudActivities = listOf(
                DayActivity(
                    date = date, isClosed = false, isManualUnlock = true,
                    agentUid = uid, isSynced = true, lastUpdated = base + 180_000L
                )
            )
        )

        val match = upsertedActivities.find { it.date == date }
        assertTrue("Fresh remote unlock must be applied", match != null)
        assertEquals(true, match!!.isManualUnlock)
        assertFalse(match.isClosed)
    }

    @Test
    fun `houses not pulled into locally reclosed day when cloud unlock is stale`() {
        val cloudHouse = House(data = date, agentUid = uid, agentName = finalAgentName)
        val (_, upsertedHouses) = runReconcile(
            localActivities = listOf(
                DayActivity(
                    date = date, isClosed = true, isManualUnlock = false,
                    agentUid = uid, isSynced = false, lastUpdated = base + 60_000L
                )
            ),
            cloudHouses = listOf(cloudHouse),
            cloudActivities = listOf(
                DayActivity(
                    date = date, isClosed = false, isManualUnlock = true,
                    agentUid = uid, isSynced = true, lastUpdated = base
                )
            )
        )

        assertTrue("Houses must NOT be pulled into a locally re-closed day", upsertedHouses.none { it.data == date })
    }
}
