package com.superagent.body.core.reliability

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.superagent.body.core.events.EventBus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * BD-11 常驻看门狗：周期自检 → 自愈 → 死亡上报（对齐 agentos SYS-004 显式死亡事件）。
 * - 每 30s 检查 a11y 连接 / body 服务健康
 * - a11y 断连：emit 事件 + 尝试通知用户（不强制重开——用户主动关闭不应强行打开）
 * - 进程死亡由 START_STICKY + BodyService.isRunning 标志覆盖（华为可能拦，记事件）
 * - 只做健康检查、有限重连和用户通知，不承诺绕过 Android 后台限制
 */
open class Watchdog(
    private val context: Context?,
    private val events: EventBus,
    private val isA11yConnected: () -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var consecutiveA11yFailures = 0
    private var lastHealthyAt = System.currentTimeMillis()

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            check()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
        Log.i(TAG, "watchdog started (interval ${CHECK_INTERVAL_MS / 1000}s)")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(checkRunnable)
    }

    private fun check() {
        val a11yOk = isA11yConnected()
        if (a11yOk) {
            consecutiveA11yFailures = 0
            lastHealthyAt = System.currentTimeMillis()
            return
        }

        consecutiveA11yFailures++
        val downtime = System.currentTimeMillis() - lastHealthyAt

        // 第一轮失败：记录
        if (consecutiveA11yFailures == 1) {
            Log.w(TAG, "a11y 断连（第 1 次，距上次健康 ${downtime / 1000}s）")
            events.emit("state", buildJsonObject {
                put("kind", "a11y_disconnected")
                put("downtimeMs", downtime)
            })
            return
        }

        // 连续 ≥2 次失败（60s+）：升级为 BLOCKED 事件
        if (consecutiveA11yFailures >= 2) {
            Log.e(TAG, "a11y 持续断连（${consecutiveA11yFailures} 次，${downtime / 1000}s）——需用户处理")
            events.emit("state", buildJsonObject {
                put("kind", "a11y_persistent_failure")
                put("failures", consecutiveA11yFailures)
                put("downtimeMs", downtime)
            })

            // 通知用户（不强制重开——docs/12 §8：用户主动关闭无障碍时不得强行打开）
            notifyUser("无障碍服务已断开 ${downtime / 1000}s，请到设置中重新开启")
        }
    }

    private fun notifyUser(message: String) {
        val ctx = context ?: return
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(CHANNEL, "服务健康", android.app.NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val notification = android.app.Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("超级AI助手 · 需要处理")
                .setContentText(message)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFY_ID, notification)
        }
    }

    /** 测试钩子：手动触发一次检查（不等 30s 定时器） */
    internal fun checkForTest() = check()

    /** 测试钩子：子类覆盖以跳过 Android 通知 */
    internal open fun notifyUserForTest(message: String) = notifyUser(message)

    companion object {
        private const val TAG = "Watchdog"
        private const val CHANNEL = "super-agent-health"
        private const val NOTIFY_ID = 2001
        private const val CHECK_INTERVAL_MS = 30_000L
    }
}
