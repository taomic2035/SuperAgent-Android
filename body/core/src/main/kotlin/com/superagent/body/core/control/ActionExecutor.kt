package com.superagent.body.core.control

import com.superagent.body.core.perception.ScreenPerceiver
import com.superagent.body.core.security.ActionGate
import com.superagent.body.core.security.SensitiveSessionTracker
import com.superagent.common.ActionResult
import com.superagent.common.CommitBoundaryGuard

/**
 * AD-11 ActionExecutor：唯一动作执行入口（审计 P0-04 终态）。
 * RPC 处理器、skill.run 回放、selector——全部经此执行，安全闸门不再分散三处。
 *
 * 流程固定：闸门（ActionGate：提交边界 + 敏感会话 + 全节点检查）→ 执行 → 后置签名 → 事件。
 */
class ActionExecutor(
    private val perceiver: ScreenPerceiver,
    private val controller: Controller,
    private val selector: OptionSelector,
    private val sensitive: SensitiveSessionTracker,
    private val onGateViolation: (ActionGate.Violation) -> Unit = {},
) {
    sealed class Action {
        data class Tap(val x: Int, val y: Int) : Action()
        data class LongPress(val x: Int, val y: Int, val durationMs: Long = 600) : Action()
        data class Swipe(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Long = 300) : Action()
        data class TypeText(val text: String) : Action()
        data class Select(val label: String, val nearX: Int? = null, val nearY: Int? = null) : Action()
        data class SelectSpec(val label: String, val nearX: Int? = null, val nearY: Int? = null) : Action()
        object Back : Action()
        object Home : Action()
        data class Launch(val pkg: String) : Action()
    }

    sealed class Result {
        data class Ok(val actionResult: ActionResult, val stableSignature: String?) : Result()
        data class GateBlocked(val violation: ActionGate.Violation) : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun execute(action: Action): Result {
        // 1. 闸门：坐标类动作统一走 ActionGate（全包含节点检查）
        when (action) {
            is Action.Tap -> gate(action.x, action.y)?.let { return Result.GateBlocked(it) }
            is Action.LongPress -> gate(action.x, action.y)?.let { return Result.GateBlocked(it) }
            is Action.Select -> {
                if (sensitive.needsExtraConfirm(action.label)) {
                    return Result.GateBlocked(ActionGate.Violation.SensitiveSession(action.label))
                }
                if (CommitBoundaryGuard.isCommitBoundary(action.label)) {
                    return Result.GateBlocked(ActionGate.Violation.Commit(action.label))
                }
            }
            is Action.SelectSpec -> {
                if (sensitive.needsExtraConfirm(action.label)) {
                    return Result.GateBlocked(ActionGate.Violation.SensitiveSession(action.label))
                }
                if (CommitBoundaryGuard.isCommitBoundary(action.label)) {
                    return Result.GateBlocked(ActionGate.Violation.Commit(action.label))
                }
            }
            else -> {}
        }

        // 2. 执行
        val raw: ActionResult = when (action) {
            is Action.Tap -> controller.tap(action.x, action.y)
            is Action.LongPress -> controller.longPress(action.x, action.y, action.durationMs)
            is Action.Swipe -> controller.swipe(action.fromX, action.fromY, action.toX, action.toY, action.durationMs)
            is Action.TypeText -> controller.typeText(action.text)
            is Action.Select -> {
                val near = if (action.nearX != null && action.nearY != null) PointArg(action.nearX, action.nearY) else null
                selector.select(action.label, near, verifySelected = false)
            }
            is Action.SelectSpec -> {
                val near = if (action.nearX != null && action.nearY != null) PointArg(action.nearX, action.nearY) else null
                selector.select(action.label, near, verifySelected = true)
            }
            Action.Back -> controller.back()
            Action.Home -> {
                sensitive.onHome()
                controller.home()
            }
            is Action.Launch -> {
                sensitive.onLaunch(action.pkg)
                controller.launch(action.pkg)
            }
        }

        if (!raw.located) return Result.Failed(raw.note ?: "动作未成功")

        // 3. 后置签名（#24 等页面稳定）
        val sig = perceiver.settledStableSignature()
        return Result.Ok(raw.copy(signature = sig), sig)
    }

    /** learn-time 校验：拒绝含提交边界标签的步（审计 P0-04：不接受未经 body 证明的轨迹）。 */
    fun validateLearnStep(tool: String, args: Map<String, String>): Boolean {
        if (tool !in REPLAYABLE) return false
        val label = args["label"] ?: return true
        return !CommitBoundaryGuard.isCommitBoundary(label)
    }

    private fun gate(x: Int, y: Int): ActionGate.Violation? =
        ActionGate.violatingLabel(perceiver, sensitive, x, y)

    companion object {
        val REPLAYABLE = setOf(
            "control.tap", "control.longPress", "control.swipe", "control.typeText",
            "control.selectOption", "control.selectSpec", "control.back", "control.home", "control.launch",
        )
    }
}
