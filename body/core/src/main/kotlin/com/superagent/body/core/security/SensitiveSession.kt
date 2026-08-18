package com.superagent.body.core.security

import com.superagent.common.CommitBoundaryGuard

/**
 * 敏感 App 注册表 + 敏感会话追踪（BD-08.1）。
 *
 * 进入敏感 App（银行/支付/社交）后，body 进入"敏感会话"状态：
 * - 提交边界词仍由 CommitBoundaryGuard 拦截（硬红线）
 * - 敏感会话内额外对"确认/提交/转账/发送/删除"类动作强制 HITL
 *   （这些词太泛不宜全局拦，但在银行 App 里就该问一声）
 *
 * 非提交动作（浏览、查询）不受限，体验与安全兼顾。
 */
object SensitiveAppRegistry {
    private val SENSITIVE_PREFIXES = setOf(
        // 银行
        "com.chinamworld", "com.ccb", "com.icbc", "com.abchina", "com.bankcomm",
        "com.cmbchina", "com.chinamobile.boce", "com.spdb", "com.cebbank",
        "com.citic", "com.cgb", "com.pab", "com.epay", "com.bankofchina",
        // 支付
        "com.eg.android.AlipayGphone", "com.tencent.mm", "com.unionpay",
        // 社交（发送消息为提交边界）
        "com.tencent.mobileqq", "com.tencent.qqlive", "com.sina.weibo",
        "com.ss.android.article", "com.netease.mail",
    )

    fun isSensitiveApp(pkg: String): Boolean =
        SENSITIVE_PREFIXES.any { pkg == it || pkg.startsWith("$it.") }
}

/** 敏感会话内的额外确认动作词（太泛不宜全局拦，仅在敏感 App 内生效） */
private val SENSITIVE_SESSION_ACTION_VERBS = listOf(
    "确认", "提交", "转账", "发送", "删除", "修改密码", "实名认证",
)

class SensitiveSessionTracker {
    @Volatile
    var currentApp: String = ""
        private set

    @Volatile
    var inSensitiveSession: Boolean = false
        private set

    fun onLaunch(pkg: String) {
        currentApp = pkg
        inSensitiveSession = SensitiveAppRegistry.isSensitiveApp(pkg)
    }

    /** 在敏感会话内，该 label 是否需要额外 HITL（提交边界词已由 Guard 拦，此处补泛词） */
    fun needsExtraConfirm(label: String): Boolean {
        if (!inSensitiveSession) return false
        if (CommitBoundaryGuard.isCommitBoundary(label)) return false // 已被硬拦，不重复
        val normalized = label.replace(Regex("\\s+"), "")
        return SENSITIVE_SESSION_ACTION_VERBS.any { normalized.contains(it) }
    }
}