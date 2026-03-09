package com.antigravity.healthagent.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val syncRepository: SyncRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AdminUiState>(AdminUiState.Loading)
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    init {
        loadAgentsData()
    }

    fun loadAgentsData() {
        _uiState.value = AdminUiState.Loading
        viewModelScope.launch {
            val result = syncRepository.fetchAllAgentsData()
            if (result.isSuccess) {
                _uiState.value = AdminUiState.Success(result.getOrNull() ?: emptyList())
            } else {
                _uiState.value = AdminUiState.Error(result.exceptionOrNull()?.message ?: "Erro desconhecido")
            }
        }
    }
}

sealed class AdminUiState {
    object Loading : AdminUiState()
    data class Success(val agents: List<AgentData>) : AdminUiState()
    data class Error(val message: String) : AdminUiState()
}
