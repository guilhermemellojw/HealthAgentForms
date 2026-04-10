package com.antigravity.healthagent.domain.repository

import com.antigravity.healthagent.domain.repository.AgentData

interface LocalizationRepository {
    suspend fun fetchBairros(): Result<List<String>>
    suspend fun addBairro(name: String): Result<Unit>
    suspend fun deleteBairro(name: String): Result<Unit>
    
    // System Metadata / Settings
    suspend fun fetchSystemSettings(): Result<Map<String, Any>>
    suspend fun updateSystemSetting(key: String, value: Any): Result<Unit>
}
