package com.superagent.body.core.security

import com.superagent.body.core.perception.ScreenPerceiver
import com.superagent.common.CommitBoundaryGuard

/**
 * 坐标动作统一闸门（审计 P0-02/03/04 修复，2026-08-19）：
 * RPC 的 control.tap/longPress 与 skill.run 回放的 tap 步**共用**此判定，
 * 不再各自为政。检查**全部**包含落点的节点（父容器先命中不遮挡子按钮——
 * 审计 P0-03：firstOrNull 会被普通文案的父容器顶掉"提交订单"子节点）。
 */
object ActionGate {

    sealed class Violation(val label: String, val reason: String) {
        class Commit(text: String) : Violation(text, "commit")
        /** AD-10：携带一次性 nonce——hitl.confirm 必须回传此值才能放行 */
        class SensitiveSession(text: String, val nonce: String) : Violation(text, "sensitive_session")
    }

    /**
     * 落点 (x,y) 是否命中提交边界或敏感会话动作。
     * - 任一包含节点命中提交边界词 → Commit（坐标点击不可绕过）
     * - 敏感会话内任一包含节点命中敏感动作词 → SensitiveSession（需 hitl.confirm action 放行）
     * - 感知失败/无节点 → null 放行（闸门不因感知故障误伤正常操控）
     */
    fun violatingLabel(perceiver: ScreenPerceiver, sensitive: SensitiveSessionTracker, x: Int, y: Int): Violation? {
        val nodes = runCatching { perceiver.perceive("a11y").nodes }.getOrNull() ?: return null
        val hits = nodes.filter { it.bounds.left <= x && x <= it.bounds.right && it.bounds.top <= y && y <= it.bounds.bottom }
        if (hits.isEmpty()) return null
        hits.firstOrNull { CommitBoundaryGuard.isCommitBoundary(it.label) }
            ?.let { return Violation.Commit(it.label) }
        hits.firstOrNull { sensitive.needsExtraConfirm(it.label) }
            ?.let { return Violation.SensitiveSession(it.label, sensitive.issueNonce(it.label)) }
        return null
    }
}
