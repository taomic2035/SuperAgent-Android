package com.superagent.body.core

import android.content.Context
import android.util.Log
import com.superagent.body.core.control.Controller
import com.superagent.body.core.control.OptionSelector
import com.superagent.body.core.control.PointArg
import com.superagent.body.core.events.EventBus
import com.superagent.body.core.hardware.HardwareService
import com.superagent.body.core.hitl.Hitl
import com.superagent.body.core.http.BodyServer
import com.superagent.body.core.perception.ScreenPerceiver
import com.superagent.body.core.skills.SkillStore
import com.superagent.body.core.speech.SpeechEngine
import com.superagent.body.core.speech.SpeechUnavailable
import com.superagent.body.core.speech.VoiceConfig
import com.superagent.common.ActionResult
import com.superagent.common.JsonElement
import com.superagent.common.RpcResponse
import com.superagent.common.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 躯体核心装配：把感知/操控/语音/硬件/技能/HITL 挂到 RPC 分发表上。
 * 只依赖第三方：NanoHTTPD + sherpa-onnx（third/）。R1 大脑不可达时躯体仍自持。
 */
class BodyCore(
    private val context: Context,
    private val a11y: () -> android.accessibilityservice.AccessibilityService?,
) {
    private val events = EventBus()
    private val perceiver = ScreenPerceiver(a11y)
    private val controller = Controller(context, a11y)
    private val selector = OptionSelector(perceiver, controller)
    private val speech = SpeechEngine(context)
    private val sensitiveSession = com.superagent.body.core.security.SensitiveSessionTracker()
    private val voiceLoop = com.superagent.body.core.voice.VoiceLoop(context, events)
    private val hardware = HardwareService(context)
    private val hitl = Hitl(context, events)
    private val skills = SkillStore(File(context.filesDir, "skills"), perceiver, selector, controller, events)
    private val server = BodyServer(events)
    private val started = AtomicBoolean(false)

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        registerHandlers()
        runCatching { server.start() }
            .onFailure { e ->
                started.set(false)
                throw IllegalStateException("躯体服务启动失败: ${e.message}", e)
            }
        Log.i(TAG, "body server on ${BodyContext.settings.host}:${BodyContext.settings.port}")
        return true
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        server.stopAndWait()
    }

    private fun registerHandlers() {
        server.rpc("perceive.screen") { req ->
            if (a11y() == null) {
                return@rpc RpcResponse.failure(req.id, "A11Y_DISCONNECTED", "无障碍服务未连接，请在设置中开启", "a11y")
            }
            val mode = req.params?.jsonObject?.get("mode")?.toString()?.trim('"') ?: "auto"
            ok(req, perceiver.perceive(mode))
        }

        server.rpc("control.tap") { req ->
            val p = params(req)
            val x = p.int("x") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 x")
            val y = p.int("y") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 y")
            ok(req, controller.tap(x, y))
        }

        server.rpc("control.longPress") { req ->
            val p = params(req)
            val x = p.int("x") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 x")
            val y = p.int("y") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 y")
            ok(req, controller.longPress(x, y, p.int("durationMs")?.toLong() ?: 600L))
        }

        server.rpc("control.swipe") { req ->
            val p = params(req)
            val fromX = p.int("fromX") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 fromX")
            val fromY = p.int("fromY") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 fromY")
            val toX = p.int("toX") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 toX")
            val toY = p.int("toY") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 toY")
            ok(req, controller.swipe(fromX, fromY, toX, toY, p.int("durationMs")?.toLong() ?: 300L))
        }

        server.rpc("control.typeText") { req ->
            val p = params(req)
            val text = p.string("text") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 text")
            ok(req, controller.typeText(text))
        }

        server.rpc("control.selectOption") { req ->
            val p = params(req)
            val label = p.string("label") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 label")
            val near = p.near()
            if (sensitiveSession.needsExtraConfirm(label)) {
                return@rpc RpcResponse.failure(req.id, "COMMIT_BOUNDARY", "敏感会话内确认动作需人工确认", "sensitive_session")
            }
            val result = selector.select(label, near, verifySelected = false)
            if (result.note == "COMMIT_BOUNDARY") {
                return@rpc RpcResponse.failure(req.id, "COMMIT_BOUNDARY", "提交边界拦截（躯体侧兜底）", "commit")
            }
            ok(req, result)
        }

        server.rpc("control.selectSpec") { req ->
            val p = params(req)
            val label = p.string("label") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 label")
            val near = p.near()
            if (sensitiveSession.needsExtraConfirm(label)) {
                return@rpc RpcResponse.failure(req.id, "COMMIT_BOUNDARY", "敏感会话内确认动作需人工确认", "sensitive_session")
            }
            val result = selector.select(label, near, verifySelected = true)
            if (result.note == "COMMIT_BOUNDARY") {
                return@rpc RpcResponse.failure(req.id, "COMMIT_BOUNDARY", "提交边界拦截（躯体侧兜底）", "commit")
            }
            ok(req, result)
        }

        server.rpc("control.back") { req -> ok(req, controller.back()) }
        server.rpc("control.home") { req -> ok(req, controller.home()) }

        server.rpc("control.launch") { req ->
            val pkg = params(req).string("pkg") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 pkg")
            sensitiveSession.onLaunch(pkg)
            ok(req, controller.launch(pkg))
        }

        server.rpc("speech.asr") { req ->
            runCatching { speech.recognize() }
                .fold(
                    { ok(req, it) },
                    { e -> if (e is SpeechUnavailable) speechError(req, e) else throw e },
                )
        }

        server.rpc("speech.say") { req ->
            val p = params(req)
            val text = p.string("text") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 text")
            val voice = p.json.get("voice")?.let {
                runCatching {
                    json.decodeFromJsonElement<VoiceConfig>(it)
                }.getOrNull()
            }
            runCatching { speech.say(text, voice) }
                .fold(
                    { ok(req, it) },
                    { e -> if (e is SpeechUnavailable) speechError(req, e) else throw e },
                )
        }

        server.rpc("speech.interrupt") { req ->
            speech.interrupt()
            emptyOk(req)
        }

        server.rpc("speech.voiceprintEnroll") { req ->
            val name = params(req).string("name") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 name")
            runCatching { speech.enroll(name) }
                .fold(
                    { ok(req, it) },
                    { e -> if (e is SpeechUnavailable) speechError(req, e) else throw e },
                )
        }

        server.rpc("speech.voiceprintIdentify") { req ->
            runCatching { speech.identify() }
                .fold(
                    { ok(req, it) },
                    { e -> if (e is SpeechUnavailable) speechError(req, e) else throw e },
                )
        }

        server.rpc("hardware.audioRoute") { req ->
            val target = params(req).string("target") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 target")
            runCatching { hardware.audioRoute(target) }
                .fold(
                    { ok(req, it) },
                    { RpcResponse.failure(req.id, "AUDIO_ROUTE", it.message ?: "路由失败") },
                )
        }

        server.rpc("hardware.vibrate") { req ->
            val p = params(req)
            val pattern = p.json.get("pattern")?.let {
                runCatching { json.decodeFromJsonElement<List<Int>>(it) }.getOrNull()
            } ?: listOf(0, 200, 100, 200)
            hardware.vibrate(pattern, p.int("repeat") ?: -1)
            emptyOk(req)
        }

        server.rpc("hardware.sensor") { req ->
            val type = params(req).string("type") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 type")
            runCatching { hardware.sensor(type) }
                .fold(
                    { ok(req, it) },
                    { RpcResponse.failure(req.id, "SENSOR", it.message ?: "传感器错误") },
                )
        }

        server.rpc("hardware.headset") { req -> ok(req, hardware.headset()) }

        server.rpc("skill.list") { req -> ok(req, skills.list()) }

        server.rpc("skill.run") { req ->
            val name = params(req).string("name") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 name")
            runCatching { skills.run(name) }
                .fold(
                    { outcome ->
                        when (outcome) {
                            is com.superagent.body.core.skills.SkillRunOutcome.Success ->
                                ok(req, com.superagent.common.SkillRunResult("success", outcome.completedSteps))
                            is com.superagent.body.core.skills.SkillRunOutcome.SensitiveHandoff ->
                                ok(req, com.superagent.common.SkillRunResult("sensitive_handoff", outcome.completedSteps))
                            is com.superagent.body.core.skills.SkillRunOutcome.Stale ->
                                RpcResponse.failure(req.id, "SKILL_STALE",
                                    "失配在第${outcome.failedStepIndex}步(完成${outcome.completedSteps}步)，工具=${outcome.failedStep.tool}",
                                    "stale")
                        }
                    },
                    { RpcResponse.failure(req.id, "SKILL_NOT_FOUND", it.message ?: "回放失败") },
                )
        }

        server.rpc("skill.learn") { req ->
            val p = params(req)
            val goal = p.string("goal") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 goal")
            val appPackage = p.string("appPackage") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 appPackage")
            val trace = p.json.get("trace")?.let {
                runCatching { json.decodeFromJsonElement<List<com.superagent.common.TraceStep>>(it) }.getOrNull()
            } ?: emptyList()
            runCatching { skills.learn(goal, appPackage, trace) }
                .fold(
                    { ok(req, it) },
                    { RpcResponse.failure(req.id, "SKILL_LEARN", it.message ?: "固化失败") },
                )
        }

        server.rpc("skill.feedback") { req ->
            val p = params(req)
            val name = p.string("name") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 name")
            val success = p.json.get("success")?.jsonPrimitive?.content == "true"
            runCatching { skills.feedback(name, success) }
                .fold(
                    { emptyOk(req) },
                    { RpcResponse.failure(req.id, "SKILL_NOT_FOUND", it.message ?: "反馈失败") },
                )
        }

        server.rpc("hitl.confirm") { req ->
            val prompt = params(req).string("prompt") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 prompt")
            ok(req, com.superagent.common.HitlConfirmResult(hitl.confirm(prompt)))
        }

        server.rpc("hitl.ask") { req ->
            val prompt = params(req).string("prompt") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 prompt")
            ok(req, com.superagent.common.HitlAskResult(hitl.ask(prompt)))
        }

        server.rpc("hitl.handoff") { req ->
            val reason = params(req).string("reason") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 reason")
            ok(req, com.superagent.common.HitlHandoffResult(hitl.handoff(reason)))
        }
    }

    private fun params(req: com.superagent.common.RpcRequest): Params =
        Params(req.params?.jsonObject ?: buildJsonObject {})

    private class Params(private val obj: JsonObject) {
        val json: JsonObject get() = obj
        fun string(key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull
        fun int(key: String): Int? = obj[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        fun near(): PointArg? {
            val near = obj["near"] ?: return null
            val o = near.jsonObject
            val x = o["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null
            val y = o["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null
            return PointArg(x, y)
        }
    }

    private inline fun <reified T> ok(req: com.superagent.common.RpcRequest, result: T): RpcResponse =
        RpcResponse.success(req.id, json.encodeToJsonElement(result))

    private fun emptyOk(req: com.superagent.common.RpcRequest): RpcResponse =
        RpcResponse.success(req.id, json.parseToJsonElement("{}"))

    private fun bad(req: com.superagent.common.RpcRequest, code: String, message: String): RpcResponse =
        RpcResponse.failure(req.id, code, message)

    private fun speechError(req: com.superagent.common.RpcRequest, e: SpeechUnavailable): RpcResponse =
        RpcResponse.failure(req.id, "SPEECH_UNAVAILABLE", e.message ?: "语音模型未安装", "model")

    companion object {
        private const val TAG = "BodyCore"
    }
}