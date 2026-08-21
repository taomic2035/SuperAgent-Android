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

    private fun emitVoice(kind: String) {
        events.emit("voice", buildJsonObject { put("kind", kind) })
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
    fun `finish aborted 进入 STOPPED`() {
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
        controller.setIdle()
        events.emit("voice", buildJsonObject { put("kind", "text_input"); put("text", "打开设置") })

        assertEquals(UiStateController.UiState.IDLE, controller.state)
        assertEquals("已收到 · 正在理解", controller.snapshot().currentStep)
    }

    @Test
    fun `OFFLINE text input is rejected locally without false acknowledgement`() {
        events.emit("voice", buildJsonObject { put("kind", "text_input"); put("text", "打开设置") })

        assertEquals(UiStateController.UiState.OFFLINE, controller.state)
        assertEquals("未发送·大脑离线", controller.snapshot().currentStep)
        assertTrue(controller.notificationText().contains("未发送"))
    }

    @Test
    fun `OFFLINE submitTextInput publishes no voice event`() {
        var voiceEvents = 0
        events.addListener { type, _ -> if (type == "voice") voiceEvents++ }

        val accepted = controller.submitTextInput("打开设置")

        assertFalse(accepted)
        assertEquals(0, voiceEvents)
        assertEquals(UiStateController.UiState.OFFLINE, controller.state)
    }

    @Test
    fun `online submitTextInput publishes exactly one command`() {
        controller.setIdle()
        var voiceEvents = 0
        events.addListener { type, _ -> if (type == "voice") voiceEvents++ }

        val accepted = controller.submitTextInput("打开设置")

        assertTrue(accepted)
        assertEquals(1, voiceEvents)
        assertEquals("已收到 · 正在理解", controller.snapshot().currentStep)
    }

    @Test
    fun `raw resume event does not claim UI state without local acceptance`() {
        emitBrain("task-1", 1, "prompt_start", "开始")
        emitBrain("task-1", 2, "finish", "已暂停", resultKind = "paused")
        assertEquals(UiStateController.UiState.PAUSED, controller.state)

        emitVoice("resume_request")

        assertEquals(UiStateController.UiState.PAUSED, controller.state)
        assertEquals("已暂停", controller.snapshot().currentStep)
    }

    @Test
    fun `PAUSED requestResume publishes once and immediately shows restoring`() {
        emitBrain("task-1", 1, "prompt_start", "开始")
        emitBrain("task-1", 2, "finish", "已暂停", resultKind = "paused")
        var voiceEvents = 0
        events.addListener { type, _ -> if (type == "voice") voiceEvents++ }

        val accepted = controller.requestResume()

        assertTrue(accepted)
        assertEquals(1, voiceEvents)
        assertEquals(UiStateController.UiState.THINKING, controller.state)
        assertEquals("正在恢复", controller.snapshot().currentStep)
    }

    @Test
    fun `requestResume during PAUSING publishes zero`() {
        emitBrain("task-1", 1, "prompt_start", "开始")
        emitBrain("task-1", 2, "act", "执行中")
        emitVoice("pause_request")
        assertEquals(UiStateController.UiState.PAUSING, controller.state)

        var voiceEvents = 0
        events.addListener { type, _ -> if (type == "voice") voiceEvents++ }

        val accepted = controller.requestResume()

        assertFalse(accepted)
        assertEquals(0, voiceEvents)
        assertEquals(UiStateController.UiState.PAUSING, controller.state)
        assertEquals("暂停中·当前动作完成后停", controller.snapshot().currentStep)
    }

    @Test
    fun `repeated requestResume publishes one total`() {
        emitBrain("task-1", 1, "prompt_start", "开始")
        emitBrain("task-1", 2, "finish", "已暂停", resultKind = "paused")
        var voiceEvents = 0
        events.addListener { type, _ -> if (type == "voice") voiceEvents++ }

        val first = controller.requestResume()
        val second = controller.requestResume()

        assertTrue(first)
        assertFalse(second)
        assertEquals(1, voiceEvents)
        assertEquals(UiStateController.UiState.THINKING, controller.state)
    }

    @Test
    fun `finish stopped alias enters STOPPED`() {
        emitBrain("task-1", 1, "prompt_start", "开始")

        emitBrain("task-1", 2, "finish", "用户已停止", resultKind = "stopped")

        assertEquals(UiStateController.UiState.STOPPED, controller.state)
    }

    @Test
    fun `pause request settles RUNNING through PAUSING to PAUSED`() {
        emitBrain("task-1", 1, "prompt_start", "开始")
        emitBrain("task-1", 2, "act", "执行中")

        emitVoice("pause_request")
        assertEquals(UiStateController.UiState.PAUSING, controller.state)

        emitBrain("task-1", 3, "finish", "已暂停", resultKind = "paused")
        assertEquals(UiStateController.UiState.PAUSED, controller.state)
    }

    @Test
    fun `stop request settles RUNNING through STOPPING to STOPPED`() {
        emitBrain("task-1", 1, "prompt_start", "开始")
        emitBrain("task-1", 2, "act", "执行中")

        emitVoice("stop_request")
        assertEquals(UiStateController.UiState.STOPPING, controller.state)

        emitBrain("task-1", 3, "finish", "已停止", resultKind = "aborted")
        assertEquals(UiStateController.UiState.STOPPED, controller.state)
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
