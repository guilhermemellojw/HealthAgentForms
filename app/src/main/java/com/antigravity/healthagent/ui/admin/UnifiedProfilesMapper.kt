package com.antigravity.healthagent.ui.admin

import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.UserRole

/**
 * Pure, testable builder for the admin unified profile list.
 *
 * Linking rules (Fase 1):
 * - Emails are ALWAYS normalized with trim().lowercase(); names with trim().uppercase().
 * - A pre-registered doc ("pre_<email>") and the real account with the same
 *   normalized email are merged into ONE card owned by the real UID. The real
 *   user is never silently dropped.
 * - AgentData lookup prioritizes UID match. Email fallback is only used when
 *   there is exactly ONE candidate; ambiguous matches attach nothing and flag
 *   [UnifiedProfile.hasAmbiguousLink] so the admin can resolve manually.
 */
object UnifiedProfilesMapper {

    fun normalizeEmail(email: String?): String? =
        email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    fun normalizeName(name: String?): String? =
        name?.trim()?.uppercase()?.takeIf { it.isNotBlank() }

    fun buildUnifiedProfiles(
        usersList: List<AuthUser>,
        agentsList: List<AgentData>,
        namesList: List<String>,
        query: String = ""
    ): List<UnifiedProfile> {
        val agentsByUid: Map<String, AgentData> = agentsList.associateBy { it.uid }
        // Only pre-registered agent docs participate in the email fallback,
        // grouped so ambiguity (>= 2 candidates) can be detected.
        val preAgentsByEmail: Map<String, List<AgentData>> = agentsList
            .filter { it.uid.startsWith("pre_") }
            .groupBy { it.email.trim().lowercase() }

        val result = mutableListOf<UnifiedProfile>()
        val processedAgentUids = mutableSetOf<String>()
        val processedEmails = mutableSetOf<String>()

        // Group users by normalized email. Users without email each form
        // their own group so they are never merged/dropped.
        val groups = linkedMapOf<String, MutableList<AuthUser>>()
        usersList.forEach { user ->
            val key = normalizeEmail(user.email) ?: "uid:${user.uid}"
            groups.getOrPut(key) { mutableListOf() }.add(user)
        }

        // Real accounts first within each group for deterministic merge.
        groups.values.forEach { group ->
            val sorted = group.sortedWith(compareBy { it.uid.startsWith("pre_") })
            val realUsers = sorted.filter { !it.uid.startsWith("pre_") }
            val preUsers = sorted.filter { it.uid.startsWith("pre_") }

            if (realUsers.isNotEmpty()) {
                // One card per real account (multiple reals with the same email
                // is corrupt data: show all of them instead of dropping any).
                realUsers.forEachIndexed { index, real ->
                    val pendingPre = index == 0 && preUsers.isNotEmpty()
                    val emailKey = normalizeEmail(real.email)
                    var agentData = agentsByUid[real.uid]
                    var ambiguous = false
                    if (agentData == null && emailKey != null && !processedEmails.contains(emailKey)) {
                        val candidates = preAgentsByEmail[emailKey].orEmpty()
                            .filter { !processedAgentUids.contains(it.uid) }
                        when {
                            candidates.size == 1 -> agentData = candidates.first()
                            candidates.size > 1 -> ambiguous = true
                        }
                    }
                    // Prefer production data name, then the pre-registered name,
                    // then the account's own name.
                    val preName = preUsers.firstOrNull()?.agentName?.takeIf { it.isNotBlank() }
                    result.add(
                        UnifiedProfile(
                            uid = real.uid,
                            email = real.email,
                            agentName = agentData?.agentName ?: preName ?: real.agentName,
                            role = real.role,
                            isAuthorized = real.isAuthorized,
                            isPreRegistered = false,
                            agentData = agentData,
                            hasPendingPreMigration = pendingPre,
                            hasAmbiguousLink = ambiguous
                        )
                    )
                    processedAgentUids.add(real.uid)
                    preUsers.forEach { processedAgentUids.add(it.uid) }
                    emailKey?.let { processedEmails.add(it) }
                    agentData?.let {
                        processedAgentUids.add(it.uid)
                        processedEmails.add(it.email.trim().lowercase())
                    }
                }
            } else {
                // Only pre-registered docs in this group: single merged card.
                val pre = preUsers.first()
                val emailKey = normalizeEmail(pre.email)
                var agentData = agentsByUid[pre.uid]
                var ambiguous = false
                if (agentData == null && emailKey != null) {
                    val candidates = preAgentsByEmail[emailKey].orEmpty()
                        .filter { !processedAgentUids.contains(it.uid) }
                    when {
                        candidates.size == 1 -> agentData = candidates.first()
                        candidates.size > 1 -> ambiguous = true
                    }
                }
                result.add(
                    UnifiedProfile(
                        uid = pre.uid,
                        email = pre.email,
                        agentName = agentData?.agentName ?: pre.agentName,
                        role = pre.role,
                        isAuthorized = pre.isAuthorized,
                        isPreRegistered = true,
                        agentData = agentData,
                        hasPendingPreMigration = false,
                        hasAmbiguousLink = ambiguous
                    )
                )
                preUsers.forEach { processedAgentUids.add(it.uid) }
                emailKey?.let { processedEmails.add(it) }
                agentData?.let {
                    processedAgentUids.add(it.uid)
                    processedEmails.add(it.email.trim().lowercase())
                }
            }
        }

        // Agents from Firestore without a user account yet.
        agentsList.forEach { agent ->
            val emailKey = agent.email.trim().lowercase()
            if (!processedAgentUids.contains(agent.uid) && !processedEmails.contains(emailKey)) {
                result.add(
                    UnifiedProfile(
                        uid = agent.uid,
                        email = agent.email,
                        agentName = agent.agentName,
                        role = UserRole.AGENT,
                        isAuthorized = true,
                        isPreRegistered = agent.uid.startsWith("pre_"),
                        agentData = agent
                    )
                )
                processedAgentUids.add(agent.uid)
                processedEmails.add(emailKey)
            }
        }

        // Master-list names not linked yet (case-insensitive).
        val existingNamesUpperCase = result.mapNotNull { normalizeName(it.agentName) }.toSet()
        namesList.forEach { name ->
            val normalizedName = normalizeName(name) ?: return@forEach
            if (!existingNamesUpperCase.contains(normalizedName)) {
                result.add(
                    UnifiedProfile(
                        uid = null,
                        email = null,
                        agentName = name,
                        role = UserRole.AGENT,
                        isAuthorized = false,
                        isPreRegistered = true,
                        agentData = null
                    )
                )
            }
        }

        if (query.isBlank()) return result
        return result.filter {
            it.email?.contains(query, true) == true ||
                it.agentName?.contains(query, true) == true
        }
    }
}
