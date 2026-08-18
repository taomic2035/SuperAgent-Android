package com.superagent.body.core.perception

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.superagent.common.A11yNode
import com.superagent.common.Bounds
import com.superagent.common.CommitBoundaryGuard
import com.superagent.common.Mark
import com.superagent.common.Point
import com.superagent.common.ScreenResult
import java.security.MessageDigest

class ScreenPerceiver(private val accessibilityService: () -> AccessibilityService?) {

    fun perceive(mode: String = "auto"): ScreenResult {
        val root = accessibilityService()?.rootInActiveWindow
            ?: return ScreenResult("", "a11y", true, null, null, null, null)
        val marks = mutableListOf<Mark>()
        val nodes = mutableListOf<A11yNode>()
        val pageTexts = mutableListOf<String>()
        var hasWebView = false
        walk(root, marks, nodes, pageTexts, 0, { hasWebView = true })
        if (marks.isEmpty()) {
            return ScreenResult("", "a11y", true, nodes, null, pageTexts, currentPackage(root))
        }
        // WebView 敏感页标记：发现 WebView 且页面含敏感上下文 → 全部节点标 sensitive
        if (hasWebView && pageTexts.any { CommitBoundaryGuard.isSensitiveContext(it) }) {
            nodes.indices.forEach { idx ->
                nodes[idx] = nodes[idx].copy(sensitive = true)
            }
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
        onWebView: () -> Unit,
    ) {
        if (depth > 40) return
        val className = node.className?.toString() ?: ""
        if (className.contains("WebView", ignoreCase = true)) {
            onWebView()
        }
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
                        sensitive = CommitBoundaryGuard.isSensitiveContext(label),
                        bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                    ),
                )
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, marks, nodes, pageTexts, depth + 1, onWebView)
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