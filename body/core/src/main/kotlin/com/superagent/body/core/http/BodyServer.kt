package com.superagent.body.core.http

import com.superagent.body.core.BodyContext
import com.superagent.body.core.PROTOCOL_VERSION
import com.superagent.body.core.events.EventBus
import com.superagent.common.JsonElement
import com.superagent.common.RpcRequest
import com.superagent.common.RpcResponse
import com.superagent.common.json
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

typealias Handler = suspend (request: RpcRequest) -> RpcResponse

/**
 * 躯体 HTTP 服务（NanoHTTPD，纯 Kotlin/Java 无外置依赖）：
 * - POST /rpc   JSON-RPC（Bearer 认证 + 幂等键缓存）
 * - GET  /health 健康检查
 * - GET  /events 短轮询事件
 * 认证失败一律 401 + JSON-RPC error；denial is data。
 */
class BodyServer(
    private val events: EventBus,
    /** 截图 blob 存放目录（视觉感知 L1，GET /blob/{id}） */
    private val blobDir: java.io.File,
) : NanoHTTPD(BodyContext.settings.host, BodyContext.settings.port) {

    private val handlers = ConcurrentHashMap<String, Handler>()
    private val handlerTimeouts = ConcurrentHashMap<String, Long>()
    private val idempotentResults = ConcurrentHashMap<String, RpcResponse>()
    private val idempotentOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()
    /** 审计 P1-03：同 key 并发执行预留（共享首个执行结果，防重复手势/输入/启动） */
    private val idempotentInFlight = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<RpcResponse>>()

    /** C-10（docs/16 §8）：结构化 scope 替代 GlobalScope——服务生命周期可整体取消 */
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 服务销毁时取消全部在途执行（BodyService.onDestroy 调用）。 */
    fun shutdown() {
        serverScope.cancel()
    }

    fun on(method: String, handler: Handler) {
        handlers[method] = handler
    }

    fun rpc(method: String, handler: Handler) = on(method, handler)

    /** 注册长耗时方法（HITL 等待用户、录音、技能回放）。超时必须 > handler 内部等待上限，否则用户响应到达前 RPC 已被判死。 */
    fun rpc(method: String, timeoutMs: Long, handler: Handler) {
        handlers[method] = handler
        handlerTimeouts[method] = timeoutMs
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.method.name.uppercase()) {
                "GET" -> handleGet(session)
                "POST" -> handlePost(session)
                else -> jsonResponse(RpcResponse.failure(0, "METHOD_NOT_ALLOWED", session.method.name), 405)
            }
        } catch (e: Exception) {
            jsonResponse(RpcResponse.failure(0, "INTERNAL", e.message ?: "internal error"), 500)
        }
    }

    private fun handleGet(session: IHTTPSession): Response {
        if (!authorized(session)) {
            return jsonResponse(RpcResponse.failure(0, "UNAUTHORIZED", "token 无效"), 401)
        }
        val path = session.uri.removePrefix("/").trimEnd('/')
        return when {
            // P1-02 修复（审计）：真实路由是 /blob/{id}——精确匹配 "blob" 永远落 NOT_FOUND（mock 自带路由所以 smoke 没抓到）
            path.startsWith("blob/") -> {
                val id = path.removePrefix("blob/")
                if (id.isBlank() || id.contains('/') || id.contains('\\') || id.contains("..")) {
                    jsonResponse(RpcResponse.failure(0, "BAD_REQUEST", "非法 blob id"), 400)
                } else {
                    val file = java.io.File(blobDir, id)
                    if (!file.exists()) {
                        jsonResponse(RpcResponse.failure(0, "NOT_FOUND", "blob 不存在: $id"), 404)
                    } else {
                        val res = newFixedLengthResponse(
                            Response.Status.lookup(200), "image/jpeg",
                            file.inputStream(), file.length(),
                        )
                        res.addHeader("Cache-Control", "no-store")
                        res
                    }
                }
            }
            path == "health" -> jsonResponse(
                RpcResponse.success(
                    0,
                    json.encodeToJsonElement(
                        com.superagent.common.HealthStatus(
                            ok = true,
                            bootId = BodyContext.bootId,
                            protocolVersion = PROTOCOL_VERSION,
                            uptimeMs = BodyContext.uptimeMs(),
                            services = healthServices(),
                        ),
                    ),
                ),
            )
            path == "events" -> {
                val since = session.parameters["since"]?.firstOrNull()?.toLongOrNull() ?: 0L
                jsonResponse(RpcResponse.success(0, json.encodeToJsonElement(events.poll(since))))
            }
            else -> jsonResponse(RpcResponse.failure(0, "NOT_FOUND", session.uri), 404)
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        if (!authorized(session)) {
            return jsonResponse(RpcResponse.failure(0, "UNAUTHORIZED", "token 无效"), 401)
        }
        val body = readBody(session)
            ?: return jsonResponse(RpcResponse.failure(0, "BAD_REQUEST", "请求体缺失或超过上限 ${MAX_BODY_BYTES / 1024 / 1024}MB"), 413)
        val request = try {
            json.decodeFromString<RpcRequest>(body)
        } catch (e: Exception) {
            return jsonResponse(RpcResponse.failure(0, "BAD_REQUEST", "JSON 解析失败: ${e.message}"), 400)
        }
        val key = request.idempotencyKey
        if (key != null) {
            idempotentResults[key]?.let { return jsonResponse(it) }
        }
        val handler = handlers[request.method]
            ?: return jsonResponse(RpcResponse.failure(request.id, "UNKNOWN_METHOD", "未知方法: ${request.method}"))
        // 审计 P1-03 + C-10：同 key 并发/瞬时重试共享同一次执行；执行体在 serverScope 结构化运行，
        // HTTP 线程带超时等待——超时即回 TIMEOUT（reason=unknown_side_effect，引导先 perceive 核实），
        // 但执行继续跑完：真实结果写入幂等缓存，同 key 重试拿到第一次执行的真实结果而非二次执行。
        if (key != null) {
            val newFuture = java.util.concurrent.CompletableFuture<RpcResponse>()
            val existing = idempotentInFlight.putIfAbsent(key, newFuture)
            if (existing != null) {
                val shared = runCatching { existing.get(35, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
                    ?: RpcResponse.failure(request.id, "TIMEOUT", "同 key 请求仍在执行（>35s）", "unknown_side_effect")
                return jsonResponse(shared)
            }
            val timeoutMs = handlerTimeouts[request.method] ?: DEFAULT_HANDLER_TIMEOUT_MS
            serverScope.launch {
                var response: RpcResponse? = null
                try {
                    // 执行体给 handler 超时 + 5s 余量：慢动作最终完成并落缓存（幂等闭环）
                    response = withTimeoutOrNull(timeoutMs + 5_000L) {
                        runCatching { handler(request) }.getOrElse { e ->
                            RpcResponse.failure(request.id, "BODY_ERROR", e.message ?: "handler error")
                        }
                    } ?: RpcResponse.failure(request.id, "TIMEOUT", "handler 执行超时（服务侧取消）", "unknown_side_effect")
                } finally {
                    // S5/C10 复核（GPT）：结果必须先对新请求可见，再解除 in-flight——
                    // 此前 remove(key) 先于 putIfAbsent，窗口期同 key 请求两处都看不到会二次执行副作用。
                    // 顺序保证：put 缓存 → complete（等待者放行）→ remove in-flight，窗口关闭。
                    val final = response ?: RpcResponse.failure(request.id, "BODY_ERROR", "handler 未产出结果")
                    if (idempotentResults.putIfAbsent(key, final) == null) {
                        idempotentOrder.add(key)
                        while (idempotentOrder.size > MAX_IDEMPOTENT_ENTRIES) {
                            val oldest = idempotentOrder.poll() ?: break
                            idempotentResults.remove(oldest)
                        }
                    }
                    newFuture.complete(final)
                    idempotentInFlight.remove(key, newFuture)
                }
            }
            val awaited = runCatching { newFuture.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
                ?: RpcResponse.failure(
                    request.id, "TIMEOUT",
                    "handler 超时——副作用可能已发生：同 key 重试会等待并返回第一次执行的真实结果；切勿换 key 盲重试，先 perceive.screen 核实现场",
                    "unknown_side_effect",
                )
            return jsonResponse(awaited)
        }
        val response = dispatch(handler, request)
        return jsonResponse(response)
    }

    /** C-10：无幂等键路径——结构化超时（withTimeoutOrNull 真取消，替代 latch 只停等待）。 */
    private fun dispatch(handler: Handler, request: RpcRequest): RpcResponse {
        val timeoutMs = handlerTimeouts[request.method] ?: DEFAULT_HANDLER_TIMEOUT_MS
        return kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(timeoutMs) {
                runCatching { handler(request) }.getOrElse { e ->
                    RpcResponse.failure(request.id, "BODY_ERROR", e.message ?: "handler error")
                }
            } ?: RpcResponse.failure(
                request.id, "TIMEOUT",
                "handler 超时——副作用可能已发生，重试前先 perceive.screen 核实现场",
                "unknown_side_effect",
            )
        }
    }

    /** 审计 P1-04：请求体上限（拒绝超大/负数/缺失 Content-Length，防持 token 客户端内存耗尽）。 */
    private fun readBody(session: IHTTPSession): String? {
        val length = session.headers.get("content-length")?.toIntOrNull() ?: -1
        if (length < 0 || length > MAX_BODY_BYTES) return null
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(buffer, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return buffer.toString(Charsets.UTF_8)
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val header = session.headers.get("authorization")
        return header == "Bearer ${BodyContext.settings.token}"
    }

    private fun jsonResponse(body: RpcResponse, status: Int = 200): Response {
        val response = newFixedLengthResponse(
            Response.Status.lookup(status),
            "application/json; charset=utf-8",
            json.encodeToString(body),
        )
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun healthServices(): Map<String, Boolean> =
        handlers.keys.map { it.substringBefore('.') }.toSet().associateWith { true }

    override fun start() {
        super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    companion object {
        const val DEFAULT_HANDLER_TIMEOUT_MS = 30_000L
        private const val MAX_IDEMPOTENT_ENTRIES = 512

        /** 审计 P1-04：单请求体上限 10MB（skill.learn 大轨迹也远够） */
        private const val MAX_BODY_BYTES = 10 * 1024 * 1024
    }

    @Throws(IOException::class)
    fun stopAndWait() {
        stop()
    }
}