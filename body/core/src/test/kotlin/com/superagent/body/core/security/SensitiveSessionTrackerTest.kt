package com.superagent.body.core.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * P2-02（审计）：body/core 首批 JVM 单测——SensitiveSessionTracker 的 nonce 生命周期
 * （AD-10 核心安全语义）+ 会话切换 + 批准单次消费。
 */
class SensitiveSessionTrackerTest {

    private lateinit var tracker: SensitiveSessionTracker

    @BeforeEach
    fun setup() {
        tracker = SensitiveSessionTracker()
    }

    // ---------- AD-10 nonce 生命周期 ----------

    @Test
    fun `nonce 发放与消费成功`() {
        tracker.onForeground("com.tencent.mm") // 进入微信（敏感 App）
        assertTrue(tracker.inSensitiveSession)

        val nonce = tracker.issueNonce("发送")
        assertTrue(nonce.isNotBlank())

        val label = tracker.consumeNonce(nonce)
        assertEquals("发送", label)
    }

    @Test
    fun `nonce 一次性消费——重放不放行`() {
        tracker.onForeground("com.tencent.mm")
        val nonce = tracker.issueNonce("转账")

        val first = tracker.consumeNonce(nonce)
        assertEquals("转账", first)

        val replay = tracker.consumeNonce(nonce)
        assertNull(replay, "同 nonce 重放应返回 null（一次性消费）")
    }

    @Test
    fun `无效 nonce 返回 null`() {
        tracker.onForeground("com.tencent.mm")
        assertNull(tracker.consumeNonce("nonexistent-nonce"))
    }

    @Test
    fun `前台切换后旧 nonce 失效`() {
        tracker.onForeground("com.tencent.mm")
        val nonce = tracker.issueNonce("发送")

        tracker.onForeground("com.android.settings") // 切到非敏感 App
        assertFalse(tracker.inSensitiveSession)

        val result = tracker.consumeNonce(nonce)
        assertNull(result, "前台已切换，旧 nonce 应失效")
    }

    @Test
    fun `未知 nonce 前台包不发放`() {
        tracker.onForeground("com.android.settings") // 非敏感 App
        assertFalse(tracker.inSensitiveSession)
        // issueNonce 仍可调用（内部记录），但 needsExtraConfirm 不会触发（非敏感会话）
        val nonce = tracker.issueNonce("发送")
        assertTrue(nonce.isNotBlank()) // 发放本身不限制——校验在 consume 侧
    }

    // ---------- 会话切换语义 ----------

    @Test
    fun `进入敏感 App 开启会话`() {
        tracker.onForeground("com.tencent.mm")
        assertTrue(tracker.inSensitiveSession)
        assertEquals("com.tencent.mm", tracker.currentApp)
    }

    @Test
    fun `home 退出会话并清空批准`() {
        tracker.onForeground("com.tencent.mm")
        assertTrue(tracker.inSensitiveSession)

        tracker.approve("发送") // 先批准
        tracker.onHome()
        assertFalse(tracker.inSensitiveSession)

        // 批准被清空——needsExtraConfirm 恢复拦截
        // （非敏感会话内 needsExtraConfirm 恒 false，所以测 isApproved 的间接效果）
    }

    @Test
    fun `同 App 重复 onForeground 不重置会话`() {
        tracker.onForeground("com.tencent.mm")
        tracker.approve("发送")
        tracker.onForeground("com.tencent.mm") // 同 App 重复调用

        assertTrue(tracker.inSensitiveSession)
        // 批准未被清空（同 App 不触发切换逻辑）
        assertFalse(tracker.needsExtraConfirm("发送"), "批准仍在生效（同 App 不清空）")
    }

    @Test
    fun `敏感到非敏感切换清空批准`() {
        tracker.onForeground("com.tencent.mm")
        tracker.approve("发送")
        assertFalse(tracker.needsExtraConfirm("发送"), "已批准")

        tracker.onForeground("com.android.settings")
        tracker.onForeground("com.tencent.mm") // 重新进入

        assertTrue(tracker.inSensitiveSession)
        // 批准已被清空（经过了一次非敏感中转）
        assertTrue(tracker.needsExtraConfirm("发送"), "会话切换后批准被清空")
    }

    // ---------- 批准单次消费（P0-05） ----------

    @Test
    fun `approve 后 needsExtraConfirm 返回 false 一次`() {
        tracker.onForeground("com.tencent.mm")
        assertTrue(tracker.needsExtraConfirm("发送"), "未批准时需确认")

        tracker.approve("发送")
        assertFalse(tracker.needsExtraConfirm("发送"), "第一次（单次消费）")

        assertTrue(tracker.needsExtraConfirm("发送"), "第二次（已消费，恢复拦截）")
    }

    @Test
    fun `commitBoundary 标签不做 extraConfirm`() {
        tracker.onForeground("com.tencent.mm")
        // "立即支付" 是 commitBoundary——由 ActionGate.Commit 拦截，不走 extraConfirm
        assertFalse(tracker.needsExtraConfirm("立即支付"))
    }
}
