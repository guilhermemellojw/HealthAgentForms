package com.antigravity.healthagent.domain.usecase

import android.content.Context
import android.net.Uri
import com.antigravity.healthagent.data.backup.BackupData
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.AuthRepository
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
    fun `invoke should preserve agent names from backup houses and activities`() = runBlocking {
        // Arrange
        val targetUid = "target-uid"
        val houses = listOf(
            House(agentName = "AGENT A", createdAt = 1000, data = "15-03-2026"),
            House(agentName = "", createdAt = 2000, data = "15-03-2026")
        )
        val activities = listOf(
            DayActivity(date = "15-03-2026", agentName = "AGENT A"),
            DayActivity(date = "15-03-2026", agentName = "AGENT B")
        )
        val backupData = BackupData(houses = houses, dayActivities = activities)

        every { authRepository.getCurrentUserUid() } returns targetUid
        coEvery { authRepository.fetchAllUsers() } returns Result.success(emptyList()) // Fixed: coEvery for suspend
        every { backupManager.importData(any(), any()) } returns backupData

        val houseSlot = slot<List<House>>()
        val activitySlot = slot<List<DayActivity>>()

        // Act
        useCase(context, targetUid, uri)

        // Assert
        coVerify { 
            syncRepository.restoreLocalData(
                agentName = any(),
                houses = capture(houseSlot),
                activities = capture(activitySlot)
            )
        }

        assertEquals("House 0 agentName mismatch", "AGENT A", houseSlot.captured[0].agentName)
        assertEquals("House 1 agentName mismatch", "RESTORADO", houseSlot.captured[1].agentName)
        
        assertEquals("Activity 0 agentName mismatch", "AGENT A", activitySlot.captured[0].agentName)
        assertEquals("Activity 1 agentName mismatch", "AGENT B", activitySlot.captured[1].agentName)
    }
}
