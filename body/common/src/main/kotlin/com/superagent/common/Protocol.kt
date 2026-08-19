package com.superagent.common

import kotlinx.serialization.Serializable

@Serializable
data class RpcRequest(
    val id: Int,
    val method: String,
    val params: JsonElement? = null,
    val idempotencyKey: String? = null,
)

@Serializable
data class RpcError(
    val code: String,
    val message: String,
    val reason: String? = null,
    /** AD-10：敏感动作被拒时的一次性 nonce——hitl.confirm 必须携带此值 */
    val nonce: String? = null,
)

@Serializable
data class RpcResponse(
    val id: Int,
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: RpcError? = null,
) {
    companion object {
        fun success(id: Int, result: JsonElement? = null): RpcResponse =
            RpcResponse(id, true, result)

        fun failure(id: Int, code: String, message: String, reason: String? = null, nonce: String? = null): RpcResponse =
            RpcResponse(id, false, null, RpcError(code, message, reason, nonce))
    }
}

typealias JsonElement = kotlinx.serialization.json.JsonElement

val json = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class BodyEvent(
    val seq: Long,
    val type: String,
    val payload: JsonElement? = null,
)

@Serializable
data class HealthStatus(
    val ok: Boolean,
    val bootId: String,
    val protocolVersion: Int,
    val uptimeMs: Long,
    val services: Map<String, Boolean>,
)

@Serializable
data class ActionResult(
    val located: Boolean,
    val signature: String? = null,
    val note: String? = null,
)

@Serializable
data class A11yNode(
    val label: String,
    val clickable: Boolean,
    val selected: Boolean? = null,
    val sensitive: Boolean? = null,
    val bounds: Bounds,
)

@Serializable
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@Serializable
data class Mark(
    val index: Int,
    val text: String,
    val center: Point,
)

@Serializable
data class Point(
    val x: Int,
    val y: Int,
)

@Serializable
data class ScreenResult(
    val signature: String,
    val kind: String,
    val blank: Boolean,
    val nodes: List<A11yNode>? = null,
    val marks: List<Mark>? = null,
    val pageTexts: List<String>? = null,
    val appPackage: String? = null,
    val sensitiveSession: Boolean = false,
    /** 视觉感知（L1）截图引用：GET /blob/{ref} 取 JPEG，brain 侧送 VLM 识别 marks */
    val screenshotRef: String? = null,
)

@Serializable
data class AsrResult(
    val text: String,
    val confidence: Double,
    val durationMs: Long,
)

@Serializable
data class SayResult(
    val route: String,
)

@Serializable
data class VoiceprintEnrollResult(
    val speaker: String,
    val samples: Int,
)

@Serializable
data class VoiceprintIdentifyResult(
    val speaker: String? = null,
    val confidence: Double,
)

@Serializable
data class SensorResult(
    val type: String,
    val value: Double,
    val timestamp: Long,
)

@Serializable
data class HeadsetResult(
    val connected: Boolean,
    val type: String,
)

@Serializable
data class SkillMeta(
    val name: String,
    val description: String,
    val appPackage: String,
    val tags: List<String>,
)

@Serializable
data class SkillListResult(
    val skills: List<SkillMeta>,
)

@Serializable
data class SkillSearchHit(
    val skill: SkillMeta,
    val score: Double,
)

@Serializable
data class SkillSearchResult(
    val hits: List<SkillSearchHit>,
)

@Serializable
data class SkillRunResult(
    val result: String,
    val completedSteps: Int,
)

@Serializable
data class SkillLearnResult(
    val slug: String,
)

@Serializable
data class HitlConfirmResult(
    val approved: Boolean,
)

@Serializable
data class HitlAskResult(
    val answer: String,
)

@Serializable
data class HitlHandoffResult(
    val taken: Boolean,
)

@Serializable
data class TraceStep(
    val tool: String,
    val args: Map<String, JsonElement>? = null,
    val located: Boolean,
    val signature: String? = null,
    val timestamp: Long,
)

/**
 * UI-0 事件回灌（docs/12 §7 UX 最低契约）：brain → body → 悬浮层。
 * 类型化封闭契约：禁止开放 payload 覆盖权威字段；seq 单调可去重；taskId 隔离旧任务事件。
 */
@Serializable
data class BrainEvent(
    val taskId: String,
    val seq: Long,
    /** 有限枚举（docs/12 §6 状态机）：prompt_start/act/act_done/hitl_wait/blocked/finish/error */
    val state: String,
    val stepIndex: Int? = null,
    /** 已脱敏、用户可读、长度受限的显示文本 */
    val displayText: String = "",
    /** 纯展示 none / 普通控制 control / 可信确认 confirm / 人工接管 handoff */
    val requiresUser: String = "none",
    /** finish 必填：success/failed/aborted/unknown_side_effect */
    val resultKind: String? = null,
    val timestamp: Long,
)