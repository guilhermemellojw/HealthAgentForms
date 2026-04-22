package com.antigravity.healthagent.domain.usecase

import android.content.Context
import android.net.Uri
import com.antigravity.healthagent.data.backup.BackupData
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.SyncRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertEquals

class RestoreDataUseCaseTest {

    private val syncRepository = mockk<SyncRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val backupManager = mockk<BackupManager>()
    private val context = mockk<Context>()
    private val uri = mockk<Uri>()

    private val useCase = RestoreDataUseCase(syncRepository, authRepository, backupManager)

    @Test
    fun `invoke should use agent name from auth repository when available`() = runBlocking {
        // Arrange
        val targetUid = "target-uid"
        val mockUser = AuthUser(
            uid = targetUid,
            email = "agent@test.com",
            displayName = "Agent Tester",
            photoUrl = null,
            agentName = "AGENT MASTER"
        )
        val houses = listOf(
            House(agentName = "OLD NAME", data = "15-03-2026"),
        )
        val activities = listOf(
            DayActivity(date = "15-03-2026", agentName = "OLD NAME"),
        )
        val backupData = BackupData(houses = houses, dayActivities = activities)

        every { authRepository.getCurrentUserUid() } returns "current-uid"
        coEvery { authRepository.fetchAllUsers() } returns Result.success(listOf(mockUser))
        every { backupManager.importData(any(), any()) } returns backupData

        val houseSlot = slot<List<House>>()
        val activitySlot = slot<List<DayActivity>>()

        // Act
        useCase(
            context = context,
            targetUid = targetUid,
            fileUri = uri
        )

        // Assert
        coVerify { 
            syncRepository.pushLocalDataToCloud(
                houses = capture(houseSlot),
                activities = capture(activitySlot),
                targetUid = targetUid,
                shouldReplace = true
            )
        }

        assertEquals("House agentName should be normalized from AuthRepository", "AGENT MASTER", houseSlot.captured[0].agentName)
        assertEquals("Activity agentName should be normalized from AuthRepository", "AGENT MASTER", activitySlot.captured[0].agentName)
    }

    @Test
    fun `invoke should preserve original agent name when house belongs to a different agent than backup owner`() = runBlocking {
        // Arrange
        val targetUid = "restorer-uid"
        val backupOwnerUid = "restorer-uid"
        val otherAgentUid = "other-uid"
        
        val houses = listOf(
            House(agentUid = backupOwnerUid, agentName = "RESTORER", streetName = "MY STREET", data = "15-03-2026"),
            House(agentUid = otherAgentUid, agentName = "OTHER AGENT", streetName = "OTHER STREET", data = "15-03-2026")
        )
        val backupData = BackupData(
            houses = houses, 
            dayActivities = emptyList(),
            sourceAgentUid = backupOwnerUid,
            sourceAgentName = "RESTORER"
        )

        every { authRepository.getCurrentUserUid() } returns targetUid
        coEvery { authRepository.fetchAllUsers() } returns Result.success(emptyList())
        every { backupManager.importData(any(), any()) } returns backupData

        val houseSlot = slot<List<House>>()

        // Act
        useCase(
            context = context,
            targetUid = targetUid,
            fileUri = uri
        )

        // Assert
        coVerify { 
            syncRepository.pushLocalDataToCloud(
                houses = capture(houseSlot),
                activities = any(),
                targetUid = targetUid,
                shouldReplace = true
            )
        }

        val restoredHouses = houseSlot.captured
        assertEquals("Own house should be re-assigned/normalized", targetUid, restoredHouses[0].agentUid)
        assertEquals("Other agent's house should be PRESERVED", otherAgentUid, restoredHouses[1].agentUid)
        assertEquals("Other agent's name should be PRESERVED", "OTHER AGENT", restoredHouses[1].agentName)
    }
}
