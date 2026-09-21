package com.antigravity.healthagent.data.remote.supabase

import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.UserRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Linha de public.profiles + mapeamento puro para AuthUser (testável sem rede).
 * Paridade com AuthRepositoryImpl.getFullUserData (Firebase).
 */
@Serializable
data class SupabaseProfileRow(
    val id: String,
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    val role: String? = null,
    @SerialName("is_authorized") val isAuthorized: Boolean = false,
    @SerialName("agent_name") val agentName: String? = null,
    @SerialName("require_data_reset") val requireDataReset: Boolean = false
)

fun SupabaseProfileRow.toAuthUser(
    authUid: String,
    authEmail: String?,
    authDisplayName: String?,
    authPhotoUrl: String?,
    bootstrapAdmins: List<String> = emptyList()
): AuthUser {
    val email = (email ?: authEmail ?: "").trim().lowercase()
    if (bootstrapAdmins.contains(email)) {
        return AuthUser(
            uid = authUid,
            email = email,
            displayName = displayName ?: authDisplayName,
            photoUrl = photoUrl ?: authPhotoUrl,
            role = UserRole.ADMIN,
            isAuthorized = true,
            agentName = null
        )
    }
    val finalRole = try {
        UserRole.valueOf((role ?: "AGENT").uppercase())
    } catch (_: Exception) {
        UserRole.AGENT
    }
    return AuthUser(
        uid = authUid,
        email = email,
        displayName = displayName ?: authDisplayName,
        photoUrl = photoUrl ?: authPhotoUrl,
        role = finalRole,
        isAuthorized = isAuthorized,
        agentName = agentName?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
    )
}
