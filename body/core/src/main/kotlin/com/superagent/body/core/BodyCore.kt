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
import com.superagent.common.CommitBoundaryGuard
import com.superagent.common.JsonElement
import com.superagent.common.RpcResponse
import com.superagent.common.SayResult
import com.superagent.common.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
    private val skills = SkillStore(File(context.filesDir, "skills"), perceiver, selector, controller, events, sensitiveSession)
    private val blobsDir = File(context.filesDir, "blobs")
    private val screenshots = com.superagent.body.core.screenshot.ScreenshotService(context).also {
        com.superagent.body.core.screenshot.ScreenshotService.shared = it
    }
    private val server = BodyServer(events, blobsDir)
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
            // L1 视觉（BD-02.2 第一步）：已授权屏幕捕获时返回截图引用，VLM 识别在 brain 侧；
            // 未授权自动回退 a11y（typed note，不硬失败）
            if (mode == "vision" || mode == "auto") {
                // P0：auto 仍走 a11y（视觉待 brain 侧 VLM 通路就绪后启用），仅显式 vision 走截图
                if (mode == "vision") {
                    val ref = runCatching { screenshots.capture(blobsDir) }.getOrNull()
                    if (ref != null) {
                        val a11yScreen = perceiver.perceive("a11y", sensitiveSession.inSensitiveSession)
                        sensitiveSession.onForeground(a11yScreen.appPackage)
                        return@rpc ok(
                            req,
                            a11yScreen.copy(kind = "vision", marks = null, nodes = null, pageTexts = null, screenshotRef = ref),
                        )
                    }
                    val fallback = perceiver.perceive("a11y", sensitiveSession.inSensitiveSession)
                    sensitiveSession.onForeground(fallback.appPackage)
                    return@rpc ok(req, fallback)
                }
            }
            val screen = perceiver.perceive(mode, sensitiveSession.inSensitiveSession)
            // 审计 P0-01：每次感知以真实前台包名同步敏感会话（用户手动打开敏感 App 不再漏判）
            sensitiveSession.onForeground(screen.appPackage)
            ok(req, screen)
        }

        server.rpc("control.tap") { req ->
            val p = params(req)
            val x = p.int("x") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 x")
            val y = p.int("y") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 y")
            gate(x, y)?.let { return@rpc it.toFailure(req) }
            ok(req, withStableSig(controller.tap(x, y)))
        }

        server.rpc("control.longPress") { req ->
            val p = params(req)
            val x = p.int("x") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 x")
            val y = p.int("y") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 y")
            gate(x, y)?.let { return@rpc it.toFailure(req) }
            ok(req, withStableSig(controller.longPress(x, y, p.int("durationMs")?.toLong() ?: 600L)))
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
            ok(req, withStableSig(result))
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
            ok(req, withStableSig(result))
        }

        server.rpc("control.back") { req -> ok(req, controller.back()) }
        server.rpc("control.home") { req ->
            sensitiveSession.onHome()
            ok(req, controller.home())
        }

        server.rpc("control.launch") { req ->
            val pkg = params(req).string("pkg") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 pkg")
            sensitiveSession.onLaunch(pkg)
            ok(req, controller.launch(pkg))
        }

        server.rpc("speech.asr", SPEECH_RPC_TIMEOUT_MS) { req ->
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
            // 播报不阻塞 RPC（首次加载 310MB Kokoro 模型可超 30s 硬超时）：
            // 请求即回，合成+播放切后台线程。
            runCatching { speech.isReady()["tts"] == true }
                .getOrDefault(false)
                .takeIf { it } ?: return@rpc speechError(req, SpeechUnavailable("TTS 模型未安装（scripts/fetch-models）"))
            val play = speech
            val t = text
            val v = voice
            Thread {
                runCatching { play.say(t, v) }
                    .onFailure { e ->
                        Log.e("BodyCore", "TTS 播放失败", e)
                        // fire-and-forget 的失败也要可观测（brain 可经 /events 感知），不静默吞
                        events.emit(
                            "speech",
                            buildJsonObject {
                                put("kind", "say_failed")
                                put("error", e.message ?: e.javaClass.simpleName)
                            },
                        )
                    }
            }.start()
            ok(req, SayResult("speaker"))
        }

        server.rpc("speech.interrupt") { req ->
            speech.interrupt()
            emptyOk(req)
        }

        server.rpc("speech.voiceprintEnroll", SPEECH_RPC_TIMEOUT_MS) { req ->
            val name = params(req).string("name") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 name")
            runCatching { speech.enroll(name) }
                .fold(
                    { ok(req, it) },
                    { e -> if (e is SpeechUnavailable) speechError(req, e) else throw e },
                )
        }

        server.rpc("speech.voiceprintIdentify", SPEECH_RPC_TIMEOUT_MS) { req ->
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
        server.rpc("skill.search") { req ->
            val query = params(req).string("query") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 query")
            ok(req, skills.search(query))
        }

        server.rpc("skill.run", SKILL_RUN_RPC_TIMEOUT_MS) { req ->
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

        server.rpc("hitl.confirm", HITL_RPC_TIMEOUT_MS) { req ->
            val prompt = params(req).string("prompt") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 prompt")
            // action=被拦截动作的确切文字（如「发送」）。用户同意后按标签放行一次，
            // 否则敏感会话内 selectOption 重试会被 needsExtraConfirm 永久拦截（死锁）。
            val action = params(req).string("action")
            val approved = hitl.confirm(prompt)
            if (approved && action != null) sensitiveSession.approve(action)
            ok(req, com.superagent.common.HitlConfirmResult(approved))
        }

        server.rpc("hitl.ask", HITL_RPC_TIMEOUT_MS) { req ->
            val prompt = params(req).string("prompt") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 prompt")
            ok(req, com.superagent.common.HitlAskResult(hitl.ask(prompt)))
        }

        server.rpc("hitl.handoff", HITL_RPC_TIMEOUT_MS) { req ->
            val reason = params(req).string("reason") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 reason")
            ok(req, com.superagent.common.HitlHandoffResult(hitl.handoff(reason)))
        }
    }

    /** 成功动作后附加**稳定后**签名（#18 learn 存 expectedSignature；#24 等页面过渡结束再采样）。 */
    private fun withStableSig(result: com.superagent.common.ActionResult): com.superagent.common.ActionResult =
        if (result.located) result.copy(signature = perceiver.settledStableSignature() ?: result.signature) else result

    private fun params(req: com.superagent.common.RpcRequest): Params =
        Params(req.params?.jsonObject ?: buildJsonObject {})

    /** 坐标动作统一闸门（审计 P0-02/03：提交边界 + 敏感会话动作，全包含节点检查）。 */
    private fun gate(x: Int, y: Int): com.superagent.body.core.security.ActionGate.Violation? =
        com.superagent.body.core.security.ActionGate.violatingLabel(perceiver, sensitiveSession, x, y)

    private fun com.superagent.body.core.security.ActionGate.Violation.toFailure(req: com.superagent.common.RpcRequest): RpcResponse =
        when (this) {
            is com.superagent.body.core.security.ActionGate.Violation.Commit ->
                RpcResponse.failure(req.id, "COMMIT_BOUNDARY", "落点「$label」是提交边界动作，坐标点击不可绕过（转 hitl）", reason)
            is com.superagent.body.core.security.ActionGate.Violation.SensitiveSession ->
                RpcResponse.failure(req.id, "COMMIT_BOUNDARY", "敏感会话内动作「$label」需人工确认（hitl.confirm 带 action 放行）", reason)
        }

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

        /** RPC 等待必须 > handler 内部等待上限：HITL 等用户 60s，留事件/清理余量。 */
        private const val HITL_RPC_TIMEOUT_MS = Hitl.HITL_TIMEOUT_MS + 15_000L

        /** 录音最长 15s/段（声纹注册 3 段）+ 首次加载 sherpa 模型。 */
        private const val SPEECH_RPC_TIMEOUT_MS = 60_000L

        /** 技能回放：每步感知+动作 ~2s，步数受 brain 侧 maxSteps=30 约束。 */
        private const val SKILL_RUN_RPC_TIMEOUT_MS = 120_000L
    }
}