package com.superagent.body

import com.superagent.body.core.ui.UiStateController.UiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class FloatingUiPolicyTest {

    @Test
    fun `BLOCKED arrival opens or replaces with dedicated panel`() {
        assertEquals(
            FloatingUiPolicy.ArrivalAction.OPEN_BLOCKED_PANEL,
            FloatingUiPolicy.onStateArrival(UiState.BLOCKED, panelOpen = false),
        )
        assertEquals(
            FloatingUiPolicy.ArrivalAction.REPLACE_WITH_BLOCKED_PANEL,
            FloatingUiPolicy.onStateArrival(UiState.BLOCKED, panelOpen = true),
        )
    }

    @Test
    fun `BLOCKED ball click toggles dedicated panel without control command`() {
        assertEquals(
            FloatingUiPolicy.BallAction.OPEN_BLOCKED_PANEL,
            FloatingUiPolicy.onBallClick(UiState.BLOCKED, panelOpen = false),
        )
        assertEquals(
            FloatingUiPolicy.BallAction.CLOSE_PANEL,
            FloatingUiPolicy.onBallClick(UiState.BLOCKED, panelOpen = true),
        )
        assertFalse(
            setOf(
                FloatingUiPolicy.onBallClick(UiState.BLOCKED, panelOpen = false),
                FloatingUiPolicy.onBallClick(UiState.BLOCKED, panelOpen = true),
            ).contains(FloatingUiPolicy.BallAction.REQUEST_PAUSE),
        )
    }

    @Test
    fun `BLOCKED panel exposes trusted step guidance and close only`() {
        val model = FloatingUiPolicy.blockedPanel("执行结果未知 · 请核对设备状态")

        assertEquals("执行结果未知 · 请核对设备状态", model.currentStep)
        assertEquals("请先核对设备实际状态，再决定后续操作", model.guidance)
        assertEquals(listOf(FloatingUiPolicy.BlockedPanelAction.CLOSE), model.actions)
    }

    @Test
    fun `existing PAUSED and STOPPED arrival behavior stays automatic`() {
        assertEquals(
            FloatingUiPolicy.ArrivalAction.OPEN_CONTROL_PANEL,
            FloatingUiPolicy.onStateArrival(UiState.PAUSED, panelOpen = false),
        )
        assertEquals(
            FloatingUiPolicy.ArrivalAction.OPEN_CONTROL_PANEL,
            FloatingUiPolicy.onStateArrival(UiState.STOPPED, panelOpen = false),
        )
    }
}
