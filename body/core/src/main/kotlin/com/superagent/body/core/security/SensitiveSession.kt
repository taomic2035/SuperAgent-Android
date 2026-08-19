package com.superagent.body.core.security

import com.superagent.common.CommitBoundaryGuard

object SensitiveAppRegistry {
    fun isSensitiveApp(pkg: String): Boolean =
        CommitBoundaryGuard.isSensitiveApp(pkg)
}

class SensitiveSessionTracker {
    @Volatile
    var currentApp: String = ""
        private set

    @Volatile
    var inSensitiveSession: Boolean = false
        private set

    /** 用户已确认放行的动作标签 → 过期时间戳。 */
    private val approvedLabels = mutableMapOf<String, Long>()

    /** AD-10 nonce 池：nonce → (label, foregroundPkg, createdAtMs)。一次性消费。 */
    private val pendingNonces = mutableMapOf<String, PendingNonce>()

    data class PendingNonce(val label: String, val foregroundPkg: String, val createdAtMs: Long)

    /**
     * 审计 P0-01 修复：以**真实前台包名**同步敏感态（perceive 时调用）——
     * 用户从桌面手动打开银行/聊天应用不再漏判（此前只有 control.launch/home 更新状态）。
     * 会话切换（进/出敏感应用）时清空未消费的批准。
     */
    fun onForeground(pkg: String?) {
        if (pkg == null || pkg == currentApp) return
        val sensitive = SensitiveAppRegistry.isSensitiveApp(pkg)
        if (sensitive != inSensitiveSession) {
            inSensitiveSession = sensitive
            synchronized(approvedLabels) { approvedLabels.clear() }
        }
        currentApp = pkg
    }

    fun onLaunch(pkg: String) {
        onForeground(pkg)
    }

    fun onHome() {
        if (inSensitiveSession) {
            inSensitiveSession = false
            synchronized(approvedLabels) { approvedLabels.clear() }
        }
    }

    /** hitl.confirm 用户同意后按确切标签放行（短时效，**单次消费**——审计 P0-05：不消费可重复放行）。 */
    fun approve(label: String) {
        val normalized = label.replace(Regex("\\s+"), "")
        if (normalized.isEmpty()) return
        synchronized(approvedLabels) {
            approvedLabels[normalized] = System.currentTimeMillis() + APPROVAL_TTL_MS
        }
    }

    /**
     * AD-10：生成一次性 nonce——敏感动作被拒时调用，返回 nonce 字符串。
     * hitl.confirm 必须携带此 nonce，body 侧校验（前台包名未变 + 未过期 + 原子消费）。
     */
    fun issueNonce(label: String): String {
        val nonce = java.util.UUID.randomUUID().toString().take(16)
        val normalized = label.replace(Regex("\\s+"), "")
        synchronized(pendingNonces) {
            // 清理过期 nonce（防膨胀）
            val now = System.currentTimeMillis()
            pendingNonces.entries.removeAll { now - it.value.createdAtMs > NONCE_TTL_MS }
            pendingNonces[nonce] = PendingNonce(normalized, currentApp, now)
        }
        return nonce
    }

    /**
     * AD-10：验证并消费 nonce。返回 label（批准该动作）或 null（无效/过期/前台已切换）。
     * 一次性：验证成功即从池中移除，同 nonce 重放=duplicate 不放行。
     */
    fun consumeNonce(nonce: String): String? {
        synchronized(pendingNonces) {
            val pending = pendingNonces.remove(nonce) ?: return null
            // 过期
            if (System.currentTimeMillis() - pending.createdAtMs > NONCE_TTL_MS) return null
            // 前台已切换（用户切换 App 后旧 nonce 失效）
            if (pending.foregroundPkg != currentApp) return null
            return pending.label
        }
    }

    fun needsExtraConfirm(label: String): Boolean {
        if (!inSensitiveSession) return false
        if (CommitBoundaryGuard.isCommitBoundary(label)) return false
        if (isApproved(label)) return false
        return CommitBoundaryGuard.isSensitiveSessionAction(label)
    }

    /** 单次消费：命中即移除（一次批准只放行一个动作）。 */
    private fun isApproved(label: String): Boolean {
        val normalized = label.replace(Regex("\\s+"), "")
        val now = System.currentTimeMillis()
        synchronized(approvedLabels) {
            val until = approvedLabels[normalized] ?: return false
            if (now >= until) {
                approvedLabels.remove(normalized)
                return false
            }
            approvedLabels.remove(normalized)
            return true
        }
    }

    companion object {
        /** 放行时效：足够模型重试一次，不给长期豁免。 */
        private const val APPROVAL_TTL_MS = 120_000L

        /** AD-10：nonce 有效期（与 APPROVAL_TTL 对齐——足够模型完成 confirm 流程） */
        private const val NONCE_TTL_MS = 120_000L
    }
}
