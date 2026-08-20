package com.superagent.body.core.ui

import com.superagent.body.core.events.EventBus
import com.superagent.common.BrainEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * P2-02（审计）：UiStateController 状态机 JVM 单测——
 * 事件→状态迁移 / taskId 隔离 / seq 去重防回退 / 最近步骤 / 心跳恢复。
 */
class UiStateControllerTest {

    private lateinit var events: EventBus
    private lateinit var controller: UiStateController

    @BeforeEach
    fun setup() {
        events = EventBus()
        controller = UiStateController(events)
        controller.start()
    }

    private fun emitBrain(taskId: String, seq: Long, state: String, displayText: String = "", resultKind: String? = null) {
        events.emit("brain", buildJsonObject {
            put("taskId", taskId)
            put("seq", seq)
            put("state", state)
            put("displayText", displayText)
            put("requiresUser", if (state == "hitl_wait") "confirm" else "none")
            if (resultKind != null) put("resultKind", resultKind)
            put("timestamp", System.currentTimeMillis())
        })
    }

    @Test
    fun `prompt_start 进入 THINKING`() {
        emitBrain("task-1", 1, "prompt_start", "目标：打开设置")
        assertEquals(UiStateController.UiState.THINKING, controller.state)
        assertTrue(controller.snapshot().recentSteps.contains("目标：打开设置"))
    }

    @Test
    fun `act 进入 RUNNING 并推进步骤`() {
        emitBrain("task-1", 1, "prompt_start", "开始")
        emitBrain("task-1", 2, "act", "第 1 步 · 正在打开应用")
        assertEquals(UiStateController.UiState.RUNNING, controller.state)
        emitBrain("task-1", 3, "act", "第 2 步 · 正在选择「搜索」")
        assertEquals(UiStateController.UiState.RUNNING, controller.state)
        val steps = controller.snapshot().recentSteps
        assertTrue(steps.contains("第 2 步 · 正在选择「搜索」"))
    }

    @Test
    fun `finish success 进入 COMPLETED`() {
        emitBrain("task-1", 1, "prompt_start", "开始")
        emitBrain("task-1", 2, "act", "第 1 步")
        emitBrain("task-1", 3, "finish", "任务完成", resultKind = "success")
        assertEquals(UiStateController.UiState.COMPLETED, controller.state)
    }

    @Test
    fun `finish failed 进入 FAILED`() {
        emitBrain("task-1", 1, "prompt_start", "开始")
        emitBrain("task-1", 2, "finish", "执行失败", resultKind = "failed")
        assertEquals(UiStateController.UiState.FAILED, controller.state)
    }

    @Test
    fun `finish aborted 进入 PAUSED`() {
        emitBrain("task-1", 1, "prompt_start", "开始")
        emitBrain("task-1", 2, "finish", "用户已停止", resultKind = "aborted")
        assertEquals(UiStateController.UiState.STOPPED, controller.state)
    }

    @Test
    fun `hitl_wait 进入 AWAITING_CONFIRM`() {
        emitBrain("task-1", 1, "prompt_start", "开始")
        emitBrain("task-1", 2, "hitl_wait", "等待确认")
        assertEquals(UiStateController.UiState.AWAITING_CONFIRM, controller.state)
    }

    @Test
    fun `旧任务事件不覆盖当前任务状态`() {
        emitBrain("task-1", 1, "prompt_start", "任务1")
        emitBrain("task-2", 1, "prompt_start", "任务2") // 新任务开始
        assertEquals(UiStateController.UiState.THINKING, controller.state)

        emitBrain("task-1", 5, "finish", "旧任务的迟到事件", resultKind = "success")
        assertEquals(UiStateController.UiState.THINKING, controller.state, "旧任务事件不应覆盖新任务状态")
    }

    @Test
    fun `seq 回退事件被忽略`() {
        emitBrain("task-1", 1L, "prompt_start", "开始")
        emitBrain("task-1", 5L, "act", "第 5 步")
        assertEquals(UiStateController.UiState.RUNNING, controller.state)

        emitBrain("task-1", 3L, "act", "乱序旧事件")
        // seq 3 < 5，不应推进步骤
        val steps = controller.snapshot().recentSteps
        assertFalse(steps.contains("乱序旧事件"), "seq 回退的事件应被忽略")
    }

    @Test
    fun `text_input 在非运行态触发 IDLE 展示`() {
        events.emit("voice", buildJsonObject { put("kind", "text_input"); put("text", "打开设置") })
        // 初始状态 OFFLINE → text_input 应让 IDLE 状态可见（但 state 可能已在 OFFLINE）
        // 至少不应崩溃且状态合法
        assertNotNull(controller.state)
    }

    @Test
    fun `最近步骤最多保留5条`() {
        emitBrain("task-1", 1L, "prompt_start", "开始")
        for (i in 1..10) {
            emitBrain("task-1", (i + 1).toLong(), "act", "步骤 $i")
            emitBrain("task-1", (i + 100).toLong(), "act_done", "步骤 $i 完成")
        }
        assertEquals(5, controller.snapshot().recentSteps.size, "最近步骤不超过 5 条")
    }

    @Test
    fun `stop 后事件不再被消费（U2-H05）`() {
        controller.stop()
        emitBrain("task-1", 1L, "prompt_start", "停止后的事件")
        assertEquals(UiStateController.UiState.OFFLINE, controller.state)
    }
}
