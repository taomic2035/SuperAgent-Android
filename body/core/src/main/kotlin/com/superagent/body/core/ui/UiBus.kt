package com.superagent.body.core.ui

import com.superagent.body.core.events.EventBus

/**
 * UI-0（docs/05 §6.1 铁律）：body 是 UI 唯一 owner——悬浮层（app 进程）经此总线拿 EventBus，
 * 与 brain 回灌的 BrainEvent 同源订阅；brain 永不直接碰 UI。
 *
 * #37 readiness 契约（GPT 授权修复 2026-08-21）：
 * - observeEvents：锁内「读当前 + 注册等待」原子——已就绪立即回调，后续 publish 必达（无丢唤醒）
 * - 返回退订 handle（onDestroy 注销——销毁后迟到 publish 不得再触发）
 * - clear(expected)：值匹配才清——旧 Core 不得清掉替换后的新 bus
 * - 旧字段 events 兼容（快照语义；写入走 publish 含唤醒）
 */
object UiBus {

    private val lock = Any()
    private var bus: EventBus? = null
    private val waiters = mutableListOf<(EventBus) -> Unit>()

    /** 兼容旧读取（快照）；BodyCore 改经 publish 发布（含唤醒等待者） */
    @JvmStatic
    var events: EventBus?
        get() = synchronized(lock) { bus }
        internal set(value) { publishInternal(value) }

    /** U2-H04：通知兜底访问当前 UI 状态（BodyService 构建通知时读取） */
    @Volatile
    var stateController: UiStateController? = null

    /** BodyCore 启动发布 bus（唤醒全部等待者）；重复 publish 以新替旧（body 重启场景）。 */
    fun publish(newBus: EventBus) = publishInternal(newBus)

    private fun publishInternal(newBus: EventBus?) {
        val toWake: List<(EventBus) -> Unit>
        synchronized(lock) {
            bus = newBus
            toWake = if (newBus != null) waiters.toList() else emptyList()
            waiters.clear()
        }
        // 锁外回调（回调内可能再进 UiBus——防死锁）
        if (newBus != null) toWake.forEach { runCatching { it(newBus) } }
    }

    /**
     * 注册 readiness：已就绪立即回调；否则入等待队列（publish 必达，无 1s 超时丢唤醒）。
     * 返回退订函数——服务 onDestroy 注销，销毁后迟到 publish 不再触发该回调。
     */
    fun observeEvents(callback: (EventBus) -> Unit): () -> Unit {
        synchronized(lock) {
            bus?.let {
                runCatching { callback(it) }
                return {}
            }
            waiters.add(callback)
            return {
                synchronized(lock) {
                    waiters.remove(callback)
                }
            }
        }
    }

    /** 服务销毁清理：仅当当前 bus 即 expected 才清（旧 Core 不得清掉替换后的新 bus）。 */
    fun clear(expected: EventBus) {
        synchronized(lock) {
            if (bus === expected) {
                bus = null
                waiters.clear()
            }
        }
    }
}
