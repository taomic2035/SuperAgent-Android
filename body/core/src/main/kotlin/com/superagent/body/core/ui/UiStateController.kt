package com.superagent.body.core.ui

import com.superagent.body.core.events.EventBus
import com.superagent.common.BrainEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

    private var eventListener: ((String, String?) -> Unit)? = null

    fun start() {
        val listener: (String, String?) -> Unit = { type, payloadJson -> onEvent(type, payloadJson) }
        eventListener = listener
        events.addListener(listener)
        // UX-11：10s 无心跳 → OFFLINE（brain 停止/崩溃/Termux 被 kill）；恢复心跳 → IDLE
        offlineChecker.scheduleAtFixedRate({
            val elapsed = System.currentTimeMillis() - lastHeartbeatMs
            if (elapsed > 10_000 && state != UiState.OFFLINE) {
                transition(UiState.OFFLINE, "大脑未连接")
            }
        }, 11, 3, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** U2-H05：服务销毁时清理（防监听器泄漏与重复状态迁移） */
    fun stop() {
        eventListener?.let { events.removeListener(it) }
        eventListener = null
        offlineChecker.shutdownNow()
    }

    private fun onEvent(type: String, payloadJson: String?) {
        when (type) {
            "brain" -> payloadJson?.let { onBrain(it) }
            "voice" -> payloadJson?.let { onVoice(it) }
            "hitl" -> payloadJson?.let { onHitl(it) }
            // codex 静态核验：Watchdog 的 a11y 状态必须进状态机——断开不得仍显示运行中
            "state" -> payloadJson?.let { onSystemState(it) }
        }
    }

    private fun onSystemState(payloadJson: String) {
        val obj = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return
        when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
            "a11y_persistent_failure" -> transition(UiState.BLOCKED, "无障碍服务断开，请在设置中重新开启")
            "a11y_recovered" -> if (state == UiState.BLOCKED) transition(UiState.IDLE, "")
            // a11y_disconnected（首轮）：瞬时抖动不打断显示，只靠通知兜底
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
                "aborted", "stopped" -> { unreadResult = true; transition(UiState.STOPPED, ev.displayText.ifBlank { "已停止" }) }
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
        // #26：暂停/停止请求即迁 PAUSING/STOPPING（终态由 brain 的 finish 事件 settle——paused/aborted）
        when (kind) {
            "pause_request" -> if (state == UiState.RUNNING || state == UiState.THINKING) transition(UiState.PAUSING, "暂停中·当前动作完成后停")
            "stop_request" -> if (state == UiState.RUNNING || state == UiState.THINKING || state == UiState.PAUSING || state == UiState.PAUSED) transition(UiState.STOPPING, "停止中")
            // C-06 自动断点续跑尚未闭环：不得假装已恢复或承诺不可用的“继续”。
            "resume_request" -> if (state == UiState.PAUSED) transition(UiState.PAUSED, "暂不能自动恢复·请重新发起任务")
        }
        if (kind == "text_input") {
            if (state == UiState.OFFLINE) {
                transition(UiState.OFFLINE, "未发送·大脑离线")
            } else if (state != UiState.RUNNING && state != UiState.THINKING) {
                // 本地受理（300ms 反馈义务在输入侧；此处仅复位待命态）
                transition(UiState.IDLE, "已收到 · 正在理解")
            }
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

    /**
     * UI 文字指令的唯一发布口：OFFLINE 时在 EventBus 之前 fail-closed，
     * 避免 brain 重启后跳过历史事件时 UI 却声称“已收到”。
     */
    @Synchronized
    fun submitTextInput(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false
        if (state == UiState.OFFLINE) {
            transition(UiState.OFFLINE, "未发送·大脑离线")
            return false
        }
        events.emit("voice", buildJsonObject { put("kind", "text_input"); put("text", normalized) })
        return true
    }

    /** C-06 完成前不发布 resume_request：显示拒绝不能代替真实拦截。 */
    @Synchronized
    fun requestResume(): Boolean {
        if (state == UiState.PAUSED) {
            transition(UiState.PAUSED, "暂不能自动恢复·请重新发起任务")
        }
        return false
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
        UiState.OFFLINE -> recent.lastOrNull()?.takeIf { it.startsWith("未发送") } ?: "离线 · 大脑未连接"
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
