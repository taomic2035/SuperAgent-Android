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

    /** 性能：短时缓存——同一屏幕 300ms 内重复 perceive 直接返回（brain 常连发 perceive+act） */
    @Volatile
    private var cachedResult: ScreenResult? = null
    @Volatile
    private var cachedAt = 0L
    @Volatile
    private var cachedKey = ""

    fun perceive(mode: String = "auto", inSensitiveSession: Boolean = false): ScreenResult {
        // 缓存命中：同 mode + 同敏感态 + 300ms 内
        val key = "$mode:$inSensitiveSession"
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = cachedResult
        if (cached != null && cachedKey == key && now - cachedAt < 300 && !cached.blank) {
            return cached
        }

        val root = accessibilityService()?.rootInActiveWindow
            ?: return ScreenResult("", "a11y", true, null, null, null, null, inSensitiveSession)
        val marks = mutableListOf<Mark>()
        val nodes = mutableListOf<A11yNode>()
        val pageTexts = mutableListOf<String>()
        var hasWebView = false
        var webViewSensitive = false
        walk(root, marks, nodes, pageTexts, 0, { hasWebView = true }, { webViewSensitive = true })
        if (marks.isEmpty()) {
            return ScreenResult("", "a11y", true, nodes, null, pageTexts, currentPackage(root), inSensitiveSession)
        }
        if (hasWebView && (pageTexts.any { CommitBoundaryGuard.isSensitiveContext(it) } || webViewSensitive)) {
            nodes.indices.forEach { idx ->
                nodes[idx] = nodes[idx].copy(sensitive = true)
            }
        }
        val signature = signature(marks)
        val appPkg = currentPackage(root)
        val result = ScreenResult(
            signature = signature,
            kind = "a11y",
            blank = false,
            nodes = nodes,
            marks = marks,
            pageTexts = pageTexts,
            appPackage = appPkg,
            sensitiveSession = inSensitiveSession,
        )
        // 缓存写入
        cachedResult = result
        cachedKey = key
        cachedAt = now
        return result
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        marks: MutableList<Mark>,
        nodes: MutableList<A11yNode>,
        pageTexts: MutableList<String>,
        depth: Int,
        onWebView: () -> Unit,
        onWebViewSensitiveUrl: () -> Unit,
    ) {
        if (depth > 40) return
        // 性能：跳过纯布局容器（无文字/无描述/不可见）——减少无效递归
        if (depth > 0 && !node.isVisibleToUser) return
        val className = node.className?.toString() ?: ""
        if (className.contains("WebView", ignoreCase = true)) {
            onWebView()
            checkWebViewUrl(node, onWebViewSensitiveUrl)
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
            walk(child, marks, nodes, pageTexts, depth + 1, onWebView, onWebViewSensitiveUrl)
            child.recycle()
        }
    }

    private fun checkWebViewUrl(node: AccessibilityNodeInfo, onSensitiveUrl: () -> Unit) {
        val bundle = node.extras ?: return
        val url = bundle.getCharSequence("url")?.toString() ?: return
        if (CommitBoundaryGuard.isSensitiveUrl(url)) {
            onSensitiveUrl()
        }
    }

    private fun currentPackage(root: AccessibilityNodeInfo): String? =
        root.packageName?.toString()

    /** 当前屏幕稳定签名（#18 回放校验）；感知失败/空屏返回 null。 */
    fun currentStableSignature(): String? {
        val marks = perceive("a11y").marks ?: return null
        return if (marks.isEmpty()) null else stableSignature(marks)
    }

    /**
     * 等待签名稳定（#24：控制动作返回时页面仍在过渡动画，立刻采样导致学习记录与
     * 回放校验两侧拿到不同过渡态 → 新技能回放恒 stale@1 假阳性）。
     * 连续两次采样相同视为稳定；最多 4 组（约 1.6s，覆盖 EMUI 页面切换动画）。
     */
    fun settledStableSignature(): String? {
        var prev = currentStableSignature()
        repeat(4) {
            Thread.sleep(400)
            val cur = currentStableSignature()
            if (cur != null && cur == prev) return cur
            prev = cur
        }
        return prev
    }

    companion object {
        fun signature(marks: List<Mark>): String {
            // 性能：CRC32（比 SHA-1 快 ~5x，签名只需唯一性不需密码学安全）
            val crc = java.util.zip.CRC32()
            val sb = StringBuilder()
            for (m in marks) sb.append(m.text).append('@').append(m.center.x).append(',').append(m.center.y).append(';')
            crc.update(sb.toString().toByteArray(Charsets.UTF_8))
            return java.lang.Long.toHexString(crc.value).padStart(8, '0').take(12)
        }

        /**
         * 稳定签名（#18 回放校验用）：抹掉数字/时间/百分号类易变文本（状态栏时钟、电量、进度），
         * 坐标按 8px 桶聚合——跨分钟的回放校验不被时钟跳变误判 Stale。
         */
        fun stableSignature(marks: List<Mark>): String {
            val sb = StringBuilder()
            for (m in marks) {
                val text = m.text.replace(Regex("[0-9:%.]+"), "#")
                if (text.isBlank() || text == "#") continue
                sb.append(text).append('@').append(m.center.x shr 3).append(',').append(m.center.y shr 3).append(';')
            }
            return sha1(sb.toString()).take(12)
        }

        fun sha1(input: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
