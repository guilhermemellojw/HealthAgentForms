package com.antigravity.healthagent.data.sync

import com.antigravity.healthagent.domain.repository.SyncRepository
import kotlinx.coroutines.sync.Mutex

/**
 * Pull nuvem -> local — backend-neutro. Firebase e Supabase implementam;
 * Hilt seleciona via BuildConfig.USE_SUPABASE_SYNC.
 */
interface PullHandler {
    suspend fun pullCloudDataToLocal(
        targetUid: String?,
        force: Boolean,
        syncMutex: Mutex
    ): Result<SyncRepository.SyncResult>
}
