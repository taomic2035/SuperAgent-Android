package com.superagent.body.core.ui

import com.superagent.body.core.events.EventBus
import com.superagent.common.BrainEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * UI 状态机（docs/12 §6，复用 Kestrel AgentStateController 模式）：
 * 消费 EventBus（brain/voice/hitl）→ 单一用户权威状态 + 最近步骤（≤5），
 * 悬浮层只订阅本控制器，不读内部实现细节、不展示模型原文。
 * 事件只是输入：去重（seq/时间戳）、旧任务隔离（taskId）、合法迁移在此收口。
 */
class UiStateController(private val events: EventBus) {

    enum class UiState { OFFLINE, MINI, IDLE, THINKING, RUNNING, PAUSING, PAUSED, AWAITING_CONFIRM, BLOCKED, COMPLETED, FAILED }

    data class Snapshot(
        val state: UiState,
        val currentStep: String,
        val recentSteps: List<String>,
        val unreadResult: Boolean,
    )

    @Volatile
    var state: UiState = UiState.OFFLINE
        private set

    private val recent = ArrayDeque<String>(5)
    private var lastBrainSeq = -1L
    private var activeTaskId = ""
    @Volatile
    private var unreadResult = false
    private val listeners = mutableListOf<(Snapshot) -> Unit>()

    fun start() {
        events.addListener { type, payload -> onEvent(type, payload) }
    }

    private fun onEvent(type: String, payloadJson: String?) {
        when (type) {
            "brain" -> payloadJson?.let { onBrain(it) }
            "voice" -> payloadJson?.let { onVoice(it) }
            "hitl" -> payloadJson?.let { onHitl(it) }
        }
    }

    private fun onBrain(payloadJson: String) {
        val ev = runCatching { Json.decodeFromString<BrainEvent>(payloadJson) }.getOrNull() ?: return
        // 旧任务/乱序事件不回退状态（docs/12 §5.3.5）
        if (ev.taskId != activeTaskId) {
            if (ev.state != "prompt_start") return
            activeTaskId = ev.taskId
            recent.clear()
        }
        if (ev.seq <= lastBrainSeq) return
        lastBrainSeq = ev.seq
        when (ev.state) {
            "prompt_start" -> transition(UiState.THINKING, ev.displayText.ifBlank { "正在理解" })
            "act" -> {
                if (state != UiState.THINKING && state != UiState.RUNNING) return
                transition(UiState.RUNNING, ev.displayText)
            }
            "act_done" -> pushStep(ev.displayText)
            "hitl_wait" -> transition(UiState.AWAITING_CONFIRM, ev.displayText.ifBlank { "等待确认" })
            "blocked" -> transition(UiState.BLOCKED, ev.displayText.ifBlank { "需要处理" })
            "finish" -> when (ev.resultKind) {
                "success" -> { unreadResult = true; transition(UiState.COMPLETED, ev.displayText.ifBlank { "已完成" }) }
                "aborted" -> transition(UiState.PAUSED, ev.displayText.ifBlank { "已暂停" })
                else -> { unreadResult = true; transition(UiState.FAILED, ev.displayText.ifBlank { "执行失败" }) }
            }
            "error" -> { unreadResult = true; transition(UiState.FAILED, ev.displayText.ifBlank { "出错" }) }
        }
    }

    private fun onVoice(payloadJson: String) {
        val obj = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return
        val kind = obj["kind"]?.jsonPrimitive?.content ?: return
        if (kind == "text_input" && state != UiState.RUNNING && state != UiState.THINKING) {
            // 本地受理（300ms 反馈义务在输入侧；此处仅复位待命态）
            transition(UiState.IDLE, "已收到 · 正在理解")
        }
    }

    private fun onHitl(payloadJson: String) {
        if (state == UiState.THINKING || state == UiState.RUNNING) {
            transition(UiState.AWAITING_CONFIRM, "等待确认")
        }
    }

    @Synchronized
    private fun transition(next: UiState, step: String) {
        state = next
        if (step.isNotBlank()) {
            if (recent.size >= 5) recent.removeFirst()
            recent.addLast(step)
        }
        publish()
    }

    @Synchronized
    private fun pushStep(step: String) {
        if (step.isBlank()) return
        if (recent.size >= 5) recent.removeFirst()
        recent.addLast(step)
        publish()
    }

    fun markRead() {
        unreadResult = false
        publish()
    }

    fun setOffline() = transition(UiState.OFFLINE, "")
    fun setIdle() = transition(UiState.IDLE, "")

    @Synchronized
    fun subscribe(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    @Synchronized
    fun snapshot() = Snapshot(state, recent.lastOrNull() ?: "", recent.toList(), unreadResult)

    @Synchronized
    private fun publish() {
        val s = snapshot()
        listeners.forEach { runCatching { it(s) } }
    }
}
