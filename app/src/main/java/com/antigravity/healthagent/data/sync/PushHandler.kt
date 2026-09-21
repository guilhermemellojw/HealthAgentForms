package com.antigravity.healthagent.data.sync

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import kotlinx.coroutines.sync.Mutex

/**
 * Push local -> nuvem — backend-neutro. Firebase e Supabase implementam;
 * Hilt seleciona via BuildConfig.USE_SUPABASE_SYNC.
 */
interface PushHandler {
    suspend fun pushLocalDataToCloud(
        houses: List<House>,
        activities: List<DayActivity>,
        targetUid: String?,
        shouldReplace: Boolean,
        isFullWipe: Boolean = false,
        syncMutex: Mutex
    ): Result<Unit>
}
