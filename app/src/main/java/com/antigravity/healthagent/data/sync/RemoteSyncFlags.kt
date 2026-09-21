package com.antigravity.healthagent.data.sync

/**
 * Flags remotas que disparam wipe local — backend-neutro (Firestore ou Supabase).
 * Extraído de DocumentSnapshot para permitir fontes Supabase sem vazar tipos Firebase.
 */
data class RemoteSyncFlags(
    val requireDataResetFromUser: Boolean = false,
    val requireDataResetFromAgent: Boolean = false,
    val agentDocExists: Boolean = true
) {
    val requireDataReset: Boolean
        get() = requireDataResetFromUser || requireDataResetFromAgent
}
