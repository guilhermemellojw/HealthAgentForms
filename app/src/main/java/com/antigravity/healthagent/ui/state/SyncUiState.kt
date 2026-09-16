package com.antigravity.healthagent.ui.state

sealed interface SyncUiState {
    val lastSyncTime: Long?
    val clockSkewMs: Long

    data class Idle(
        override val lastSyncTime: Long? = null,
        override val clockSkewMs: Long = 0L
    ) : SyncUiState
    data class Syncing(
        val progress: Float, 
        val message: String? = null, 
        val isDownloading: Boolean = false, 
        override val lastSyncTime: Long? = null,
        override val clockSkewMs: Long = 0L
    ) : SyncUiState
    
    data class Success(
        override val lastSyncTime: Long, 
        override val clockSkewMs: Long = 0L
    ) : SyncUiState
    
    data class Error(
        val message: String, 
        override val lastSyncTime: Long? = null,
        override val clockSkewMs: Long = 0L
    ) : SyncUiState
}
