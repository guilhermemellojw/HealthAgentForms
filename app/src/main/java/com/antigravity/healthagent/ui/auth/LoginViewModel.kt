package com.antigravity.healthagent.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.AuthUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncRepository: com.antigravity.healthagent.domain.repository.SyncRepository
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.currentUserAsync.collect { user ->
                if (user == null) {
                    _authState.value = AuthState.Unauthenticated
                } else if (!user.isAuthorized) {
                    _authState.value = AuthState.WaitingForAuthorization(user)
                } else {
                    // Authorized User: Trigger automatic data pull for multi-device sync
                    // We check if we already have data locally or just pull anyway (upsert)
                    _authState.value = AuthState.Authenticated(user)
                    
                    // Trigger sync in the background
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        syncRepository.pullCloudDataToLocal()
                    }
                }
            }
        }
    }

    fun signInWithGoogle(idToken: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            val result = authRepository.signInWithGoogle(idToken)
            if (result.isFailure) {
                _authState.value = AuthState.Error(result.exceptionOrNull()?.message ?: "Login failed")
            }
        }
    }

    fun setError(message: String) {
        _authState.value = AuthState.Error(message)
    }

    fun resetError() {
        if (_authState.value is AuthState.Error) {
            _authState.value = AuthState.Unauthenticated
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
        }
    }

    private val _requestSent = MutableStateFlow(false)
    val requestSent: StateFlow<Boolean> = _requestSent.asStateFlow()

    fun requestAccess() {
        val user = (authState.value as? AuthState.WaitingForAuthorization)?.user ?: return
        viewModelScope.launch {
            val result = authRepository.requestAccess(user.uid, user.email ?: "", user.displayName)
            if (result.isSuccess) {
                _requestSent.value = true
            } else {
                setError("Erro ao solicitar acesso: ${result.exceptionOrNull()?.message}")
            }
        }
    }
}

sealed class AuthState {
    object Loading : AuthState()
    object Unauthenticated : AuthState()
    data class WaitingForAuthorization(val user: AuthUser) : AuthState()
    data class Authenticated(val user: AuthUser) : AuthState()
    data class Error(val message: String) : AuthState()
}
