package com.superagent.common

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardTest {
    @Test
    fun `payment terms hit`() {
        assertTrue(PaymentGuard.isPaymentTarget("立即支付"))
        assertTrue(PaymentGuard.isPaymentTarget("收银台"))
        assertTrue(PaymentGuard.isPaymentTarget("确认支付"))
        assertTrue(PaymentGuard.isPaymentTarget("去 支付"))
    }

    @Test
    fun `non payment terms pass`() {
        assertFalse(PaymentGuard.isPaymentTarget("加入购物车"))
        assertFalse(PaymentGuard.isPaymentTarget("立即购买"))
        assertFalse(PaymentGuard.isPaymentTarget("订单详情"))
    }
}