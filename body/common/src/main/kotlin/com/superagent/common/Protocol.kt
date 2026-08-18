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

        fun failure(id: Int, code: String, message: String, reason: String? = null): RpcResponse =
            RpcResponse(id, false, null, RpcError(code, message, reason))
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