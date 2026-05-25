package com.antigravity.healthagent.domain.usecase

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DayLockEnforcerTest {

    private lateinit var enforcer: DayLockEnforcer

    @Before
    fun setup() {
        enforcer = DayLockEnforcer()
    }

    @Test
    fun `open day always allows`() {
        val result = enforcer.enforce(isDayClosed = false, isManualUnlock = false, isAdmin = false)
        assertTrue(result is DayLockEnforcer.LockResult.Allowed)
    }

    @Test
    fun `closed day blocks regular user`() {
        val result = enforcer.enforce(isDayClosed = true, isManualUnlock = false, isAdmin = false)
        assertTrue(result is DayLockEnforcer.LockResult.Blocked)
    }

    @Test
    fun `closed day allows admin`() {
        val result = enforcer.enforce(isDayClosed = true, isManualUnlock = false, isAdmin = true)
        assertTrue(result is DayLockEnforcer.LockResult.Allowed)
    }

    @Test
    fun `closed day allows manual unlock`() {
        val result = enforcer.enforce(isDayClosed = true, isManualUnlock = true, isAdmin = false)
        assertTrue(result is DayLockEnforcer.LockResult.Allowed)
    }

    @Test
    fun `blocked message contains action description`() {
        val result = enforcer.enforce(
            isDayClosed = true,
            isManualUnlock = false,
            isAdmin = false,
            actionDescription = "deletar"
        )
        assertTrue(result is DayLockEnforcer.LockResult.Blocked)
        assertTrue((result as DayLockEnforcer.LockResult.Blocked).message.contains("deletar"))
    }
}
