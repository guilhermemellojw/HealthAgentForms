package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.ui.home.delegates.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeDelegatesProvider @Inject constructor(
    val syncDelegate: SyncDelegate,
    val dayNavigationDelegate: DayNavigationDelegate,
    val dayClosingDelegate: DayClosingDelegate,
    val validationDelegate: ValidationDelegate,
    val remoteAgentDelegate: RemoteAgentDelegate,
    val boletimDataDelegate: BoletimDataDelegate,
    val initializationDelegate: InitializationDelegate,
    val houseEditDelegate: HouseEditDelegate,
    val stateDelegate: HomeStateDelegate
)
