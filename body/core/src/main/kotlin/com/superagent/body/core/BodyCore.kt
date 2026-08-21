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
import com.superagent.body.core.perception.PerceptionRoute
import com.superagent.body.core.perception.VisionCaptureResult
import com.superagent.body.core.perception.guardedVisionCapture
import com.superagent.body.core.perception.perceptionRoute
import com.superagent.body.core.skills.SkillStore
import com.superagent.body.core.speech.SpeechEngine
import com.superagent.body.core.speech.SpeechUnavailable
import com.superagent.body.core.speech.VoiceConfig
import com.superagent.body.core.vision.VisionActionContextRegistry
import com.superagent.body.core.vision.bindVisionActionContext
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
import kotlinx.serialization.json.doubleOrNull
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
    private val sensitiveSession = com.superagent.body.core.security.SensitiveSessionTracker()
    private val selector = OptionSelector(perceiver, controller, sensitiveSession)
    private val speech = SpeechEngine(context).also {
        // AD-12：barge-in 事件 → EventBus → brain（"用户打断了播报"）
        it.onBargeInEvent = {
            events.emit("voice", buildJsonObject { put("kind", "barge_in") })
        }
    }
    private val voiceLoop = com.superagent.body.core.voice.VoiceLoop(context, events)
    private val hardware = HardwareService(context)
    private val hitl = Hitl(context, events)
    private val skills = SkillStore(File(context.filesDir, "skills"), perceiver, selector, controller, events, sensitiveSession)
    /** ME-1/ME-3b 记忆与归档（docs/15）：body 侧 SQLite 权威存储（memory.db：memories+runs 两表） */
    private val memoryDb = com.superagent.body.core.memory.AndroidSqliteMemoryDb(context)
    private val memories = com.superagent.body.core.memory.MemoryStore(memoryDb)
    private val runArchive = com.superagent.body.core.memory.RunArchiveStore(memoryDb)
    private val commands = com.superagent.body.core.memory.CommandStore(
        com.superagent.body.core.memory.AndroidSqliteCommandDb(memoryDb as android.database.sqlite.SQLiteOpenHelper),
    )
    private val blobsDir = File(context.filesDir, "blobs")
    private val screenshots = com.superagent.body.core.screenshot.ScreenshotService(context).also {
        com.superagent.body.core.screenshot.ScreenshotService.shared = it
    }
    private val visionActionContexts = VisionActionContextRegistry()
    /** AD-11：唯一动作执行入口——RPC 与回放共用，安全闸门不分散 */
    val actionExecutor = com.superagent.body.core.control.ActionExecutor(perceiver, controller, selector, sensitiveSession)
    private val server = BodyServer(events, blobsDir)
    /** BD-11：看门狗——a11y 断连检测/通知/事件上报 */
    private val watchdog = com.superagent.body.core.reliability.Watchdog(context, events) { a11y() != null }
    private val started = AtomicBoolean(false)

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        // UI-0：body 是 UI 唯一 owner——悬浮层经 UiBus 订阅同源事件（docs/05 §6.1）
        com.superagent.body.core.ui.UiBus.events = events
        // AD-11：回放与 RPC 共用唯一动作执行入口
        skills.executor = actionExecutor
        registerHandlers()
        watchdog.start()
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
        watchdog.stop()
        server.shutdown() // C-10：取消全部在途执行（结构化 scope）
        server.stopAndWait()
    }

    private fun registerHandlers() {
        server.rpc("perceive.screen") { req ->
            if (a11y() == null) {
                return@rpc RpcResponse.failure(req.id, "A11Y_DISCONNECTED", "无障碍服务未连接，请在设置中开启", "a11y")
            }
            val mode = req.params?.jsonObject?.get("mode")?.toString()?.trim('"') ?: "auto"
            // L1 视觉（BD-02.2）+ L2 auto 路由（BD-02.3）：a11y 节点不足或含 WebView 时自动 fallback 视觉
            if (mode == "vision" || mode == "auto") {
                // 第一次强制刷新：auto 路由与显式 vision 都不得相信 300ms 内的旧前台包。
                val scanned = perceiver.perceive(
                    mode = "a11y",
                    inSensitiveSession = sensitiveSession.inSensitiveSession,
                    forceRefresh = true,
                )
                sensitiveSession.onForeground(scanned.appPackage)
                val freshScreen = scanned.copy(sensitiveSession = sensitiveSession.inSensitiveSession)
                when (
                    perceptionRoute(
                        mode = mode,
                        a11yScreen = freshScreen,
                        hasWebView = perceiver.lastScanHasWebView,
                        inSensitiveSession = sensitiveSession.inSensitiveSession,
                    )
                ) {
                    PerceptionRoute.UseA11y -> return@rpc ok(req, freshScreen)
                    PerceptionRoute.VisionBlocked -> {
                        return@rpc RpcResponse.failure(req.id, "VISION_BLOCKED", "敏感会话或前台应用未知，禁止视觉导出", "privacy")
                    }
                    PerceptionRoute.UseVision -> Unit
                }

                // UX-10：截图前隐藏全部 overlay（OverlayGate），留一帧视图生效余量后采集。
                com.superagent.body.core.ui.OverlayGate.hide()
                try {
                    try {
                        Thread.sleep(180)
                    } catch (_: InterruptedException) {
                    }
                    // 第二次强制刷新紧邻 capture，关闭 overlay settle 期间切入敏感 App 的 TOCTOU 窗口。
                    when (
                        val captured = guardedVisionCapture(
                            freshScan = {
                                perceiver.perceive(
                                    mode = "a11y",
                                    inSensitiveSession = sensitiveSession.inSensitiveSession,
                                    forceRefresh = true,
                                )
                            },
                            synchronizeForeground = sensitiveSession::onForeground,
                            isSensitive = { sensitiveSession.inSensitiveSession },
                            capture = { runCatching { screenshots.capture(blobsDir) }.getOrNull() },
                        )
                        ) {
                        is VisionCaptureResult.Blocked ->
                            return@rpc RpcResponse.failure(req.id, "VISION_BLOCKED", "敏感会话或前台应用未知，禁止视觉导出", "privacy")
                        is VisionCaptureResult.Completed -> {
                            val capture = captured.capture ?: return@rpc ok(req, captured.screen)
                            val visionScreen = captured.screen.copy(
                                kind = "vision",
                                marks = null,
                                nodes = null,
                                pageTexts = null,
                                screenshotRef = capture.ref,
                                screenWidth = capture.screenWidth,
                                screenHeight = capture.screenHeight,
                                screenshotWidth = capture.screenshotWidth,
                                screenshotHeight = capture.screenshotHeight,
                            )
                            return@rpc ok(
                                req,
                                bindVisionActionContext(
                                    screen = visionScreen,
                                    screenshotRef = capture.ref,
                                    screenWidth = capture.screenWidth,
                                    screenHeight = capture.screenHeight,
                                    registry = visionActionContexts,
                                ),
                            )
                        }
                    }
                } finally {
                    com.superagent.body.core.ui.OverlayGate.restore()
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
            execAction(req, com.superagent.body.core.control.ActionExecutor.Action.Tap(x, y))
        }

        server.rpc("control.longPress") { req ->
            val p = params(req)
            val x = p.int("x") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 x")
            val y = p.int("y") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 y")
            execAction(req, com.superagent.body.core.control.ActionExecutor.Action.LongPress(x, y, p.int("durationMs")?.toLong() ?: 600L))
        }

        server.rpc("control.swipe") { req ->
            val p = params(req)
            val fromX = p.int("fromX") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 fromX")
            val fromY = p.int("fromY") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 fromY")
            val toX = p.int("toX") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 toX")
            val toY = p.int("toY") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 toY")
            execAction(req, com.superagent.body.core.control.ActionExecutor.Action.Swipe(fromX, fromY, toX, toY, p.int("durationMs")?.toLong() ?: 300L))
        }

        server.rpc("control.typeText") { req ->
            val text = params(req).string("text") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 text")
            execAction(req, com.superagent.body.core.control.ActionExecutor.Action.TypeText(text))
        }

        server.rpc("control.selectOption") { req ->
            val p = params(req)
            val label = p.string("label") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 label")
            val near = p.near()
            execAction(req, com.superagent.body.core.control.ActionExecutor.Action.Select(label, near?.x, near?.y))
        }

        server.rpc("control.selectSpec") { req ->
            val p = params(req)
            val label = p.string("label") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 label")
            val near = p.near()
            execAction(req, com.superagent.body.core.control.ActionExecutor.Action.SelectSpec(label, near?.x, near?.y))
        }

        server.rpc("control.back") { req -> execAction(req, com.superagent.body.core.control.ActionExecutor.Action.Back) }
        server.rpc("control.home") { req -> execAction(req, com.superagent.body.core.control.ActionExecutor.Action.Home) }

        server.rpc("control.launch") { req ->
            val pkg = params(req).string("pkg") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 pkg")
            execAction(req, com.superagent.body.core.control.ActionExecutor.Action.Launch(pkg))
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
                        // DS-014：华为 logcat 裁剪后台线程 → 错误写文件持久化
                        runCatching {
                            java.io.File(context.filesDir, "tts-error.log")
                                .appendText("[${System.currentTimeMillis()}] say FAILED ${e.javaClass.simpleName}: ${e.message}\n")
                        }
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

        // BD-04 在线播报主路：brain 端 edge/azure 合成的 MP3 字节（base64），内存播放零落盘
        server.rpc("speech.playBytes") { req ->
            val audioB64 = params(req).string("audio") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 audio")
            // codex-P1-06：非法 base64 在 RPC 线程抛异常会 500——转 BAD_PARAMS
            val bytes = try {
                android.util.Base64.decode(audioB64, android.util.Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return@rpc bad(req, "BAD_PARAMS", "audio 非法 base64")
            }
            if (bytes.size !in 512..(5 * 1024 * 1024)) return@rpc bad(req, "BAD_PARAMS", "音频大小非法: ${bytes.size}B")
            val result = speech.playAudioBytes(bytes)
            ok(req, result)
        }

        // TC-12 验收：播放预生成的音频文件（filesDir/audio-*.mp3）
        server.rpc("speech.playFile") { req ->
            val file = params(req).string("file") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 file")
            // codex-P1-06：只允许 basename（拒绝 ../ 与路径分隔符，防越出 filesDir）
            if (file.contains('/') || file.contains('\\') || file.contains("..")) {
                return@rpc bad(req, "BAD_PARAMS", "file 只允许文件名（不允许路径）")
            }
            val audioFile = java.io.File(context.filesDir, file)
            if (!audioFile.isFile) return@rpc bad(req, "NOT_FOUND", "音频文件不存在: $file")
            Thread {
                runCatching {
                    val mp = android.media.MediaPlayer()
                    mp.setDataSource(audioFile.absolutePath)
                    mp.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    mp.prepare()
                    mp.start()
                    mp.setOnCompletionListener { it.release() }
                }.onFailure { Log.e("BodyCore", "playFile failed", it) }
            }.start()
            ok(req, SayResult("speaker"))
        }

        // DS-014 诊断：TTS 引擎状态（哪个可用、active 是哪个）
        server.rpc("speech.status") { req ->
            val status = speech.ttsStatus()
            ok(req, buildJsonObject {
                put("sherpaReady", status["sherpaReady"] == true)
                put("systemTtsReady", status["systemTtsReady"] == true)
                put("activeEngine", status["activeEngine"]?.toString() ?: "unknown")
            })
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

        // ME-1 记忆四件套（docs/15 §4）：写入路径在 brain（remember 工具/reflect），body 只管存储与合并语义
        server.rpc("memory.write") { req ->
            val p = params(req)
            val kind = p.string("kind") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 kind")
            val topic = p.string("topic") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 topic")
            val content = p.string("content") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 content")
            val source = p.string("source") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 source")
            runCatching {
                memories.write(kind, topic, content, source, (p.json.get("confidence")?.jsonPrimitive?.doubleOrNull ?: 0.5))
            }.fold(
                { ok(req, it) },
                { e -> bad(req, if (e is IllegalArgumentException) "BAD_PARAMS" else "MEMORY_STORE", e.message ?: "写入失败") },
            )
        }

        server.rpc("memory.search") { req ->
            val p = params(req)
            val query = p.string("query") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 query")
            ok(req, memories.search(query, p.int("limit") ?: 5))
        }

        server.rpc("memory.revise") { req ->
            val p = params(req)
            val id = p.long("id") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 id")
            val content = p.string("content") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 content")
            runCatching { memories.revise(id, content, p.string("source")) }
                .fold(
                    { found -> if (found) emptyOk(req) else bad(req, "NOT_FOUND", "记忆条目不存在: $id") },
                    { e -> bad(req, if (e is IllegalArgumentException) "BAD_PARAMS" else "MEMORY_STORE", e.message ?: "修订失败") },
                )
        }

        server.rpc("memory.forget") { req ->
            val id = params(req).long("id") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 id")
            if (memories.forget(id)) emptyOk(req) else bad(req, "NOT_FOUND", "记忆条目不存在: $id")
        }

        // ME-8 备份导出（docs/15 §7）：全量含 revoked——brain 定期拉快照落 Termux（"不丢记忆"补丁）
        server.rpc("memory.export") { req ->
            ok(req, memories.exportAll())
        }

        // ME-8 恢复：补缺语义（只插 body 缺失的 active 组合，不覆盖不动 revoked；PII 同 write 红线）
        server.rpc("memory.import") { req ->
            val entries = params(req).json.get("entries")?.let {
                runCatching { json.decodeFromJsonElement<List<com.superagent.common.MemoryEntry>>(it) }.getOrNull()
            } ?: return@rpc bad(req, "BAD_PARAMS", "缺少/非法 entries")
            if (entries.size > 5000) return@rpc bad(req, "BAD_PARAMS", "entries 过多: ${entries.size}")
            runCatching { memories.importEntries(entries) }
                .fold(
                    { ok(req, it) },
                    { e -> bad(req, "MEMORY_STORE", e.message ?: "导入失败") },
                )
        }

        // ME-6 生命周期维护（docs/15 §8.2）：衰减 + 容量治理——brain 月度触发
        server.rpc("memory.maintain") { req ->
            ok(req, memories.maintain())
        }

        // ── S6 command journal RPC（GPT 冻结设计 + 裁决最小面；GLM 注册）──
        server.rpc("command.reserve") { req ->
            val p = params(req)
            val kind = p.string("kind") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 kind")
            val text = p.string("text") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 text")
            // 注意：入参 text 为明文（调用方在 UI 进程内），入库前 protect；本地拒绝不入 journal
            when (val r = commands.reserve(kind, text)) {
                is com.superagent.body.core.memory.CommandStore.ReserveOutcome.Queued ->
                    ok(req, com.superagent.common.CommandReserveReceipt(commandId = r.commandId))
                is com.superagent.body.core.memory.CommandStore.ReserveOutcome.LocallyRejected ->
                    ok(req, com.superagent.common.CommandReserveReceipt(reason = r.reason))
            }
        }
        server.rpc("command.claimNext") { req ->
            val p = params(req)
            val cid = p.string("commandId") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 commandId")
            val session = p.string("brainSessionId") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 brainSessionId")
            when (val r = commands.claimNext(cid, session, p.long("leaseMs") ?: 60_000)) {
                is com.superagent.body.core.memory.CommandStore.ClaimOutcome.Claimed ->
                    ok(req, com.superagent.common.CommandClaimEnvelope(claimed = true, protectedText = r.text))
                is com.superagent.body.core.memory.CommandStore.ClaimOutcome.Rejected ->
                    ok(req, com.superagent.common.CommandClaimEnvelope(claimed = false, reason = r.reason))
            }
        }
        server.rpc("command.accept") { req ->
            val p = params(req)
            val cid = p.string("commandId") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 commandId")
            val taskId = p.string("taskId") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 taskId")
            val session = p.string("brainSessionId") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 brainSessionId")
            val okRes = commands.bindTask(cid, taskId, session)
            ok(req, com.superagent.common.CommandSettleResult(ok = okRes, reason = if (okRes) null else "状态/会话不匹配"))
        }
        server.rpc("command.settle") { req ->
            val p = params(req)
            val cid = p.string("commandId") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 commandId")
            val session = p.string("brainSessionId") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 brainSessionId")
            val status = p.string("status") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 status")
            val st = com.superagent.body.core.memory.CommandStatus.fromWire(status)
                ?: return@rpc bad(req, "BAD_PARAMS", "status 非法: $status")
            // D2：settle 入口顺带惰性清扫
            commands.sweepExpired()
            val okRes = commands.settle(cid, p.string("taskId"), session, st)
            ok(req, com.superagent.common.CommandSettleResult(ok = okRes, reason = if (okRes) null else "状态/会话/任务不匹配"))
        }
        server.rpc("command.list") { req ->
            // D2：list 入口惰性清扫（过期 QUEUED→REJECTED / CLAIMED→INTERRUPTED）
            commands.sweepExpired()
            val p = params(req)
            ok(req, com.superagent.common.CommandListResult(commands.list(p.long("afterRowId") ?: 0, p.int("limit") ?: 50)))
        }

        // ME-3b 情景层全量归档（docs/15 §3）：run 快照 SQLite 全量留存（不环形淘汰）
        server.rpc("run.archive") { req ->
            val p = params(req)
            val goal = p.string("goal") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 goal")
            val outcome = p.string("outcome") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 outcome")
            val trace = p.json.get("trace")?.let {
                runCatching { json.decodeFromJsonElement<List<com.superagent.common.TraceStep>>(it) }.getOrNull()
            } ?: emptyList()
            val now = System.currentTimeMillis()
            runCatching {
                runArchive.archive(
                    goal, outcome, p.string("failureReason"), trace,
                    p.long("startedAt") ?: now, p.long("finishedAt") ?: now,
                    p.int("memoriesInjected") ?: 0,
                )
            }.fold(
                { ok(req, it) },
                { e -> bad(req, if (e is IllegalArgumentException) "BAD_PARAMS" else "MEMORY_STORE", e.message ?: "归档失败") },
            )
        }

        server.rpc("run.list") { req -> ok(req, runArchive.list(params(req).int("limit") ?: 30)) }

        server.rpc("hitl.confirm", HITL_RPC_TIMEOUT_MS) { req ->
            val p = params(req)
            val prompt = p.string("prompt") ?: return@rpc bad(req, "BAD_PARAMS", "缺少 prompt")
            val nonce = p.string("nonce")
            // AD-10：nonce 优先——一次性消费（绑原始动作+前台包名+时间窗），不可伪造
            val approved: Boolean
            if (nonce != null) {
                val label = sensitiveSession.consumeNonce(nonce)
                if (label == null) {
                    return@rpc ok(req, com.superagent.common.HitlConfirmResult(false))
                }
                // 服务端规范化文案（不信任 brain 自拟 prompt 承载授权语义）
                val canonicalPrompt = "确认在 ${sensitiveSession.currentApp} 执行「$label」？"
                approved = hitl.confirm(canonicalPrompt)
                if (approved) sensitiveSession.approve(label)
            } else {
                // 无 nonce 回退 action 路径（过渡兼容，后续收窄）
                val action = p.string("action")
                approved = hitl.confirm(prompt)
                if (approved && action != null) sensitiveSession.approve(action)
            }
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

        // UI-0（docs/12 §7 / CT-06）：brain 回灌事件通道——类型化 BrainEvent（契约入 contract.json），
        // 悬浮层订阅渲染。body 保持 UI 唯一 owner；状态机去重排序由 UI 层做。
        server.rpc("brain.event") { req ->
            val accepted = BrainEventIngress.accept(req.params) { event ->
                events.emit("brain", json.encodeToJsonElement(event))
            }
            if (!accepted) return@rpc bad(req, "BAD_PARAMS", "BrainEvent 缺字段或不合法（以 contract.json 为准）")
            emptyOk(req)
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

    /** codex-P0-01 收编：全部 control.* RPC 统一经 ActionExecutor（闸门+执行+签名+事件一个不落） */
    private suspend fun execAction(
        req: com.superagent.common.RpcRequest,
        action: com.superagent.body.core.control.ActionExecutor.Action,
    ): RpcResponse =
        when (val r = actionExecutor.execute(action)) {
            is com.superagent.body.core.control.ActionExecutor.Result.GateBlocked -> gateFailure(req, r.violation)
            is com.superagent.body.core.control.ActionExecutor.Result.Failed -> ok(req, ActionResult(false, null, r.reason))
            is com.superagent.body.core.control.ActionExecutor.Result.Ok -> ok(req, r.actionResult)
        }

    private fun gateFailure(req: com.superagent.common.RpcRequest, v: com.superagent.body.core.security.ActionGate.Violation): RpcResponse =
        when (v) {
            is com.superagent.body.core.security.ActionGate.Violation.Commit ->
                RpcResponse.failure(req.id, "COMMIT_BOUNDARY", "「${v.label}」是提交边界动作，不可绕过（转 hitl）", v.reason)
            is com.superagent.body.core.security.ActionGate.Violation.SensitiveSession ->
                RpcResponse.failure(
                    req.id, "COMMIT_BOUNDARY",
                    "敏感会话内动作「${v.label}」需人工确认。hitl.confirm 时必须把 nonce 传入 nonce 参数。",
                    v.reason, v.nonce,
                )
        }

    private class Params(private val obj: JsonObject) {
        val json: JsonObject get() = obj
        fun string(key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull
        fun int(key: String): Int? = obj[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        fun long(key: String): Long? = obj[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
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
