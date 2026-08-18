package com.superagent.common

private val PAYMENT_TERMS = listOf(
    "支付", "付款", "立即支付", "确认支付", "去支付", "收银台",
    "输密码", "验证码支付", "支付密码", "指纹支付", "面容支付", "免密支付",
)

object PaymentGuard {
    fun isPaymentTarget(label: String): Boolean {
        val normalized = label.replace(Regex("\\s+"), "")
        return PAYMENT_TERMS.any { normalized.contains(it) }
    }
}