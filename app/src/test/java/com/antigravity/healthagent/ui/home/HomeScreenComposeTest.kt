package com.antigravity.healthagent.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, instrumentedPackages = ["androidx.loader.content"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeScreenShowsAgentName() {
        val viewModel = mockk<HomeViewModel>(relaxed = true)
        val uiState = MutableStateFlow(
            HomeUiState(
                agentName = "Test Agent",
                data = "12-06-2026"
            )
        )

        every { viewModel.uiState } returns uiState
        every { viewModel.uiEvent } returns MutableStateFlow<String?>(null)
        every { viewModel.streetSuggestions } returns MutableStateFlow(emptyList())
        every { viewModel.daysWithErrors } returns MutableStateFlow(emptyList())
        every { viewModel.showMultiDayErrorDialog } returns MutableStateFlow(false)
        every { viewModel.integrityDialogMessage } returns MutableStateFlow(null)
        every { viewModel.validationErrorDetails } returns MutableStateFlow(emptyList())
        every { viewModel.scrollToHouseId } returns MutableStateFlow(null)
        every { viewModel.showHistoryUnlockConfirmation } returns MutableStateFlow(false)
        every { viewModel.showGoalReached } returns MutableStateFlow(false)
        every { viewModel.showClosingAudit } returns MutableStateFlow(null)
        every { viewModel.situationLimitConfirmation } returns MutableStateFlow(null)
        every { viewModel.moveConfirmationData } returns MutableStateFlow(null)
        every { viewModel.duplicateHouseConfirmation } returns MutableStateFlow(null)
        every { viewModel.isSyncing } returns MutableStateFlow(false)
        every { viewModel.easyMode } returns MutableStateFlow(false)
        every { viewModel.solarMode } returns MutableStateFlow(false)
        every { viewModel.editingToolsMode } returns MutableStateFlow(false)
        every { viewModel.maxOpenHouses } returns MutableStateFlow(5)
        every { viewModel.reorderHouses } returns MutableStateFlow(emptyList())
        every { viewModel.treatmentDialogState } returns MutableStateFlow(null)
        every { viewModel.contextDialogState } returns MutableStateFlow(null)

        composeTestRule.setContent {
            HomeScreen(
                viewModel = viewModel,
                user = null,
                onLogout = {},
                onSwitchAccount = {},
                onOpenSettings = {},
                onSyncPullActive = {}
            )
        }

        composeTestRule.onNodeWithText("Produção Diária").assertIsDisplayed()
        composeTestRule.onNodeWithText("12-06-2026").assertIsDisplayed()
    }
}
