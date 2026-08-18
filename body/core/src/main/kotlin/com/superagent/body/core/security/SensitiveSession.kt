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

    fun onLaunch(pkg: String) {
        currentApp = pkg
        inSensitiveSession = SensitiveAppRegistry.isSensitiveApp(pkg)
    }

    fun onHome() {
        inSensitiveSession = false
    }

    fun needsExtraConfirm(label: String): Boolean {
        if (!inSensitiveSession) return false
        if (CommitBoundaryGuard.isCommitBoundary(label)) return false
        return CommitBoundaryGuard.isSensitiveSessionAction(label)
    }
}
