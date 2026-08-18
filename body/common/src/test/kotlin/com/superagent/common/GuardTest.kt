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
}
