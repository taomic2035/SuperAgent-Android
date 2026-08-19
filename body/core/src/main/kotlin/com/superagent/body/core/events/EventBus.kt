package com.superagent.body.core.events

import com.superagent.common.BodyEvent
import com.superagent.common.JsonElement
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** 短轮询事件队列（GET /events?since=）。seq 单调递增，最近 512 条保留。
 *  同进程监听（UI-0 悬浮层）：addListener 与 HTTP 轮询同源同报（payload 序列化为 JSON 字符串分发）。 */
class EventBus {
    private val seq = AtomicLong(0)
    private val queue = ConcurrentLinkedQueue<BodyEvent>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String, String?) -> Unit>()

    fun addListener(listener: (type: String, payloadJson: String?) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (type: String, payloadJson: String?) -> Unit) {
        listeners.remove(listener)
    }

    fun emit(type: String, payload: JsonElement? = null) {
        val event = BodyEvent(seq.incrementAndGet(), type, payload)
        queue.add(event)
        while (queue.size > 512) queue.poll()
        if (listeners.isNotEmpty()) {
            val payloadJson = payload?.let { runCatching { kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it) }.getOrNull() }
            listeners.forEach { runCatching { it(type, payloadJson) } }
        }
    }

    fun poll(since: Long): List<BodyEvent> = queue.filter { it.seq > since }
}