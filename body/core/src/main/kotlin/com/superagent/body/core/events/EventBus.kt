package com.superagent.body.core.events

import com.superagent.common.BodyEvent
import com.superagent.common.JsonElement
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** 短轮询事件队列（GET /events?since=）。seq 单调递增，最近 512 条保留。 */
class EventBus {
    private val seq = AtomicLong(0)
    private val queue = ConcurrentLinkedQueue<BodyEvent>()

    fun emit(type: String, payload: JsonElement? = null) {
        val event = BodyEvent(seq.incrementAndGet(), type, payload)
        queue.add(event)
        while (queue.size > 512) queue.poll()
    }

    fun poll(since: Long): List<BodyEvent> = queue.filter { it.seq > since }
}