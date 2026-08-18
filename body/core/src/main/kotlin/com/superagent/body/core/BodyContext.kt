package com.superagent.body.core

import android.content.Context
import java.security.SecureRandom

/** protocolVersion：brain↔body 契约版本（docs/07 定义），不匹配返回 PROTOCOL_MISMATCH */
const val PROTOCOL_VERSION = 2

/**
 * 随机 token 安全闭合（ADR-3）：
 * 首启生成 256bit 随机 token 存 filesDir/token（App沙箱私有，他App不可读）。
 * Termux 经 `adb shell run-as com.superagent.body cat files/token` 桥接读取。
 * 调试默认 token 仅模拟器构建存在（BuildConfig.DEBUG && emulator 检测由 app 层做）。
 */
object TokenSecurity {
    private const val TOKEN_FILE = "token"
    private const val TOKEN_HEX_LENGTH = 64 // 256bit = 32 bytes = 64 hex chars

    fun loadOrGenerate(context: Context): String {
        val file = java.io.File(context.filesDir, TOKEN_FILE)
        if (file.exists()) {
            val existing = file.readText().trim()
            if (existing.length == TOKEN_HEX_LENGTH) return existing
        }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        file.writeText(token)
        return token
    }
}

data class BodySettings(
    val port: Int = 8765,
    val token: String = "",
    val host: String = "127.0.0.1",
)

object BodyContext {
    lateinit var app: Context
        private set
    lateinit var settings: BodySettings
        private set
    val bootId: String get() = bootIdValue
    val startedAt: Long get() = startedAtValue

    private var bootIdValue: String = ""
    private var startedAtValue: Long = 0

    fun init(app: Context, settings: BodySettings) {
        this.app = app.applicationContext
        this.settings = settings
        bootIdValue = "body-${System.currentTimeMillis()}"
        startedAtValue = System.currentTimeMillis()
    }

    fun uptimeMs(): Long = System.currentTimeMillis() - startedAtValue
}