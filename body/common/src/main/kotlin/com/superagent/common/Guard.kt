package com.superagent.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CommitBoundaries(
    val commitPhrases: List<String> = emptyList(),
    val sensitiveNavPhrases: List<String> = emptyList(),
    val sensitiveSessionActionVerbs: List<String> = emptyList(),
    val sensitiveUrlPatterns: List<String> = emptyList(),
    val sensitiveAppPrefixes: List<String> = emptyList(),
)

object CommitBoundaryGuard {
    private val json = Json { ignoreUnknownKeys = true }

    private val _boundaries: CommitBoundaries by lazy {
        loadBoundaries()
    }

    private fun loadBoundaries(): CommitBoundaries {
        val resource = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("commit_boundaries.json")
            ?: return CommitBoundaries(
                commitPhrases = listOf(
                    "立即支付", "确认支付", "立即付款", "确认付款",
                    "提交订单", "确认下单", "立即下单",
                    "支付密码", "验证码支付", "指纹支付", "面容支付", "免密支付",
                    "输密码", "确认收货",
                ),
                sensitiveNavPhrases = listOf("去支付", "去结算", "收银台"),
                sensitiveSessionActionVerbs = listOf("确认", "提交", "转账", "发送", "删除", "修改密码", "实名认证"),
                sensitiveUrlPatterns = listOf("pay", "checkout", "cashier", "收银", "结算", "payment"),
                sensitiveAppPrefixes = listOf(
                    "com.chinamworld", "com.ccb", "com.icbc", "com.abchina", "com.bankcomm",
                    "com.cmbchina", "com.chinamobile.boce", "com.spdb", "com.cebbank",
                    "com.citic", "com.cgb", "com.pab", "com.epay", "com.bankofchina",
                    "com.eg.android.AlipayGphone", "com.tencent.mm", "com.unionpay",
                    "com.tencent.mobileqq", "com.tencent.qqlive", "com.sina.weibo",
                    "com.ss.android.article", "com.netease.mail",
                ),
            )
        val text = resource.bufferedReader(Charsets.UTF_8).readText()
        return json.decodeFromString<CommitBoundaries>(text)
    }

    fun isCommitBoundary(label: String): Boolean {
        val normalized = label.replace(Regex("\\s+"), "")
        return _boundaries.commitPhrases.any { normalized.contains(it) }
    }

    fun isSensitiveContext(label: String): Boolean {
        val normalized = label.replace(Regex("\\s+"), "")
        return _boundaries.sensitiveNavPhrases.any { normalized.contains(it) } || isCommitBoundary(normalized)
    }

    fun isSensitiveSessionAction(label: String): Boolean {
        val normalized = label.replace(Regex("\\s+"), "")
        return _boundaries.sensitiveSessionActionVerbs.any { normalized.contains(it) }
    }

    fun isSensitiveUrl(url: String): Boolean {
        val lower = url.lowercase()
        return _boundaries.sensitiveUrlPatterns.any { lower.contains(it.lowercase()) }
    }

    fun isSensitiveApp(pkg: String): Boolean =
        _boundaries.sensitiveAppPrefixes.any { pkg == it || pkg.startsWith("$it.") }

    fun getBoundaries(): CommitBoundaries = _boundaries
}
