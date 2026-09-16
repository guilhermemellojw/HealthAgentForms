package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.dao.AgentCacheDao
import com.antigravity.healthagent.data.remote.AgentRemoteDataSource
import com.antigravity.healthagent.data.remote.HouseRemoteDataSource
import com.antigravity.healthagent.domain.repository.UserRole
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminNameManagementTest {

    // ---- C4: buildPreRegisteredUserData ----

    @Test
    fun `pre-registered user data omits null agentName`() {
        val data = buildPreRegisteredUserData(
            email = "a@b.com",
            role = UserRole.AGENT,
            agentName = null,
            isAuthorized = true,
            createdAt = 1000L
        )
        assertFalse(data.containsKey("agentName"))
        assertEquals("a@b.com", data["email"])
        assertEquals(true, data["isPreRegistered"])
    }

    @Test
    fun `pre-registered user data normalizes agentName and omits blank`() {
        val data = buildPreRegisteredUserData(
            email = "a@b.com",
            role = UserRole.AGENT,
            agentName = "  carlos  ",
            isAuthorized = true,
            createdAt = 1000L
        )
        assertEquals("CARLOS", data["agentName"])

        val blank = buildPreRegisteredUserData(
            email = "c@d.com",
            role = UserRole.AGENT,
            agentName = "   ",
            isAuthorized = false,
            createdAt = 0L
        )
        assertFalse(blank.containsKey("agentName"))
    }

    // ---- C6: resolveProfileAgentNameChange ----

    @Test
    fun `profile agent name change resolves set clear and none`() {
        assertEquals(AgentNameChange.Set("MARIA"), resolveProfileAgentNameChange(mapOf("agentName" to " maria ")))
        assertEquals(AgentNameChange.Clear, resolveProfileAgentNameChange(mapOf("agentName" to null)))
        assertEquals(AgentNameChange.Clear, resolveProfileAgentNameChange(mapOf("agentName" to "")))
        assertEquals(AgentNameChange.Clear, resolveProfileAgentNameChange(mapOf("agentName" to "   ")))
        assertEquals(AgentNameChange.None, resolveProfileAgentNameChange(mapOf("email" to "x@y.com")))
    }

    // ---- C3: resolveApprovalAgentName ----

    @Test
    fun `approval name prefers dialog name then requested name, normalizes, and falls back to null`() {
        assertEquals("PAULO", resolveApprovalAgentName(" paulo ", "pedro"))
        assertEquals("PEDRO", resolveApprovalAgentName(null, " pedro "))
        assertEquals("PEDRO", resolveApprovalAgentName("   ", "pedro"))
        assertEquals(null, resolveApprovalAgentName("", "  "))
        assertEquals(null, resolveApprovalAgentName(null, null))
    }

    // ---- C1: AgentRepositoryImpl cache invalidation ----

    @Test
    fun `addAgentName invalidates cached names list`() = runTest {
        val dataSource = mockk<AgentRemoteDataSource>()
        val houseRemote = mockk<HouseRemoteDataSource>()
        val cacheDao = mockk<AgentCacheDao>()
        val repo = AgentRepositoryImpl(dataSource, houseRemote, cacheDao)

        coEvery { dataSource.fetchAgentNames() } returnsMany listOf(
            Result.success(listOf("ANA")),
            Result.success(listOf("ANA", "CARLOS"))
        )
        coEvery { dataSource.addAgentName(any()) } returns Result.success(Unit)

        assertEquals(listOf("ANA"), repo.fetchAgentNames().getOrNull())
        assertTrue(repo.addAgentName("CARLOS").isSuccess)
        assertEquals(listOf("ANA", "CARLOS"), repo.fetchAgentNames().getOrNull())
        coVerify(exactly = 2) { dataSource.fetchAgentNames() }
    }
}