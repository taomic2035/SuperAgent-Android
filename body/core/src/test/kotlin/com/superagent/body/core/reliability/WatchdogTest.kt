package com.superagent.body.core.reliability

import com.superagent.body.core.events.EventBus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BD-11 看门狗 JVM 单测——a11y 断连检测/事件上报/升级逻辑。
 * 用手动触发 check() 代替 30s 定时器；Context 用 null（notifyUser 内 runCatching 兜底）。
 */
class WatchdogTest {

    private lateinit var events: EventBus
    private lateinit var captured: MutableList<Pair<String, String?>>
    private var a11yConnected = true

    @BeforeEach
    fun setup() {
        events = EventBus()
        captured = mutableListOf()
        events.addListener { type, payload -> captured.add(type to payload) }
        a11yConnected = true
    }

    private fun createWatchdog(): TestableWatchdog {
        return TestableWatchdog(events, { a11yConnected })
    }

    /** 测试子类：跳过 Context/通知（JVM 无 Android），只测事件链 */
    class TestableWatchdog(
        events: EventBus,
        a11yCheck: () -> Boolean,
    ) : Watchdog(null, events, a11yCheck) {
        override fun notifyUserForTest(message: String) { /* no-op in JVM test */ }
    }

    @Test
    fun `a11y 正常时不产生事件`() {
        a11yConnected = true
        val wd = createWatchdog()
        wd.checkForTest()
        assertEquals(0, captured.size, "a11y 正常时不应产生事件")
    }

    @Test
    fun `a11y 断连第一次产生 disconnected 事件`() {
        a11yConnected = false
        val wd = createWatchdog()
        wd.checkForTest()
        assertTrue(captured.any { it.second?.contains("a11y_disconnected") == true },
            "第一次断连应产生 a11y_disconnected")
    }

    @Test
    fun `连续断连升级为 persistent_failure`() {
        a11yConnected = false
        val wd = createWatchdog()
        wd.checkForTest()
        wd.checkForTest()
        wd.checkForTest()
        assertTrue(captured.any { it.second?.contains("a11y_persistent_failure") == true },
            "连续 ≥2 次应升级")
    }

    @Test
    fun `恢复后计数重置`() {
        a11yConnected = false
        val wd = createWatchdog()
        wd.checkForTest()
        wd.checkForTest()
        a11yConnected = true
        wd.checkForTest()
        a11yConnected = false
        wd.checkForTest()
        val disconnects = captured.filter { it.second?.contains("a11y_disconnected") == true }
        assertEquals(2, disconnects.size, "恢复后再断连应从第 1 次重新计数")
    }
}
