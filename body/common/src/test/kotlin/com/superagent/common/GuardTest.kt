package com.superagent.common

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
}