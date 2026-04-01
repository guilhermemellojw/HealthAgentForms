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
}
