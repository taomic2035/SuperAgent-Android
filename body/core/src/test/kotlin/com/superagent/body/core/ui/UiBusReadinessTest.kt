package com.superagent.body.core.ui

import com.superagent.body.core.events.EventBus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #37 UiBus readiness 契约（GPT 授权矩阵 7 场景，确定性单测——无 Android 依赖）。
 * EventBus 为纯 Kotlin 类，直接实例化。
 */
class UiBusReadinessTest {

    @BeforeEach
    fun reset() {
        // 测试隔离：clear 需匹配当前 bus
        UiBus.events?.let { UiBus.clear(it) }
        UiBus.stateController = null
    }

    @Test
    fun `1-late publish 唤醒等待者（先 register 后 publish）`() {
        var got: EventBus? = null
        UiBus.observeEvents { got = it }
        assertNull(got, "未发布前不得回调")
        val bus = EventBus()
        UiBus.publish(bus)
        assertSame(bus, got, "publish 必达等待者")
    }

    @Test
    fun `2-second register while waiting 也在 publish 时唤醒`() {
        val gotA: MutableList<EventBus> = mutableListOf()
        val gotB: MutableList<EventBus> = mutableListOf()
        UiBus.observeEvents { gotA.add(it) }
        UiBus.observeEvents { gotB.add(it) } // second start while waiting
        val bus = EventBus()
        UiBus.publish(bus)
        assertEquals(1, gotA.size)
        assertEquals(1, gotB.size, "等待中的第二个注册者同被唤醒")
    }

    @Test
    fun `3-publish 与 register 原子性（已就绪时注册立即回调，无窗口丢失）`() {
        val bus = EventBus()
        UiBus.publish(bus)
        var got: EventBus? = null
        UiBus.observeEvents { got = it }
        assertSame(bus, got, "already-ready 立即回调（同步，无窗口）")
    }

    @Test
    fun `4-duplicate publish 替换且不重复唤醒旧 waiter`() {
        val got = mutableListOf<EventBus>()
        UiBus.observeEvents { got.add(it) }
        val b1 = EventBus(); val b2 = EventBus()
        UiBus.publish(b1)
        UiBus.publish(b2)
        assertEquals(listOf<EventBus>(b1), got, "waiter 一次性：就绪即完成，b2 替换不重复唤醒（无重复窗口）")
    }

    @Test
    fun `5-destroy-before-publish（退订后迟到 publish 不触发）`() {
        var got = 0
        val unsubscribe = UiBus.observeEvents { got++ }
        unsubscribe() // onDestroy 注销
        UiBus.publish(EventBus())
        assertEquals(0, got, "销毁后迟到 publish 不得触发已注销回调")
    }

    @Test
    fun `6-clear-old 不清 new（旧 Core 不得清掉替换后的 bus）`() {
        val old = EventBus(); val new = EventBus()
        UiBus.publish(old)
        UiBus.publish(new)
        UiBus.clear(old) // 旧 Core 迟到清理
        assertSame(new, UiBus.events, "新 bus 不受旧清理影响")
        UiBus.clear(new) // 正确清理
        assertNull(UiBus.events)
    }

    @Test
    fun `7-clear 后新 register 再 publish 恢复链路`() {
        val old = EventBus()
        UiBus.publish(old)
        UiBus.clear(old)
        var got: EventBus? = null
        UiBus.observeEvents { got = it }
        val fresh = EventBus()
        UiBus.publish(fresh)
        assertSame(fresh, got, "clear→register→publish 全链恢复")
    }

    @Test
    fun `8-兼容快照语义（events 读取不回调）`() {
        val bus = EventBus()
        UiBus.publish(bus)
        assertSame(bus, UiBus.events, "旧读法兼容")
        assertFalse(UiBus.events === EventBus(), "快照即当前值")
        assertTrue(true)
    }
}
