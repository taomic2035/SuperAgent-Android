package com.superagent.common

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuardTest {
    @Test
    fun `commit boundary phrases hit`() {
        assertTrue(CommitBoundaryGuard.isCommitBoundary("立即支付"))
        assertTrue(CommitBoundaryGuard.isCommitBoundary("确认支付"))
        assertTrue(CommitBoundaryGuard.isCommitBoundary("提交订单"))
        assertTrue(CommitBoundaryGuard.isCommitBoundary("确认下单"))
        assertTrue(CommitBoundaryGuard.isCommitBoundary("支付密码"))
        assertTrue(CommitBoundaryGuard.isCommitBoundary("立 即 支 付"))
    }

    @Test
    fun `navigation phrases mark but do not intercept`() {
        assertFalse(CommitBoundaryGuard.isCommitBoundary("去支付"))
        assertFalse(CommitBoundaryGuard.isCommitBoundary("去结算"))
        assertFalse(CommitBoundaryGuard.isCommitBoundary("收银台"))
        assertTrue(CommitBoundaryGuard.isSensitiveContext("去支付"))
        assertTrue(CommitBoundaryGuard.isSensitiveContext("收银台"))
    }

    @Test
    fun `non commit phrases pass without false positive`() {
        assertFalse(CommitBoundaryGuard.isCommitBoundary("加入购物车"))
        assertFalse(CommitBoundaryGuard.isCommitBoundary("立即购买"))
        assertFalse(CommitBoundaryGuard.isCommitBoundary("支付宝"))
        assertFalse(CommitBoundaryGuard.isCommitBoundary("付款码"))
        assertFalse(CommitBoundaryGuard.isCommitBoundary("支付方式说明"))
        assertFalse(CommitBoundaryGuard.isCommitBoundary("订单详情"))
    }

    @Test
    fun `sensitive session action verbs detected`() {
        assertTrue(CommitBoundaryGuard.isSensitiveSessionAction("确认转账"))
        assertTrue(CommitBoundaryGuard.isSensitiveSessionAction("提交申请"))
        assertTrue(CommitBoundaryGuard.isSensitiveSessionAction("发送消息"))
        assertTrue(CommitBoundaryGuard.isSensitiveSessionAction("删除记录"))
        assertFalse(CommitBoundaryGuard.isSensitiveSessionAction("查看详情"))
        assertFalse(CommitBoundaryGuard.isSensitiveSessionAction("浏览商品"))
    }

    @Test
    fun `sensitive url patterns detected`() {
        assertTrue(CommitBoundaryGuard.isSensitiveUrl("https://shop.example.com/pay/confirm"))
        assertTrue(CommitBoundaryGuard.isSensitiveUrl("https://shop.example.com/checkout"))
        assertTrue(CommitBoundaryGuard.isSensitiveUrl("https://m.shop.com/收银台"))
        assertFalse(CommitBoundaryGuard.isSensitiveUrl("https://shop.example.com/product/123"))
        assertFalse(CommitBoundaryGuard.isSensitiveUrl("https://shop.example.com/cart"))
    }

    @Test
    fun `sensitive app prefixes detected`() {
        assertTrue(CommitBoundaryGuard.isSensitiveApp("com.icbc"))
        assertTrue(CommitBoundaryGuard.isSensitiveApp("com.icbc.iphone"))
        assertTrue(CommitBoundaryGuard.isSensitiveApp("com.eg.android.AlipayGphone"))
        assertFalse(CommitBoundaryGuard.isSensitiveApp("com.example.shop"))
        assertFalse(CommitBoundaryGuard.isSensitiveApp("com.icbcx"))
    }

    @Test
    fun `boundaries loaded from json`() {
        val b = CommitBoundaryGuard.getBoundaries()
        assertTrue(b.commitPhrases.contains("立即支付"))
        assertTrue(b.sensitiveNavPhrases.contains("收银台"))
        assertTrue(b.sensitiveAppPrefixes.contains("com.icbc"))
        assertTrue(b.sensitiveUrlPatterns.contains("checkout"))
        assertTrue(b.commitPhrases.size >= 10)
        assertTrue(b.sensitiveAppPrefixes.size >= 10)
    }

    // ---- 以下为 2026-08-19 文档一致性任务 T6 新增（只增不改）----

    @Test
    fun `sensitive url mixed case and remaining patterns`() {
        // 大小写混合：实现先 lowercase 再子串匹配
        assertTrue(CommitBoundaryGuard.isSensitiveUrl("https://shop.example.com/PAY/confirm"))
        assertTrue(CommitBoundaryGuard.isSensitiveUrl("https://shop.example.com/CheckOut"))
        assertTrue(CommitBoundaryGuard.isSensitiveUrl("https://shop.example.com/CaShIeR"))
        assertTrue(CommitBoundaryGuard.isSensitiveUrl("https://m.bank.com/Payment"))
        // 中文模式：结算 / 收银
        assertTrue(CommitBoundaryGuard.isSensitiveUrl("https://m.shop.com/结算中心"))
        assertTrue(CommitBoundaryGuard.isSensitiveUrl("https://m.shop.com/?page=收银"))
        // 无关 URL 不命中
        assertFalse(CommitBoundaryGuard.isSensitiveUrl("https://news.example.com/article/123"))
        assertFalse(CommitBoundaryGuard.isSensitiveUrl("https://docs.example.com/guide/index"))
    }

    @Test
    fun `sensitive app exact subpackage and lookalike`() {
        // 精确匹配
        assertTrue(CommitBoundaryGuard.isSensitiveApp("com.tencent.mm"))
        assertTrue(CommitBoundaryGuard.isSensitiveApp("com.unionpay"))
        // 前缀 + 子包名（pkg == prefix || pkg.startsWith("$prefix.")）
        assertTrue(CommitBoundaryGuard.isSensitiveApp("com.tencent.mm.ui"))
        assertTrue(CommitBoundaryGuard.isSensitiveApp("com.eg.android.AlipayGphone.ui.pay"))
        // 相似包名不误伤：前缀后必须跟 "." 边界
        assertFalse(CommitBoundaryGuard.isSensitiveApp("com.tencent.mmhelper"))
        assertFalse(CommitBoundaryGuard.isSensitiveApp("com.example.mm.ui"))
    }

    @Test
    fun `commit and session action predicates independent`() {
        // needsExtraConfirm（body 侧 SensitiveSession.kt:42-47）分层依赖这两个谓词：
        // commit 词走硬拦截路径（reason=commit），敏感会话动作走二次确认路径（reason=sensitive_session）。
        // 仅提交边界：硬拦截词不含敏感会话动词，任何上下文都拦
        assertTrue(CommitBoundaryGuard.isCommitBoundary("立即支付"))
        assertFalse(CommitBoundaryGuard.isSensitiveSessionAction("立即支付"))
        // 仅敏感会话动作：非提交词，只在敏感会话内需额外确认
        assertFalse(CommitBoundaryGuard.isCommitBoundary("发送消息"))
        assertTrue(CommitBoundaryGuard.isSensitiveSessionAction("发送消息"))
        assertFalse(CommitBoundaryGuard.isCommitBoundary("修改密码"))
        assertTrue(CommitBoundaryGuard.isSensitiveSessionAction("修改密码"))
        // 双命中："确认收货"是 commit 词且含"确认"——needsExtraConfirm 对 commit 词直接放行给
        // 硬拦截路径处理，两个谓词各自独立成立、语义不混淆
        assertTrue(CommitBoundaryGuard.isCommitBoundary("确认收货"))
        assertTrue(CommitBoundaryGuard.isSensitiveSessionAction("确认收货"))
        // 双阴性：普通浏览动作
        assertFalse(CommitBoundaryGuard.isCommitBoundary("查看详情"))
        assertFalse(CommitBoundaryGuard.isSensitiveSessionAction("查看详情"))
        // 空白归一化：带空格的提交词仍命中
        assertTrue(CommitBoundaryGuard.isCommitBoundary("确 认 收 货"))
    }
}
