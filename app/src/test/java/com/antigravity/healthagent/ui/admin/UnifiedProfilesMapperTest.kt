package com.antigravity.healthagent.ui.admin

import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.UserRole
import org.junit.Assert.*
import org.junit.Test

class UnifiedProfilesMapperTest {

    private fun authUser(
        uid: String,
        email: String?,
        agentName: String? = null,
        authorized: Boolean = true
    ) = AuthUser(
        uid = uid,
        email = email,
        displayName = null,
        photoUrl = null,
        role = UserRole.AGENT,
        isAuthorized = authorized,
        agentName = agentName
    )

    private fun agentData(uid: String, email: String, name: String? = null) = AgentData(
        uid = uid,
        email = email,
        agentName = name,
        houses = emptyList(),
        activities = emptyList()
    )

    @Test
    fun `pre plus real with same email merge into one card owned by real uid`() {
        val preId = "pre_joao_mail_com"
        val users = listOf(
            authUser(preId, "joao@mail.com", agentName = "JOAO SILVA"),
            authUser("realUid1", "joao@mail.com", agentName = null, authorized = false)
        )

        val result = UnifiedProfilesMapper.buildUnifiedProfiles(users, emptyList(), emptyList())

        assertEquals(1, result.size)
        val card = result.first()
        assertEquals("realUid1", card.uid)
        assertEquals("JOAO SILVA", card.agentName)
        assertFalse(card.isPreRegistered)
        assertTrue(card.hasPendingPreMigration)
        assertFalse(card.isAuthorized)
    }

    @Test
    fun `merge is case insensitive on email`() {
        val users = listOf(
            authUser("pre_ana_mail_com", "ANA@Mail.com", agentName = "ANA"),
            authUser("realUid2", "ana@mail.COM")
        )

        val result = UnifiedProfilesMapper.buildUnifiedProfiles(users, emptyList(), emptyList())

        assertEquals(1, result.size)
        assertEquals("realUid2", result.first().uid)
    }

    @Test
    fun `distinct emails produce distinct cards`() {
        val users = listOf(
            authUser("uid1", "a@mail.com", agentName = "A"),
            authUser("uid2", "b@mail.com", agentName = "B")
        )

        val result = UnifiedProfilesMapper.buildUnifiedProfiles(users, emptyList(), emptyList())

        assertEquals(2, result.size)
    }

    @Test
    fun `ambiguous agent fallback attaches nothing and flags`() {
        val users = listOf(authUser("realUid3", "carlos@mail.com"))
        val agents = listOf(
            agentData("pre_carlos_mail_com", "carlos@mail.com", "CARLOS 1"),
            agentData("pre_carlos_mail_com_2", "carlos@mail.com", "CARLOS 2")
        )

        val result = UnifiedProfilesMapper.buildUnifiedProfiles(users, agents, emptyList())

        assertEquals(1, result.size)
        assertNull(result.first().agentData)
        assertTrue(result.first().hasAmbiguousLink)
    }

    @Test
    fun `single pre agent candidate attaches via email fallback`() {
        val users = listOf(authUser("realUid4", "bia@mail.com"))
        val agents = listOf(agentData("pre_bia_mail_com", "bia@mail.com", "BIA"))

        val result = UnifiedProfilesMapper.buildUnifiedProfiles(users, agents, emptyList())

        assertEquals("BIA", result.first().agentName)
        assertNotNull(result.first().agentData)
        assertFalse(result.first().hasAmbiguousLink)
    }

    @Test
    fun `master list name already linked is not duplicated case insensitively`() {
        val users = listOf(authUser("uid5", "d@mail.com", agentName = "Diego Souza"))

        val result = UnifiedProfilesMapper.buildUnifiedProfiles(users, emptyList(), listOf("DIEGO SOUZA", "NOVA PESSOA"))

        assertEquals(2, result.size)
        assertTrue(result.any { it.uid == null && it.agentName == "NOVA PESSOA" })
    }

    @Test
    fun `query filters by email or name`() {
        val users = listOf(
            authUser("uid6", "eva@mail.com", agentName = "EVA"),
            authUser("uid7", "fabio@mail.com", agentName = "FABIO")
        )

        val result = UnifiedProfilesMapper.buildUnifiedProfiles(users, emptyList(), emptyList(), "fabio")

        assertEquals(1, result.size)
        assertEquals("uid7", result.first().uid)
    }
}
