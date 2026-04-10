package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.domain.repository.LocalizationRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalizationRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore
) : LocalizationRepository {

    override suspend fun fetchBairros(): Result<List<String>> {
        return try {
            val snapshot = withTimeoutOrNull(2500) {
                firestore.collection("metadata").document("locations").get().await()
            }
            if (snapshot == null || !snapshot.exists()) {
                return Result.success(com.antigravity.healthagent.utils.AppConstants.BAIRROS)
            }
            val bairros = (snapshot.get("bairros") as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() } ?: emptyList()
            Result.success(if (bairros.isEmpty()) com.antigravity.healthagent.utils.AppConstants.BAIRROS else bairros.map { it.uppercase().trim() }.sorted())
        } catch (e: Exception) {
            Result.success(com.antigravity.healthagent.utils.AppConstants.BAIRROS)
        }
    }

    override suspend fun addBairro(name: String): Result<Unit> {
        return try {
            val normalizedName = name.trim().uppercase()
            val docRef = firestore.collection("metadata").document("locations")
            val snapshot = docRef.get().await()
            val current = (snapshot.get("bairros") as? List<*>)?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
            if (!current.contains(normalizedName)) {
                current.add(normalizedName)
                docRef.set(mapOf("bairros" to current.sorted()), com.google.firebase.firestore.SetOptions.merge()).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteBairro(name: String): Result<Unit> {
        return try {
            val docRef = firestore.collection("metadata").document("locations")
            val snapshot = docRef.get().await()
            val current = (snapshot.get("bairros") as? List<*>)?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
            if (current.remove(name)) {
                docRef.set(mapOf("bairros" to current.sorted()), com.google.firebase.firestore.SetOptions.merge()).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchSystemSettings(): Result<Map<String, Any>> {
        return try {
            val snapshot = withTimeoutOrNull(2500) {
                firestore.collection("metadata").document("settings").get().await()
            }
            if (snapshot == null || !snapshot.exists()) {
                return Result.success(emptyMap())
            }
            Result.success(snapshot.data ?: emptyMap())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateSystemSetting(key: String, value: Any): Result<Unit> {
        return try {
            val docRef = firestore.collection("metadata").document("settings")
            docRef.set(mapOf(key to value), com.google.firebase.firestore.SetOptions.merge()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
