package com.superagent.common

/**
 * 提交边界词表（docs/07 §2.2 v2）。
 * 从"内容子串匹配"升级为"短语精确包含"：仅多字短语，避免误伤"支付宝/付款码/支付方式说明"。
 */
private val COMMIT_PHRASES = listOf(
    "立即支付", "确认支付", "立即付款", "确认付款",
    "提交订单", "确认下单", "立即下单",
    "支付密码", "验证码支付", "指纹支付", "面容支付", "免密支付",
    "输密码", "确认收货",
)

/** 导航到敏感页（不拦截，但标记页面进入敏感会话） */
private val SENSITIVE_NAV_PHRASES = listOf(
    "去支付", "去结算", "收银台",
)

object CommitBoundaryGuard {
    /** 该动作是否跨过提交边界（不可逆外部效应）→ 必须拦截转 HITL */
    fun isCommitBoundary(label: String): Boolean {
        val normalized = label.replace(Regex("\\s+"), "")
        return COMMIT_PHRASES.any { normalized.contains(it) }
    }

    /** 该文字是否指向敏感页（导航动作，不拦截但标记） */
    fun isSensitiveContext(label: String): Boolean {
        val normalized = label.replace(Regex("\\s+"), "")
        return SENSITIVE_NAV_PHRASES.any { normalized.contains(it) } || isCommitBoundary(normalized)
    }
}