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

    fun onLaunch(pkg: String) {
        currentApp = pkg
        inSensitiveSession = SensitiveAppRegistry.isSensitiveApp(pkg)
        synchronized(approvedLabels) { approvedLabels.clear() }
    }

    fun onHome() {
        inSensitiveSession = false
        synchronized(approvedLabels) { approvedLabels.clear() }
    }

    /** hitl.confirm 用户同意后按确切标签放行一次（短时效，防长期豁免）。 */
    fun approve(label: String) {
        val normalized = label.replace(Regex("\\s+"), "")
        if (normalized.isEmpty()) return
        synchronized(approvedLabels) {
            approvedLabels[normalized] = System.currentTimeMillis() + APPROVAL_TTL_MS
        }
    }

    fun needsExtraConfirm(label: String): Boolean {
        if (!inSensitiveSession) return false
        if (CommitBoundaryGuard.isCommitBoundary(label)) return false
        if (isApproved(label)) return false
        return CommitBoundaryGuard.isSensitiveSessionAction(label)
    }

    private fun isApproved(label: String): Boolean {
        val normalized = label.replace(Regex("\\s+"), "")
        val now = System.currentTimeMillis()
        synchronized(approvedLabels) {
            val until = approvedLabels[normalized] ?: return false
            if (now >= until) {
                approvedLabels.remove(normalized)
                return false
            }
            return true
        }
    }

    companion object {
        /** 放行时效：足够模型重试一次，不给长期豁免。 */
        private const val APPROVAL_TTL_MS = 120_000L
    }
}
