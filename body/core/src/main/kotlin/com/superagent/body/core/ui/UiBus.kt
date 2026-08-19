package com.superagent.body.core.ui

import com.superagent.body.core.events.EventBus

/**
 * UI-0（docs/05 §6.1 铁律）：body 是 UI 唯一 owner——悬浮层（app 进程）经此总线拿 EventBus，
 * 与 brain 回灌的 BrainEvent 同源订阅；brain 永不直接碰 UI。
 */
object UiBus {
    @Volatile
    var events: EventBus? = null
        internal set
}
