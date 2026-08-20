package com.superagent.body.core.control

import com.superagent.body.core.security.SensitiveSessionTracker
import com.superagent.common.CommitBoundaryGuard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * ActionExecutor 闸门判定纯逻辑测试（不依赖 Android——验证 label 级安全检查）。
 */
class ActionExecutorGateTest {

    private val sensitive = SensitiveSessionTracker()

    @Test
    fun `commit boundary 标签应被拦截`() {
        assertTrue(CommitBoundaryGuard.isCommitBoundary("立即支付"))
        assertTrue(CommitBoundaryGuard.isCommitBoundary("确认支付"))
        assertTrue(CommitBoundaryGuard.isCommitBoundary("提交订单"))
        assertTrue(CommitBoundaryGuard.isCommitBoundary("确认下单"))
    }

    @Test
    fun `正常操作词不被拦截`() {
        assertFalse(CommitBoundaryGuard.isCommitBoundary("加入购物车"))
        assertFalse(CommitBoundaryGuard.isCommitBoundary("立即购买"))
        assertFalse(CommitBoundaryGuard.isCommitBoundary("搜索"))
        assertFalse(CommitBoundaryGuard.isCommitBoundary("返回"))
    }

    @Test
    fun `敏感会话内动作词需 extra confirm`() {
        sensitive.onForeground("com.tencent.mm") // 微信=敏感 App
        assertTrue(sensitive.inSensitiveSession)
        // "发送" 是 sensitiveSessionActionVerbs——敏感会话内需确认
        assertTrue(sensitive.needsExtraConfirm("发送"))
        assertTrue(sensitive.needsExtraConfirm("删除"))
        assertTrue(sensitive.needsExtraConfirm("转账"))
    }

    @Test
    fun `非敏感会话内动作词不需 extra confirm`() {
        sensitive.onForeground("com.android.settings") // 设置=非敏感
        assertFalse(sensitive.inSensitiveSession)
        assertFalse(sensitive.needsExtraConfirm("发送"))
    }

    @Test
    fun `approve 后单次放行`() {
        sensitive.onForeground("com.tencent.mm")
        assertTrue(sensitive.needsExtraConfirm("发送"))
        sensitive.approve("发送")
        assertFalse(sensitive.needsExtraConfirm("发送"), "第一次消费")
        assertTrue(sensitive.needsExtraConfirm("发送"), "第二次恢复拦截")
    }

    @Test
    fun `nonce 完整生命周期`() {
        sensitive.onForeground("com.tencent.mm")
        val nonce = sensitive.issueNonce("转账")
        val label = sensitive.consumeNonce(nonce)
        assertEquals("转账", label)
        // 重放
        assertNull(sensitive.consumeNonce(nonce))
    }

    @Test
    fun `learn-time 校验拒绝 commit boundary 步`() {
        // 直接测试 CommitBoundaryGuard（ActionExecutor.validateLearnStep 内部调用它）
        assertTrue(CommitBoundaryGuard.isCommitBoundary("立即支付"))
    }

    @Test
    fun `URL 敏感检测`() {
        assertTrue(CommitBoundaryGuard.isSensitiveUrl("https://example.com/pay"))
        assertTrue(CommitBoundaryGuard.isSensitiveUrl("https://example.com/checkout"))
        assertTrue(CommitBoundaryGuard.isSensitiveUrl("https://cashier.example.com"))
        assertFalse(CommitBoundaryGuard.isSensitiveUrl("https://example.com/home"))
    }

    @Test
    fun `敏感 App 注册表`() {
        assertTrue(CommitBoundaryGuard.isSensitiveApp("com.tencent.mm"))
        assertTrue(CommitBoundaryGuard.isSensitiveApp("com.eg.android.AlipayGphone"))
        assertTrue(CommitBoundaryGuard.isSensitiveApp("com.tencent.mm.ui.chat")) // 子包名
        assertFalse(CommitBoundaryGuard.isSensitiveApp("com.android.settings"))
    }
}
