package com.superagent.body.core.ui

import com.superagent.body.core.events.EventBus
import com.superagent.common.BrainEvent
import com.superagent.common.BrainEventRequiresUser
import com.superagent.common.BrainEventResultKind
import com.superagent.common.BrainEventState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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

    private fun emitBrain(
        taskId: String,
        seq: Long,
        state: BrainEventState,
        displayText: String = "",
        resultKind: BrainEventResultKind? = null,
    ) {
        emitBrainTo(events, taskId, seq, state, displayText, resultKind)
    }

    private fun emitBrainTo(
        targetEvents: EventBus,
        taskId: String,
        seq: Long,
        state: BrainEventState,
        displayText: String = "",
        resultKind: BrainEventResultKind? = null,
    ) {
        targetEvents.emit(
            "brain",
            Json.encodeToJsonElement(
                BrainEvent.serializer(),
                BrainEvent(
                    taskId = taskId,
                    seq = seq,
                    state = state,
                    displayText = displayText,
                    requiresUser = if (state == BrainEventState.HITL_WAIT) {
                        BrainEventRequiresUser.CONFIRM
                    } else {
                        BrainEventRequiresUser.NONE
                    },
                    resultKind = resultKind,
                    timestamp = System.currentTimeMillis(),
                ),
            ),
        )
    }

    private data class Harness(val events: EventBus, val controller: UiStateController)

    private fun harnessAt(target: UiStateController.UiState): Harness {
        val targetEvents = EventBus()
        val targetController = UiStateController(targetEvents).also { it.start() }
        fun brain(seq: Long, state: BrainEventState, result: BrainEventResultKind? = null) =
            emitBrainTo(targetEvents, "task-1", seq, state, state.wireValue, result)
        fun voice(kind: String) = targetEvents.emit("voice", buildJsonObject { put("kind", kind) })

        brain(1, BrainEventState.PROMPT_START)
        when (target) {
            UiStateController.UiState.STOPPING -> { brain(2, BrainEventState.ACT); voice("stop_request") }
            UiStateController.UiState.PAUSED -> brain(2, BrainEventState.FINISH, BrainEventResultKind.PAUSED)
            UiStateController.UiState.AWAITING_CONFIRM -> brain(2, BrainEventState.HITL_WAIT)
            UiStateController.UiState.BLOCKED -> brain(2, BrainEventState.BLOCKED)
            UiStateController.UiState.STOPPED -> brain(2, BrainEventState.FINISH, BrainEventResultKind.STOPPED)
            UiStateController.UiState.COMPLETED -> brain(2, BrainEventState.FINISH, BrainEventResultKind.SUCCESS)
            UiStateController.UiState.FAILED -> brain(2, BrainEventState.FINISH, BrainEventResultKind.FAILED)
            else -> error("unsupported test target: $target")
        }
        assertEquals(target, targetController.state)
        return Harness(targetEvents, targetController)
    }

    private fun assertIngressWaitsForControllerMonitor(action: () -> Unit) {
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        lateinit var worker: Thread

        synchronized(controller) {
            worker = thread(name = "ui-ingress-sync-test") {
                started.countDown()
                action()
                completed.countDown()
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertFalse(completed.await(200, TimeUnit.MILLISECONDS), "ingress must wait for the controller monitor")
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
    }

    private fun emitVoice(kind: String) {
        events.emit("voice", buildJsonObject { put("kind", kind) })
    }

    private fun emitSystemState(kind: String) {
        events.emit("state", buildJsonObject { put("kind", kind) })
    }

    private fun assertRawTextInputPreservesSnapshot(prepare: () -> Unit) {
        prepare()
        val before = controller.snapshot()

        events.emit("voice", buildJsonObject {
            put("kind", "text_input")
            put("text", "打开设置")
        })

        assertEquals(before, controller.snapshot())
        assertFalse(controller.snapshot().currentStep.contains("已收到"))
    }

    @Test
    fun `prompt_start 进入 THINKING`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "目标：打开设置")
        assertEquals(UiStateController.UiState.THINKING, controller.state)
        assertTrue(controller.snapshot().recentSteps.contains("目标：打开设置"))
    }

    @Test
    fun `act 进入 RUNNING 并推进步骤`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.ACT, "第 1 步 · 正在打开应用")
        assertEquals(UiStateController.UiState.RUNNING, controller.state)
        emitBrain("task-1", 3, BrainEventState.ACT, "第 2 步 · 正在选择「搜索」")
        assertEquals(UiStateController.UiState.RUNNING, controller.state)
        val steps = controller.snapshot().recentSteps
        assertTrue(steps.contains("第 2 步 · 正在选择「搜索」"))
    }

    @Test
    fun `finish success 进入 COMPLETED`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.ACT, "第 1 步")
        emitBrain("task-1", 3, BrainEventState.FINISH, "任务完成", resultKind = BrainEventResultKind.SUCCESS)
        assertEquals(UiStateController.UiState.COMPLETED, controller.state)
    }

    @Test
    fun `finish failed 进入 FAILED`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.FINISH, "执行失败", resultKind = BrainEventResultKind.FAILED)
        assertEquals(UiStateController.UiState.FAILED, controller.state)
    }

    @Test
    fun `finish aborted 进入 STOPPED`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.FINISH, "用户已停止", resultKind = BrainEventResultKind.ABORTED)
        assertEquals(UiStateController.UiState.STOPPED, controller.state)
    }

    @Test
    fun `finish unknown_side_effect enters unresolved BLOCKED with trusted copy`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")

        emitBrain(
            "task-1",
            2,
            BrainEventState.FINISH,
            "任务完成，可以放心离开",
            resultKind = BrainEventResultKind.UNKNOWN_SIDE_EFFECT,
        )

        val snapshot = controller.snapshot()
        assertEquals(UiStateController.UiState.BLOCKED, snapshot.state)
        assertEquals("执行结果未知 · 请核对设备状态", snapshot.currentStep)
        assertFalse(snapshot.unreadResult, "未知副作用不是已解决的未读结果")
        assertFalse(snapshot.recentSteps.contains("任务完成，可以放心离开"))
    }

    @Test
    fun `a11y recovery cannot clear unknown_side_effect BLOCKED`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.FINISH, "不可信结果", resultKind = BrainEventResultKind.UNKNOWN_SIDE_EFFECT)
        val blocked = controller.snapshot()

        emitSystemState("a11y_recovered")

        assertEquals(blocked, controller.snapshot())
    }

    @Test
    fun `a11y recovery cannot clear brain BLOCKED`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.BLOCKED, "需要用户处理")
        val blocked = controller.snapshot()

        emitSystemState("a11y_recovered")

        assertEquals(blocked, controller.snapshot())
    }

    @Test
    fun `a11y persistent failure BLOCKED recovers to IDLE`() {
        controller.setIdle()
        emitSystemState("a11y_persistent_failure")
        assertEquals(UiStateController.UiState.BLOCKED, controller.state)

        emitSystemState("a11y_recovered")

        assertEquals(UiStateController.UiState.IDLE, controller.state)
    }

    @Test
    fun `a11y failure cannot overwrite unresolved side effect copy`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.FINISH, "不可信结果", resultKind = BrainEventResultKind.UNKNOWN_SIDE_EFFECT)
        val blocked = controller.snapshot()

        emitSystemState("a11y_persistent_failure")
        emitSystemState("a11y_recovered")

        assertEquals(blocked, controller.snapshot())
    }

    @Test
    fun `unknown side effect BLOCKED survives connectivity timeout and heartbeat`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain(
            "task-1",
            2,
            BrainEventState.FINISH,
            "不可信结果",
            resultKind = BrainEventResultKind.UNKNOWN_SIDE_EFFECT,
        )
        val protected = controller.snapshot()

        controller.setOffline()
        assertEquals(UiStateController.UiState.OFFLINE, controller.state)
        emitBrain("heartbeat", 0, BrainEventState.HEARTBEAT, "不可信的新展示")

        assertEquals(protected, controller.snapshot())
    }

    @Test
    fun `brain BLOCKED survives connectivity timeout and heartbeat`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.BLOCKED, "请在设备上完成安全核对")
        val protected = controller.snapshot()

        controller.setOffline()
        assertEquals(UiStateController.UiState.OFFLINE, controller.state)
        emitBrain("heartbeat", 0, BrainEventState.HEARTBEAT, "不可信的新展示")

        assertEquals(protected, controller.snapshot())
    }

    @Test
    fun `a11y BLOCKED survives connectivity timeout and heartbeat until a11y recovers`() {
        controller.setIdle()
        emitSystemState("a11y_persistent_failure")
        val protected = controller.snapshot()

        controller.setOffline()
        assertEquals(UiStateController.UiState.OFFLINE, controller.state)
        emitBrain("heartbeat", 0, BrainEventState.HEARTBEAT)

        assertEquals(protected, controller.snapshot())

        emitSystemState("a11y_recovered")
        assertEquals(UiStateController.UiState.IDLE, controller.state)
    }

    @Test
    fun `ordinary IDLE connectivity timeout still recovers to IDLE on heartbeat`() {
        controller.setIdle()

        controller.setOffline()
        assertEquals(UiStateController.UiState.OFFLINE, controller.state)
        emitBrain("heartbeat", 0, BrainEventState.HEARTBEAT)

        assertEquals(UiStateController.UiState.IDLE, controller.state)
    }

    @Test
    fun `PAUSED survives connectivity timeout and heartbeat with its complete snapshot`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.FINISH, "已暂停", resultKind = BrainEventResultKind.PAUSED)
        val protected = controller.snapshot()

        controller.setOffline()
        emitBrain("heartbeat", 0, BrainEventState.HEARTBEAT)

        assertEquals(protected, controller.snapshot())
    }

    @Test
    fun `AWAITING_CONFIRM survives connectivity timeout and heartbeat with its complete snapshot`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.HITL_WAIT, "等待确认")
        val protected = controller.snapshot()

        controller.setOffline()
        emitBrain("heartbeat", 0, BrainEventState.HEARTBEAT)

        assertEquals(protected, controller.snapshot())
    }

    @Test
    fun `offline display cannot let prompt_start bypass a protected effective state`() {
        for (target in listOf(
            UiStateController.UiState.PAUSED,
            UiStateController.UiState.AWAITING_CONFIRM,
            UiStateController.UiState.BLOCKED,
        )) {
            val harness = harnessAt(target)
            val protected = harness.controller.snapshot()
            harness.controller.setOffline()
            val offline = harness.controller.snapshot()

            emitBrainTo(harness.events, "task-2", 1, BrainEventState.PROMPT_START, "不得绕过")

            assertEquals(offline, harness.controller.snapshot(), "$target must remain protected behind OFFLINE")
            emitBrainTo(harness.events, "heartbeat", 0, BrainEventState.HEARTBEAT)
            assertEquals(protected, harness.controller.snapshot(), "$target must still restore after heartbeat")
            harness.controller.stop()
        }
    }

    @Test
    fun `offline paused settle clears pending before a later reconnect`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.FINISH, "已暂停", BrainEventResultKind.PAUSED)
        controller.setOffline()

        emitBrain("task-1", 3, BrainEventState.FINISH, "已停止", BrainEventResultKind.STOPPED)
        assertEquals(UiStateController.UiState.STOPPED, controller.state)
        controller.setOffline()
        emitBrain("heartbeat", 0, BrainEventState.HEARTBEAT)

        assertEquals(UiStateController.UiState.IDLE, controller.state)
        assertNotEquals("已暂停", controller.snapshot().currentStep)
    }

    @Test
    fun `offline awaiting-confirm error clears pending before a later reconnect`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.HITL_WAIT, "等待确认")
        controller.setOffline()

        emitBrain("task-1", 3, BrainEventState.ERROR, "确认失败")
        assertEquals(UiStateController.UiState.FAILED, controller.state)
        controller.setOffline()
        emitBrain("heartbeat", 0, BrainEventState.HEARTBEAT)

        assertEquals(UiStateController.UiState.IDLE, controller.state)
        assertNotEquals("等待确认", controller.snapshot().currentStep)
    }

    @Test
    fun `brain ingress shares the controller synchronization boundary`() {
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        lateinit var worker: Thread

        synchronized(controller) {
            worker = thread(name = "brain-ingress-sync-test") {
                started.countDown()
                emitBrain("foreign-task", 1, BrainEventState.ACT, "应被忽略")
                completed.countDown()
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertFalse(completed.await(200, TimeUnit.MILLISECONDS), "onBrain must wait for the controller monitor")
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
    }

    @Test
    fun `voice ingress shares the controller synchronization boundary`() {
        assertIngressWaitsForControllerMonitor { emitVoice("resume_request") }
    }

    @Test
    fun `hitl ingress shares the controller synchronization boundary`() {
        assertIngressWaitsForControllerMonitor {
            events.emit("hitl", buildJsonObject { put("kind", "wait") })
        }
    }

    @Test
    fun `protected and terminal states reject new open-state brain events`() {
        val protected = listOf(
            UiStateController.UiState.STOPPING,
            UiStateController.UiState.PAUSED,
            UiStateController.UiState.AWAITING_CONFIRM,
            UiStateController.UiState.BLOCKED,
            UiStateController.UiState.STOPPED,
            UiStateController.UiState.COMPLETED,
            UiStateController.UiState.FAILED,
        )
        val openers = listOf(BrainEventState.PROMPT_START, BrainEventState.HITL_WAIT, BrainEventState.BLOCKED)

        for (target in protected) {
            for (opener in openers) {
                val harness = harnessAt(target)
                val before = harness.controller.snapshot()
                if (opener == BrainEventState.PROMPT_START) {
                    emitBrainTo(harness.events, "task-2", 1, opener, "不得覆盖")
                } else {
                    emitBrainTo(harness.events, "task-1", 3, opener, "不得覆盖")
                }
                assertEquals(before, harness.controller.snapshot(), "$target must reject $opener")
                harness.controller.stop()
            }
        }
    }

    @Test
    fun `terminal states reject late act_done without appending steps`() {
        for (target in listOf(
            UiStateController.UiState.STOPPED,
            UiStateController.UiState.COMPLETED,
            UiStateController.UiState.FAILED,
        )) {
            val harness = harnessAt(target)
            val before = harness.controller.snapshot()

            emitBrainTo(harness.events, "task-1", 3, BrainEventState.ACT_DONE, "迟到步骤")

            assertEquals(before, harness.controller.snapshot(), "$target must reject late act_done")
            harness.controller.stop()
        }
    }

    @Test
    fun `blocked and terminal states reject finish and error overwrite`() {
        val protected = listOf(
            UiStateController.UiState.BLOCKED,
            UiStateController.UiState.STOPPED,
            UiStateController.UiState.COMPLETED,
            UiStateController.UiState.FAILED,
        )
        for (target in protected) {
            for (result in BrainEventResultKind.entries) {
                val harness = harnessAt(target)
                val before = harness.controller.snapshot()

                emitBrainTo(harness.events, "task-1", 3, BrainEventState.FINISH, "不得覆盖", result)

                assertEquals(before, harness.controller.snapshot(), "$target must reject finish/$result")
                harness.controller.stop()
            }

            val harness = harnessAt(target)
            val before = harness.controller.snapshot()
            emitBrainTo(harness.events, "task-1", 3, BrainEventState.ERROR, "不得覆盖")
            assertEquals(before, harness.controller.snapshot(), "$target must reject error")
            harness.controller.stop()
        }
    }

    @Test
    fun `paused checkpoint can still settle to stopped`() {
        val harness = harnessAt(UiStateController.UiState.PAUSED)

        emitBrainTo(
            harness.events,
            "task-1",
            3,
            BrainEventState.FINISH,
            "已停止",
            BrainEventResultKind.STOPPED,
        )

        assertEquals(UiStateController.UiState.STOPPED, harness.controller.state)
        harness.controller.stop()
    }

    @Test
    fun `hitl_wait 进入 AWAITING_CONFIRM`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.HITL_WAIT, "等待确认")
        assertEquals(UiStateController.UiState.AWAITING_CONFIRM, controller.state)
    }

    @Test
    fun `旧任务事件不覆盖当前任务状态`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "任务1")
        emitBrain("task-2", 1, BrainEventState.PROMPT_START, "任务2") // 新任务开始
        assertEquals(UiStateController.UiState.THINKING, controller.state)

        emitBrain("task-1", 5, BrainEventState.FINISH, "旧任务的迟到事件", resultKind = BrainEventResultKind.SUCCESS)
        assertEquals(UiStateController.UiState.THINKING, controller.state, "旧任务事件不应覆盖新任务状态")
    }

    @Test
    fun `seq 回退事件被忽略`() {
        emitBrain("task-1", 1L, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 5L, BrainEventState.ACT, "第 5 步")
        assertEquals(UiStateController.UiState.RUNNING, controller.state)

        emitBrain("task-1", 3L, BrainEventState.ACT, "乱序旧事件")
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

        assertEquals(UiStateController.TextInputResult.REJECTED_OFFLINE, accepted)
        assertEquals(0, voiceEvents)
        assertEquals(UiStateController.UiState.OFFLINE, controller.state)
    }

    @Test
    fun `online submitTextInput publishes exactly one command`() {
        controller.setIdle()
        var voiceEvents = 0
        events.addListener { type, _ -> if (type == "voice") voiceEvents++ }

        val accepted = controller.submitTextInput("打开设置")

        assertEquals(UiStateController.TextInputResult.ACCEPTED, accepted)
        assertEquals(1, voiceEvents)
        assertEquals("已收到 · 正在理解", controller.snapshot().currentStep)
    }

    @Test
    fun `submitTextInput cannot replace PAUSING with a new command`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.ACT, "执行中")
        emitVoice("pause_request")
        var voiceEvents = 0
        events.addListener { type, _ -> if (type == "voice") voiceEvents++ }

        val accepted = controller.submitTextInput("打开设置")

        assertEquals(UiStateController.TextInputResult.REJECTED_CONTROL_PENDING, accepted)
        assertEquals(0, voiceEvents)
        assertEquals(UiStateController.UiState.PAUSING, controller.state)
        assertEquals("暂停中·当前动作完成后停", controller.snapshot().currentStep)
    }

    @Test
    fun `submitTextInput cannot replace STOPPING with a new command`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.ACT, "执行中")
        emitVoice("stop_request")
        var voiceEvents = 0
        events.addListener { type, _ -> if (type == "voice") voiceEvents++ }

        val accepted = controller.submitTextInput("打开设置")

        assertEquals(UiStateController.TextInputResult.REJECTED_CONTROL_PENDING, accepted)
        assertEquals(0, voiceEvents)
        assertEquals(UiStateController.UiState.STOPPING, controller.state)
        assertEquals("停止中", controller.snapshot().currentStep)
    }

    @Test
    fun `submitTextInput cannot replace PAUSED checkpoint with a new command`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.FINISH, "已暂停", resultKind = BrainEventResultKind.PAUSED)
        var voiceEvents = 0
        events.addListener { type, _ -> if (type == "voice") voiceEvents++ }

        val accepted = controller.submitTextInput("打开设置")

        assertEquals(UiStateController.TextInputResult.REJECTED_PAUSED, accepted)
        assertEquals(0, voiceEvents)
        assertEquals(UiStateController.UiState.PAUSED, controller.state)
        assertEquals("已暂停", controller.snapshot().currentStep)
    }

    @Test
    fun `submitTextInput cannot hide AWAITING_CONFIRM`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.HITL_WAIT, "等待确认")
        var voiceEvents = 0
        events.addListener { type, _ -> if (type == "voice") voiceEvents++ }

        val result = controller.submitTextInput("打开设置")

        assertEquals(UiStateController.TextInputResult.REJECTED_WAITING_USER, result)
        assertEquals(0, voiceEvents)
        assertEquals(UiStateController.UiState.AWAITING_CONFIRM, controller.state)
        assertEquals("等待确认", controller.snapshot().currentStep)
    }

    @Test
    fun `submitTextInput cannot hide BLOCKED`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.BLOCKED, "需要处理")
        var voiceEvents = 0
        events.addListener { type, _ -> if (type == "voice") voiceEvents++ }

        val result = controller.submitTextInput("打开设置")

        assertEquals(UiStateController.TextInputResult.REJECTED_WAITING_USER, result)
        assertEquals(0, voiceEvents)
        assertEquals(UiStateController.UiState.BLOCKED, controller.state)
        assertEquals("需要处理", controller.snapshot().currentStep)
    }

    @Test
    fun `raw text_input preserves PAUSING snapshot`() {
        assertRawTextInputPreservesSnapshot {
            emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
            emitBrain("task-1", 2, BrainEventState.ACT, "执行中")
            emitVoice("pause_request")
        }
    }

    @Test
    fun `raw text_input preserves STOPPING snapshot`() {
        assertRawTextInputPreservesSnapshot {
            emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
            emitBrain("task-1", 2, BrainEventState.ACT, "执行中")
            emitVoice("stop_request")
        }
    }

    @Test
    fun `raw text_input preserves PAUSED snapshot`() {
        assertRawTextInputPreservesSnapshot {
            emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
            emitBrain("task-1", 2, BrainEventState.FINISH, "已暂停", resultKind = BrainEventResultKind.PAUSED)
        }
    }

    @Test
    fun `raw text_input preserves AWAITING_CONFIRM snapshot`() {
        assertRawTextInputPreservesSnapshot {
            emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
            emitBrain("task-1", 2, BrainEventState.HITL_WAIT, "等待确认")
        }
    }

    @Test
    fun `raw text_input preserves BLOCKED snapshot`() {
        assertRawTextInputPreservesSnapshot {
            emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
            emitBrain("task-1", 2, BrainEventState.BLOCKED, "需要处理")
        }
    }

    @Test
    fun `raw resume event does not claim UI state without local acceptance`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.FINISH, "已暂停", resultKind = BrainEventResultKind.PAUSED)
        assertEquals(UiStateController.UiState.PAUSED, controller.state)

        emitVoice("resume_request")

        assertEquals(UiStateController.UiState.PAUSED, controller.state)
        assertEquals("已暂停", controller.snapshot().currentStep)
    }

    @Test
    fun `PAUSED requestResume publishes once and immediately shows restoring`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.FINISH, "已暂停", resultKind = BrainEventResultKind.PAUSED)
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
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.ACT, "执行中")
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
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.FINISH, "已暂停", resultKind = BrainEventResultKind.PAUSED)
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
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")

        emitBrain("task-1", 2, BrainEventState.FINISH, "用户已停止", resultKind = BrainEventResultKind.STOPPED)

        assertEquals(UiStateController.UiState.STOPPED, controller.state)
    }

    @Test
    fun `pause request settles RUNNING through PAUSING to PAUSED`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.ACT, "执行中")

        emitVoice("pause_request")
        assertEquals(UiStateController.UiState.PAUSING, controller.state)

        emitBrain("task-1", 3, BrainEventState.FINISH, "已暂停", resultKind = BrainEventResultKind.PAUSED)
        assertEquals(UiStateController.UiState.PAUSED, controller.state)
    }

    @Test
    fun `stop request settles RUNNING through STOPPING to STOPPED`() {
        emitBrain("task-1", 1, BrainEventState.PROMPT_START, "开始")
        emitBrain("task-1", 2, BrainEventState.ACT, "执行中")

        emitVoice("stop_request")
        assertEquals(UiStateController.UiState.STOPPING, controller.state)

        emitBrain("task-1", 3, BrainEventState.FINISH, "已停止", resultKind = BrainEventResultKind.ABORTED)
        assertEquals(UiStateController.UiState.STOPPED, controller.state)
    }

    @Test
    fun `最近步骤最多保留5条`() {
        emitBrain("task-1", 1L, BrainEventState.PROMPT_START, "开始")
        for (i in 1..10) {
            emitBrain("task-1", (i + 1).toLong(), BrainEventState.ACT, "步骤 $i")
            emitBrain("task-1", (i + 100).toLong(), BrainEventState.ACT_DONE, "步骤 $i 完成")
        }
        assertEquals(5, controller.snapshot().recentSteps.size, "最近步骤不超过 5 条")
    }

    @Test
    fun `stop 后事件不再被消费（U2-H05）`() {
        controller.stop()
        emitBrain("task-1", 1L, BrainEventState.PROMPT_START, "停止后的事件")
        assertEquals(UiStateController.UiState.OFFLINE, controller.state)
    }
}
