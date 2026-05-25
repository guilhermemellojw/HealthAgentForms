package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.SyncRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CleanupBrokenHousesUseCaseTest {

    private val houseDao = mockk<HouseDao>(relaxed = true)
    private val syncRepository = mockk<SyncRepository>(relaxed = true)
    private lateinit var useCase: CleanupBrokenHousesUseCase

    @Before
    fun setup() {
        useCase = CleanupBrokenHousesUseCase(houseDao, syncRepository)
    }

    @Test
    fun `returns success 0 when no broken houses found`() = runBlocking {
        coEvery { houseDao.getEmptyHouses("uid_1") } returns emptyList()

        val result = useCase("uid_1")

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
        coVerify(exactly = 0) { syncRepository.deleteHousesSurgically(any(), any()) }
    }

    @Test
    fun `deletes broken houses and returns count`() = runBlocking {
        val broken = listOf(
            House(id = 1, agentUid = "uid_1"),
            House(id = 2, agentUid = "uid_1"),
            House(id = 3, agentUid = "uid_1")
        )
        coEvery { houseDao.getEmptyHouses("uid_1") } returns broken
        coEvery { syncRepository.deleteHousesSurgically("uid_1", broken) } returns Result.success(Unit)

        val result = useCase("uid_1")

        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrNull())
        coVerify { syncRepository.deleteHousesSurgically("uid_1", broken) }
    }

    @Test
    fun `returns failure when surgical delete fails`() = runBlocking {
        val broken = listOf(House(id = 1, agentUid = "uid_1"))
        val error = Exception("Sync failed")
        coEvery { houseDao.getEmptyHouses("uid_1") } returns broken
        coEvery { syncRepository.deleteHousesSurgically("uid_1", broken) } returns Result.failure(error)

        val result = useCase("uid_1")

        assertTrue(result.isFailure)
        assertEquals("Sync failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun `returns failure when dao throws exception`() = runBlocking {
        coEvery { houseDao.getEmptyHouses(any()) } throws RuntimeException("DB error")

        val result = useCase("uid_1")

        assertTrue(result.isFailure)
        assertEquals("DB error", result.exceptionOrNull()?.message)
    }
}
