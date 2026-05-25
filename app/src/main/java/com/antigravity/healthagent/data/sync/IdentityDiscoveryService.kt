package com.antigravity.healthagent.data.sync

import com.antigravity.healthagent.domain.logger.AppLogger
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityDiscoveryService @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    suspend fun discoverAndHealAgentDocs(
        uid: String,
        discoveryEmails: List<String>,
        profileAgentName: String?
    ): List<DocumentReference> {
        val possibleAgentDocs = mutableListOf(firestore.collection("agents").document(uid))
        
        discoveryEmails.forEach { dEmail ->
            try {
                val matchingEmailDocs = firestore.collection("agents")
                    .whereEqualTo("email", dEmail)
                    .get().await()
                
                matchingEmailDocs.documents.forEach { doc ->
                    if (doc.id != uid) {
                        possibleAgentDocs.add(doc.reference)
                        
                        val legacyName = doc.getString("agentName")
                        if (profileAgentName == null && !legacyName.isNullOrBlank()) {
                            AppLogger.i("IdentityDiscoveryService", "Identity Healing: Adopting legacy agentName '$legacyName' from ${doc.id} for $uid")
                            try {
                                firestore.collection("agents").document(uid).update("agentName", legacyName)
                                firestore.collection("users").document(uid).update("agentName", legacyName)
                            } catch (e: Exception) {
                                AppLogger.w("IdentityDiscoveryService", "Identity Healing failed to update cloud profile: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w("IdentityDiscoveryService", "Failed to search legacy agent docs for $dEmail", e)
            }
        }
        
        return possibleAgentDocs.distinctBy { it.path }
    }
}
