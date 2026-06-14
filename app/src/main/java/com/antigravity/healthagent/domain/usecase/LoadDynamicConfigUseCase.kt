package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.domain.repository.LocalizationRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.utils.AppConstants
import javax.inject.Inject

class LoadDynamicConfigUseCase @Inject constructor(
    private val localizationRepository: LocalizationRepository,
    private val syncRepository: SyncRepository,
    private val settingsManager: SettingsManager
) {
    suspend operator fun invoke(): List<String> {
        var bairros = AppConstants.BAIRROS
        kotlinx.coroutines.withTimeoutOrNull(5000) {
            val bairrosResult = localizationRepository.fetchBairros()
            if (bairrosResult.isSuccess) {
                bairros = bairrosResult.getOrNull() ?: AppConstants.BAIRROS
            }

            val settingsResult = syncRepository.fetchSystemSettings()
            if (settingsResult.isSuccess) {
                val settings = settingsResult.getOrNull() ?: emptyMap()
                settings["max_open_houses"]?.let { raw ->
                    val intVal = when (raw) {
                        is Long -> raw.toInt()
                        is Int -> raw
                        is Number -> raw.toInt()
                        is String -> raw.toIntOrNull() ?: 25
                        else -> 25
                    }
                    settingsManager.setMaxOpenHouses(intVal)
                }
                settings["default_easy_mode"]?.let { raw ->
                    val boolVal = raw as? Boolean ?: false
                    settingsManager.setEasyMode(boolVal)
                }
                settings["custom_activities"]?.let { raw ->
                    val setVal = when (raw) {
                        is List<*> -> raw.mapNotNull { it?.toString() }.toSet()
                        is String -> raw.split(",").filter { it.isNotBlank() }.toSet()
                        else -> emptySet()
                    }
                    settingsManager.setCustomActivities(setVal)
                }
            }
        }
        return bairros
    }
}
