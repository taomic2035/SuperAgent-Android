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

    enum class UiState { OFFLINE, MINI, IDLE, THINKING, RUNNING, PAUSING, PAUSED, STOPPING, STOPPED, AWAITING_CONFIRM, BLOCKED, COMPLETED, FAILED }

    data class Snapshot(
        val state: UiState,
        val currentStep: String,
        val recentSteps: List<String>,
        val unreadResult: Boolean,
    )

    @Volatile
    var state: UiState = UiState.OFFLINE
        private set

    @Volatile
    private var lastHeartbeatMs = 0L

    private val offlineChecker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ui-offline-checker").apply { isDaemon = true }
    }

    private val recent = ArrayDeque<String>(5)
    private var lastBrainSeq = -1L
    private var activeTaskId = ""
    @Volatile
    private var unreadResult = false
    private val listeners = mutableListOf<(Snapshot) -> Unit>()

    fun start() {
        events.addListener { type, payloadJson -> onEvent(type, payloadJson) }
        // UX-11：10s 无心跳 → OFFLINE（brain 停止/崩溃/Termux 被 kill）；恢复心跳 → IDLE
        offlineChecker.scheduleAtFixedRate({
            val elapsed = System.currentTimeMillis() - lastHeartbeatMs
            if (elapsed > 10_000 && state != UiState.OFFLINE) {
                transition(UiState.OFFLINE, "大脑未连接")
            }
        }, 11, 3, java.util.concurrent.TimeUnit.SECONDS)
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
        // 心跳：brain 在线信号，恢复 IDLE（不改变运行态——心跳只证明连通性）
        if (ev.state == "heartbeat") {
            lastHeartbeatMs = ev.timestamp
            if (state == UiState.OFFLINE) transition(UiState.IDLE, "")
            return
        }
        lastHeartbeatMs = ev.timestamp // 任何 brain 事件也证明在线
        // 旧任务/乱序事件不回退状态（docs/12 §5.3.5）
        if (ev.taskId != activeTaskId) {
            if (ev.state != "prompt_start") return
            activeTaskId = ev.taskId
            recent.clear()
            // U2-H03：brain 重启后 seq 归零——新 taskId 到来时重置水位（否则新事件全被旧水位丢弃）
            lastBrainSeq = -1
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
                "aborted" -> { unreadResult = true; transition(UiState.STOPPED, ev.displayText.ifBlank { "已停止" }) }
                "paused" -> transition(UiState.PAUSED, ev.displayText.ifBlank { "已暂停" })
                "unknown_side_effect" -> { unreadResult = true; transition(UiState.FAILED, ev.displayText.ifBlank { "停止中·正在确认设备状态" }) }
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

    /** FGS 通知兜底文案（docs/12 §3.1）：无 overlay 时用户仍能看到状态。 */
    fun notificationText(): String = when (state) {
        UiState.OFFLINE -> "离线 · 大脑未连接"
        UiState.MINI -> "待命"
        UiState.IDLE -> "就绪 · 点击悬浮球或通知"
        UiState.THINKING -> "理解中 · ${recent.lastOrNull() ?: ""}"
        UiState.RUNNING -> "执行中 · ${recent.lastOrNull() ?: ""}"
        UiState.PAUSING -> "正在暂停…"
        UiState.PAUSED -> "已暂停"
        UiState.STOPPING -> "正在停止…"
        UiState.STOPPED -> "已停止"
        UiState.AWAITING_CONFIRM -> "等待确认"
        UiState.BLOCKED -> "需要处理 · ${recent.lastOrNull() ?: ""}"
        UiState.COMPLETED -> "已完成 · ${recent.lastOrNull() ?: ""}"
        UiState.FAILED -> "失败 · ${recent.lastOrNull() ?: ""}"
    }

    @Synchronized
    private fun publish() {
        val s = snapshot()
        listeners.forEach { runCatching { it(s) } }
    }
}
