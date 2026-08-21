package com.superagent.body.core.ui

import com.superagent.body.core.events.EventBus
import com.superagent.common.BrainEvent
import com.superagent.common.BrainEventResultKind
import com.superagent.common.BrainEventState
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
    enum class TextInputResult { ACCEPTED, REJECTED_EMPTY, REJECTED_OFFLINE, REJECTED_CONTROL_PENDING, REJECTED_PAUSED, REJECTED_WAITING_USER }
    private enum class BlockerSource { NONE, A11Y, BRAIN }
    private data class PendingProtectedState(val snapshot: Snapshot, val blockerSource: BlockerSource)

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
    @Volatile
    private var blockerSource = BlockerSource.NONE
    @Volatile
    private var pendingProtectedState: PendingProtectedState? = null
    private val listeners = mutableListOf<(Snapshot) -> Unit>()

    private var eventListener: ((String, String?) -> Unit)? = null

    fun start() {
        val listener: (String, String?) -> Unit = { type, payloadJson -> onEvent(type, payloadJson) }
        eventListener = listener
        events.addListener(listener)
        // UX-11：10s 无心跳 → OFFLINE；心跳后恢复 IDLE，或恢复尚未解决的可信 BLOCKED。
        offlineChecker.scheduleAtFixedRate({
            val elapsed = System.currentTimeMillis() - lastHeartbeatMs
            if (elapsed > 10_000 && state != UiState.OFFLINE) {
                setOffline()
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

    @Synchronized
    private fun onSystemState(payloadJson: String) {
        val obj = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return
        when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
            "a11y_persistent_failure" -> if (state != UiState.BLOCKED) {
                blockerSource = BlockerSource.A11Y
                transition(UiState.BLOCKED, "无障碍服务断开，请在设置中重新开启")
            }
            "a11y_recovered" -> if (blockerSource == BlockerSource.A11Y) {
                blockerSource = BlockerSource.NONE
                if (pendingProtectedState?.blockerSource == BlockerSource.A11Y) pendingProtectedState = null
                if (state == UiState.BLOCKED) transition(UiState.IDLE, "")
            }
            // a11y_disconnected（首轮）：瞬时抖动不打断显示，只靠通知兜底
        }
    }

    @Synchronized
    private fun onBrain(payloadJson: String) {
        val ev = runCatching { Json.decodeFromString<BrainEvent>(payloadJson) }.getOrNull() ?: return
        // 心跳：brain 在线信号，恢复 IDLE（不改变运行态——心跳只证明连通性）
        if (ev.state == BrainEventState.HEARTBEAT) {
            lastHeartbeatMs = ev.timestamp
            if (state == UiState.OFFLINE) restoreAfterHeartbeat()
            return
        }
        lastHeartbeatMs = ev.timestamp // 任何 brain 事件也证明在线
        if (!acceptsBrainEvent(ev)) return
        // 旧任务/乱序事件不回退状态（docs/12 §5.3.5）
        if (ev.taskId != activeTaskId) {
            if (ev.state != BrainEventState.PROMPT_START) return
            activeTaskId = ev.taskId
            recent.clear()
            // U2-H03：brain 重启后 seq 归零——新 taskId 到来时重置水位（否则新事件全被旧水位丢弃）
            lastBrainSeq = -1
        }
        if (ev.seq <= lastBrainSeq) return
        lastBrainSeq = ev.seq
        // OFFLINE 背后的保护快照已被合法 settle：在应用新终态前原子清除，
        // 否则下一次断连/心跳会复活旧 PAUSED/AWAITING_CONFIRM/BLOCKED。
        if ((ev.state == BrainEventState.FINISH || ev.state == BrainEventState.ERROR) &&
            pendingProtectedState != null
        ) {
            pendingProtectedState = null
            blockerSource = BlockerSource.NONE
        }
        when (ev.state) {
            BrainEventState.PROMPT_START -> {
                pendingProtectedState = null
                blockerSource = BlockerSource.NONE
                transition(UiState.THINKING, ev.displayText.ifBlank { "正在理解" })
            }
            BrainEventState.ACT -> {
                if (state != UiState.THINKING && state != UiState.RUNNING) return
                transition(UiState.RUNNING, ev.displayText)
            }
            BrainEventState.ACT_DONE -> pushStep(ev.displayText)
            BrainEventState.HITL_WAIT -> transition(UiState.AWAITING_CONFIRM, ev.displayText.ifBlank { "等待确认" })
            BrainEventState.BLOCKED -> {
                blockerSource = BlockerSource.BRAIN
                transition(UiState.BLOCKED, ev.displayText.ifBlank { "需要处理" })
            }
            BrainEventState.FINISH -> when (ev.resultKind) {
                BrainEventResultKind.SUCCESS -> { unreadResult = true; transition(UiState.COMPLETED, ev.displayText.ifBlank { "已完成" }) }
                BrainEventResultKind.ABORTED, BrainEventResultKind.STOPPED -> { unreadResult = true; transition(UiState.STOPPED, ev.displayText.ifBlank { "已停止" }) }
                BrainEventResultKind.PAUSED -> transition(UiState.PAUSED, ev.displayText.ifBlank { "已暂停" })
                BrainEventResultKind.UNKNOWN_SIDE_EFFECT -> {
                    unreadResult = false
                    blockerSource = BlockerSource.BRAIN
                    transition(UiState.BLOCKED, "执行结果未知 · 请核对设备状态")
                }
                else -> { unreadResult = true; transition(UiState.FAILED, ev.displayText.ifBlank { "执行失败" }) }
            }
            BrainEventState.ERROR -> { unreadResult = true; transition(UiState.FAILED, ev.displayText.ifBlank { "出错" }) }
            BrainEventState.HEARTBEAT -> Unit // handled above before task/seq routing
        }
    }

    @Synchronized
    private fun onVoice(payloadJson: String) {
        val obj = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return
        val kind = obj["kind"]?.jsonPrimitive?.content ?: return
        // #26：暂停/停止请求即迁 PAUSING/STOPPING（终态由 brain 的 finish 事件 settle——paused/aborted）
        when (kind) {
            "pause_request" -> if (state == UiState.RUNNING || state == UiState.THINKING) transition(UiState.PAUSING, "暂停中·当前动作完成后停")
            "stop_request" -> if (state == UiState.RUNNING || state == UiState.THINKING || state == UiState.PAUSING || state == UiState.PAUSED) transition(UiState.STOPPING, "停止中")
            // resume_request 的本地状态由 requestResume() 在发布前原子迁移；消费侧不重复迁移。
            "resume_request" -> Unit
        }
        if (kind == "text_input") {
            if (textInputRejection(state) != null) {
                return
            } else if (state == UiState.OFFLINE) {
                transition(UiState.OFFLINE, "未发送·大脑离线")
            } else if (state != UiState.RUNNING && state != UiState.THINKING) {
                // 本地受理（300ms 反馈义务在输入侧；此处仅复位待命态）
                transition(UiState.IDLE, "已收到 · 正在理解")
            }
        }
    }

    @Synchronized
    private fun onHitl(payloadJson: String) {
        if (state == UiState.THINKING || state == UiState.RUNNING) {
            transition(UiState.AWAITING_CONFIRM, "等待确认")
        }
    }

    /** OFFLINE 只是连通性展示；未解决的保护态仍是入站迁移的权威起点。 */
    private fun effectiveState(): UiState = if (state == UiState.OFFLINE) {
        pendingProtectedState?.snapshot?.state ?: UiState.OFFLINE
    } else {
        state
    }

    /** Brain 事件只能在当前用户语义允许的阶段打开、追加或 settle 流程。 */
    private fun acceptsBrainEvent(event: BrainEvent): Boolean {
        val from = effectiveState()
        return when (event.state) {
            BrainEventState.PROMPT_START -> from == UiState.OFFLINE ||
                from == UiState.MINI ||
                from == UiState.IDLE ||
                from == UiState.THINKING
            BrainEventState.HITL_WAIT, BrainEventState.BLOCKED ->
                from == UiState.THINKING || from == UiState.RUNNING
            BrainEventState.ACT_DONE -> from == UiState.RUNNING
            BrainEventState.FINISH -> acceptsFinish(from, event.resultKind)
            BrainEventState.ERROR -> from == UiState.THINKING ||
                from == UiState.RUNNING ||
                from == UiState.PAUSING ||
                from == UiState.STOPPING ||
                from == UiState.AWAITING_CONFIRM
            else -> true
        }
    }

    private fun acceptsFinish(from: UiState, result: BrainEventResultKind?): Boolean = when (result) {
        BrainEventResultKind.SUCCESS, BrainEventResultKind.FAILED ->
            from == UiState.THINKING || from == UiState.RUNNING
        BrainEventResultKind.PAUSED ->
            from == UiState.THINKING || from == UiState.RUNNING || from == UiState.PAUSING
        BrainEventResultKind.ABORTED, BrainEventResultKind.STOPPED ->
            from == UiState.THINKING ||
                from == UiState.RUNNING ||
                from == UiState.PAUSING ||
                from == UiState.PAUSED ||
                from == UiState.STOPPING ||
                from == UiState.AWAITING_CONFIRM
        BrainEventResultKind.UNKNOWN_SIDE_EFFECT ->
            from == UiState.THINKING ||
                from == UiState.RUNNING ||
                from == UiState.PAUSING ||
                from == UiState.PAUSED ||
                from == UiState.STOPPING ||
                from == UiState.AWAITING_CONFIRM
        null -> false
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
    fun submitTextInput(text: String): TextInputResult {
        val normalized = text.trim()
        if (normalized.isEmpty()) return TextInputResult.REJECTED_EMPTY
        if (state == UiState.OFFLINE) {
            transition(UiState.OFFLINE, "未发送·大脑离线")
            return TextInputResult.REJECTED_OFFLINE
        }
        // STOP > PAUSE > RESUME > NEW_TASK：未 settle 的控制请求以及可恢复断点
        // 都不能被一条新文字指令隐式覆盖。调用方只得到本地拒绝，原状态/断点保持不变。
        textInputRejection(state)?.let { return it }
        events.emit("voice", buildJsonObject { put("kind", "text_input"); put("text", normalized) })
        return TextInputResult.ACCEPTED
    }

    /** 新指令不可覆盖的控制/等待状态；发布口与原始 EventBus 入站共用同一策略。 */
    private fun textInputRejection(current: UiState): TextInputResult? = when (current) {
        UiState.PAUSING, UiState.STOPPING -> TextInputResult.REJECTED_CONTROL_PENDING
        UiState.PAUSED -> TextInputResult.REJECTED_PAUSED
        UiState.AWAITING_CONFIRM, UiState.BLOCKED -> TextInputResult.REJECTED_WAITING_USER
        else -> null
    }

    /** C-06：仅 settled PAUSED 可发布一次；先显示恢复中，再交给 brain 原子 claim。 */
    @Synchronized
    fun requestResume(): Boolean {
        if (state != UiState.PAUSED) return false
        transition(UiState.THINKING, "正在恢复")
        events.emit("voice", buildJsonObject { put("kind", "resume_request") })
        return true
    }

    /** 连通性丢失只覆盖展示，不解决已经确认的阻断处置。 */
    @Synchronized
    fun setOffline() {
        if (state == UiState.BLOCKED || state == UiState.PAUSED || state == UiState.AWAITING_CONFIRM) {
            pendingProtectedState = PendingProtectedState(snapshot(), blockerSource)
        }
        transition(UiState.OFFLINE, "大脑未连接")
    }

    @Synchronized
    private fun restoreAfterHeartbeat() {
        val protected = pendingProtectedState
        if (protected == null) {
            transition(UiState.IDLE, "")
            return
        }
        pendingProtectedState = null
        blockerSource = protected.blockerSource
        restoreSnapshot(protected.snapshot)
    }

    @Synchronized
    private fun restoreSnapshot(saved: Snapshot) {
        state = saved.state
        recent.clear()
        recent.addAll(saved.recentSteps)
        unreadResult = saved.unreadResult
        publish()
    }

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
