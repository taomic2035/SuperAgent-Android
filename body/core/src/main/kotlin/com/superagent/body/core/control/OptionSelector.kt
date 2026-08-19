package com.superagent.body.core.control

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.superagent.body.core.perception.ScreenPerceiver
import com.superagent.common.ActionResult
import com.superagent.common.Mark
import com.superagent.common.CommitBoundaryGuard
import com.superagent.common.Point
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 按可见文字定位并点选。
 * 双保险：点选前再次做支付红线校验（即使感知阶段漏标也能拦截）。
 */
class OptionSelector(
    private val perceiver: ScreenPerceiver,
    private val controller: Controller,
) {

    /**
     * 按 label 定位并点击。near 为同文多匹配时的就近消歧参考。
     * 支付词永不点选：返回红线段错误码由服务层转成 PAYMENT_RED_LINE。
     */
    suspend fun select(label: String, near: PointArg? = null, verifySelected: Boolean = false): ActionResult {
        val normalized = label.replace(Regex("\\s+"), "")
        if (CommitBoundaryGuard.isCommitBoundary(normalized)) {
            return ActionResult(false, null, "COMMIT_BOUNDARY")
        }
        val screen = perceiver.perceive("a11y")
        val marks = screen.marks.orEmpty()
        if (screen.blank || marks.isEmpty()) {
            return ActionResult(false, null, "屏幕无文字，无法定位「$label」")
        }
        // #25（2026-08-19 真机实证收紧）：反向包含（查询词包含屏上短文）曾让屏上「更多」
        // 命中查询「更多连接」→ 误点。现仅三通道：精确相等 / 屏文包含查询 / 截断宽容——
        // 截断宽容要求屏文 ≥4 字且覆盖查询 ≥2/3（「立即支付订」仍可命中「立即支付订单」）
        val candidates = marks.filter {
            val t = it.text.replace(Regex("\\s+"), "")
            t == normalized || t.contains(normalized) ||
                (t.length >= 4 && normalized.contains(t) && t.length * 3 >= normalized.length * 2)
        }
        if (candidates.isEmpty()) return ActionResult(false, null, "未找到可见文字「$label」")
        val target = when {
            candidates.size == 1 -> candidates.first()
            near != null -> candidates.minByOrNull { dist(it.center, near) } ?: candidates.first()
            else -> candidates.first()
        }
        if (verifySelected) {
            val before = perceiver.perceive("a11y").signature
            controller.tap(target.center.x, target.center.y)
            delay(300)
            val after = perceiver.perceive("a11y").signature
            if (before == after && after.isNotEmpty()) {
                return ActionResult(false, null, "点选后界面无变化（选中态校验失败）")
            }
        } else {
            controller.tap(target.center.x, target.center.y)
        }
        return ActionResult(true, ScreenPerceiver.signature(listOf(target)))
    }

    private fun dist(a: Point, b: PointArg): Int = abs(a.x - b.x) + abs(a.y - b.y)
}

data class PointArg(val x: Int, val y: Int)