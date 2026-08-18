package com.superagent.body.core.perception

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.superagent.common.A11yNode
import com.superagent.common.Bounds
import com.superagent.common.Mark
import com.superagent.common.Point
import com.superagent.common.ScreenResult
import java.security.MessageDigest

/** 支付敏感词（与 common.PaymentGuard 一致的双保险：感知阶段打标，点选阶段拦截）。 */
private val SENSITIVE_WORDS = listOf("支付", "付款", "收银台", "输密码", "验证码")

class ScreenPerceiver(private val accessibilityService: () -> AccessibilityService?) {

    fun perceive(mode: String = "auto"): ScreenResult {
        val root = accessibilityService()?.rootInActiveWindow ?: return ScreenResult("", "a11y", true, null, null, null, null)
        val marks = mutableListOf<Mark>()
        val nodes = mutableListOf<A11yNode>()
        val pageTexts = mutableListOf<String>()
        walk(root, marks, nodes, pageTexts, 0)
        if (marks.isEmpty()) {
            return ScreenResult("", "a11y", true, nodes, null, pageTexts, currentPackage(root))
        }
        val signature = signature(marks)
        val appPkg = currentPackage(root)
        return ScreenResult(
            signature = signature,
            kind = "a11y",
            blank = false,
            nodes = nodes,
            marks = marks,
            pageTexts = pageTexts,
            appPackage = appPkg,
        )
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        marks: MutableList<Mark>,
        nodes: MutableList<A11yNode>,
        pageTexts: MutableList<String>,
        depth: Int,
    ) {
        if (depth > 40) return
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val label = when {
            text != null -> text
            desc != null -> desc
            else -> null
        }
        if (label != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val center = Point(rect.centerX(), rect.centerY())
                marks.add(Mark(marks.size, label, center))
                pageTexts.add(label)
                nodes.add(
                    A11yNode(
                        label = label,
                        clickable = node.isClickable,
                        selected = node.isSelected.takeIf { node.isSelected },
                        sensitive = SENSITIVE_WORDS.any { label.contains(it) },
                        bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                    ),
                )
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, marks, nodes, pageTexts, depth + 1)
            child.recycle()
        }
    }

    private fun currentPackage(root: AccessibilityNodeInfo): String? =
        root.packageName?.toString()

    companion object {
        fun signature(marks: List<Mark>): String {
            val sb = StringBuilder()
            for (m in marks) sb.append(m.text).append('@').append(m.center.x).append(',').append(m.center.y).append(';')
            return sha1(sb.toString()).take(12)
        }

        fun sha1(input: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}