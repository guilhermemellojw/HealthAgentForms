package com.antigravity.healthagent.domain.repository

import kotlinx.coroutines.flow.Flow

interface StreetRepository {
    fun getStreetSuggestions(bairro: String, agentName: String, agentUid: String): Flow<List<String>>
    suspend fun saveCustomStreet(name: String, bairro: String)
}
