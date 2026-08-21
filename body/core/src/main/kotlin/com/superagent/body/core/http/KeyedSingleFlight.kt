package com.superagent.body.core.http

/**
 * S5/C10 抽取（GPT 复核要求）：keyed single-flight——同 key 请求恰执行一次、结果共享、
 * 超时晚到真实结果仍入缓存（幂等闭环）。不依赖 NanoHTTPD/BodyContext，JVM 可测。
 *
 * 不变量（GPT 复核清单）：
 * 1. 结果先对新请求可见（cache put），再放行等待者（future complete），再解除 in-flight
 * 2. remove 用值匹配（防误删他人 future）
 * 3. timeout/exception fallback 同样成为唯一缓存结果（同 key 重试拿到真实终态而非二次执行）
 */
class KeyedSingleFlight(
    private val cache: MutableMap<String, Any>,
    private val maxEntries: Int,
) {
    init {
        require(cache is java.util.concurrent.ConcurrentMap<*, *>) {
            "KeyedSingleFlight cache must support concurrent enter/complete access"
        }
    }

    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Any>>()
    private val order = java.util.concurrent.ConcurrentLinkedQueue<String>()

    sealed interface Entry<T> {
        /** 执行路径（新 key）：返回执行结果 */
        data class Execute<T>(val future: java.util.concurrent.CompletableFuture<T>) : Entry<T>

        /** 共享路径（同 key 在途）：等待第一次执行的结果 */
        data class Share<T>(val future: java.util.concurrent.CompletableFuture<T>, val timeoutMs: Long) : Entry<T>

        /** 命中缓存 */
        data class Cached<T>(val result: T) : Entry<T>
    }

    /** 查询入口：已有缓存→Cached；在途→Share（按超时等待）；否则占位并返回 Execute（调用方执行后 complete）。 */
    fun <T> enter(key: String): Entry<T> {
        @Suppress("UNCHECKED_CAST")
        cache[key]?.let { return Entry.Cached(it as T) }
        val future = java.util.concurrent.CompletableFuture<Any>()
        val existing = inFlight.putIfAbsent(key, future)
        if (existing != null) {
            @Suppress("UNCHECKED_CAST")
            return Entry.Share(existing as java.util.concurrent.CompletableFuture<T>, shareTimeoutMs)
        }
        @Suppress("UNCHECKED_CAST")
        return Entry.Execute(future as java.util.concurrent.CompletableFuture<T>)
    }

    /** 执行完成：结果入缓存 → 放行等待者 → 解除 in-flight（顺序即不变量 1）。 */
    fun complete(key: String, future: java.util.concurrent.CompletableFuture<Any>, result: Any) {
        // 只有 enter(key) 创建并占位成功的 owner 才能发布结果。迟到/伪造 future
        // 不得抢先污染缓存，更不得掩盖真正 owner 仍在途的事实。
        if (inFlight[key] !== future) return
        if (cache.putIfAbsent(key, result) == null) {
            order.add(key)
            while (order.size > maxEntries) {
                val oldest = order.poll() ?: break
                cache.remove(oldest)
            }
        }
        future.complete(result)
        inFlight.remove(key, future)
    }

    /** 等待者共享超时（对齐 BodyServer 35s 同 key 等待窗）。 */
    var shareTimeoutMs: Long = 35_000

    /** 测试可见性：in-flight 表（仅断言用，生产不得直接操作）。 */
    internal fun inFlightInternal(): java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Any>> = inFlight
}
