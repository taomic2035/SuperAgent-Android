package com.superagent.body.core.http

import com.superagent.body.core.BodyContext
import com.superagent.body.core.PROTOCOL_VERSION
import com.superagent.body.core.events.EventBus
import com.superagent.common.JsonElement
import com.superagent.common.RpcRequest
import com.superagent.common.RpcResponse
import com.superagent.common.json
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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
) : NanoHTTPD(BodyContext.settings.host, BodyContext.settings.port) {

    private val handlers = ConcurrentHashMap<String, Handler>()
    private val idempotentResults = ConcurrentHashMap<String, RpcResponse>()

    fun on(method: String, handler: Handler) {
        handlers[method] = handler
    }

    fun rpc(method: String, handler: Handler) = on(method, handler)

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
        return when (session.uri.removePrefix("/").trimEnd('/')) {
            "health" -> jsonResponse(
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
            "events" -> {
                val since = session.parameters["since"]?.firstOrNull()?.toLongOrNull() ?: 0L
                jsonResponse(RpcResponse.success(0, json.encodeToJsonElement(events.poll(since))))
            }
            "blob" -> jsonResponse(RpcResponse.failure(0, "BLOB_UNSUPPORTED", "/blob 通道 P1 实现"), 200)
            else -> jsonResponse(RpcResponse.failure(0, "NOT_FOUND", session.uri), 404)
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        if (!authorized(session)) {
            return jsonResponse(RpcResponse.failure(0, "UNAUTHORIZED", "token 无效"), 401)
        }
        val body = readBody(session)
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
        val response = runBlocking(handler, request)
        if (key != null && response.ok) {
            idempotentResults[key] = response
            if (idempotentResults.size > 512) {
                val oldest = idempotentResults.keys.firstOrNull()
                if (oldest != null) idempotentResults.remove(oldest)
            }
        }
        return jsonResponse(response)
    }

    private fun runBlocking(handler: Handler, request: RpcRequest): RpcResponse {
        var result: RpcResponse? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        GlobalScope.launch(Dispatchers.Default) {
            try {
                result = handler(request)
            } catch (e: Exception) {
                result = RpcResponse.failure(request.id, "BODY_ERROR", e.message ?: "handler error")
            } finally {
                latch.countDown()
            }
        }
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        return result ?: RpcResponse.failure(request.id, "TIMEOUT", "handler 超时")
    }

    private fun readBody(session: IHTTPSession): String {
        val length = session.headers.get("content-length")?.toIntOrNull() ?: 0
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

    @Throws(IOException::class)
    fun stopAndWait() {
        stop()
    }
}