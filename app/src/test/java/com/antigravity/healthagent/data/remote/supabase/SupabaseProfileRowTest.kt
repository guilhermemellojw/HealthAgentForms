package com.antigravity.healthagent.data.remote.supabase

import com.antigravity.healthagent.domain.repository.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseProfileRowTest {

    @Test
    fun bootstrapEmailBecomesAdmin() {
        val row = SupabaseProfileRow(id = "u1", email = "gmellobkp@gmail.com", role = "AGENT")
        val user = row.toAuthUser("u1", "gmellobkp@gmail.com", "Gui", null, listOf("gmellobkp@gmail.com"))
        assertEquals(UserRole.ADMIN, user.role)
        assertTrue(user.isAuthorized)
        assertTrue(user.isAdmin)
        assertNull(user.agentName)
    }

    @Test
    fun roleMappingAndNormalization() {
        val row = SupabaseProfileRow(
            id = "u2", email = "agente@x.com", role = "supervisor",
            isAuthorized = true, agentName = "  joão silva  "
        )
        val user = row.toAuthUser("u2", "agente@x.com", null, null)
        assertEquals(UserRole.SUPERVISOR, user.role)
        assertTrue(user.isAuthorized)
        assertEquals("JOÃO SILVA", user.agentName)
    }

    @Test
    fun invalidRoleFallsBackToAgent() {
        val row = SupabaseProfileRow(id = "u3", email = "a@x.com", role = "BOSS")
        val user = row.toAuthUser("u3", "a@x.com", null, null)
        assertEquals(UserRole.AGENT, user.role)
        assertFalse(user.isAuthorized)
    }

    @Test
    fun missingRowFieldsFallBackToAuth() {
        val row = SupabaseProfileRow(id = "u4")
        val user = row.toAuthUser("u4", "novo@x.com", "Novo", "http://pic")
        assertEquals("novo@x.com", user.email)
        assertEquals("Novo", user.displayName)
        assertEquals("http://pic", user.photoUrl)
        assertEquals(UserRole.AGENT, user.role)
        assertNull(user.agentName)
    }
}
