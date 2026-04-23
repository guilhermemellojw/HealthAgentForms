package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.domain.repository.BackupMetadata
import com.antigravity.healthagent.domain.repository.BackupRepository
import javax.inject.Inject

class GetTimelineUseCase @Inject constructor(
    private val backupRepository: BackupRepository
) {
    suspend operator fun invoke(uid: String): Result<List<BackupMetadata>> {
        return backupRepository.fetchTimeline(uid)
    }
}
