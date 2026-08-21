package com.superagent.body

import com.superagent.body.core.ui.UiStateController.UiState

internal object FloatingUiPolicy {
    enum class ArrivalAction { NONE, OPEN_CONTROL_PANEL, OPEN_BLOCKED_PANEL, REPLACE_WITH_BLOCKED_PANEL }
    enum class BallAction { OPEN_IDLE_PANEL, OPEN_RESULT_PANEL, OPEN_BLOCKED_PANEL, CLOSE_PANEL, REQUEST_PAUSE }
    enum class BlockedPanelAction { CLOSE }

    data class BlockedPanelModel(
        val currentStep: String,
        val guidance: String,
        val actions: List<BlockedPanelAction>,
    )

    fun onStateArrival(state: UiState, panelOpen: Boolean): ArrivalAction = when {
        state == UiState.BLOCKED && panelOpen -> ArrivalAction.REPLACE_WITH_BLOCKED_PANEL
        state == UiState.BLOCKED -> ArrivalAction.OPEN_BLOCKED_PANEL
        panelOpen -> ArrivalAction.NONE
        state in setOf(UiState.PAUSED, UiState.STOPPED) -> ArrivalAction.OPEN_CONTROL_PANEL
        else -> ArrivalAction.NONE
    }

    fun onBallClick(state: UiState, panelOpen: Boolean): BallAction = when {
        panelOpen -> BallAction.CLOSE_PANEL
        state in setOf(UiState.IDLE, UiState.OFFLINE, UiState.MINI) -> BallAction.OPEN_IDLE_PANEL
        state in setOf(UiState.COMPLETED, UiState.FAILED, UiState.STOPPED) -> BallAction.OPEN_RESULT_PANEL
        state == UiState.BLOCKED -> BallAction.OPEN_BLOCKED_PANEL
        else -> BallAction.REQUEST_PAUSE
    }

    fun blockedPanel(currentStep: String) = BlockedPanelModel(
        currentStep = currentStep,
        guidance = "请先核对设备实际状态，再决定后续操作",
        actions = listOf(BlockedPanelAction.CLOSE),
    )
}
