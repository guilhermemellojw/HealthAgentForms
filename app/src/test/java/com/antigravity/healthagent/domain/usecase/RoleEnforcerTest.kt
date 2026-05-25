package com.antigravity.healthagent.domain.usecase

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RoleEnforcerTest {

    private lateinit var enforcer: RoleEnforcer

    @Before
    fun setup() {
        enforcer = RoleEnforcer()
    }

    @Test
    fun `non-supervisor user is always allowed`() {
        val result = enforcer.enforce(isSupervisor = false, isAdmin = false)
        assertTrue(result is RoleEnforcer.RoleResult.Allowed)
    }

    @Test
    fun `admin supervisor is allowed`() {
        val result = enforcer.enforce(isSupervisor = true, isAdmin = true)
        assertTrue(result is RoleEnforcer.RoleResult.Allowed)
    }

    @Test
    fun `non-admin supervisor is blocked`() {
        val result = enforcer.enforce(isSupervisor = true, isAdmin = false)
        assertTrue(result is RoleEnforcer.RoleResult.Blocked)
    }

    @Test
    fun `blocked message contains action description`() {
        val result = enforcer.enforce(
            isSupervisor = true,
            isAdmin = false,
            actionDescription = "adicionar dados remotamente"
        )
        assertTrue(result is RoleEnforcer.RoleResult.Blocked)
        assertTrue((result as RoleEnforcer.RoleResult.Blocked).message.contains("adicionar dados remotamente"))
    }
}
