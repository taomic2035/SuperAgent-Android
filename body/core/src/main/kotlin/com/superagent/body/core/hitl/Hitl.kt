package com.superagent.body.core.hitl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import com.superagent.body.core.events.EventBus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 人工接管（HITL）：通知 + 动态注册 Receiver。
 * - confirm：同意/拒绝 两个通知动作
 * - ask：通知回复文本（通知栏内联输入，API 24+ 支持）
 * - handoff：通知 + 前台标记，人工接管后点「接管完成」
 * 超时默认拒绝/空答（denial is data）。
 */
class Hitl(private val context: Context, private val events: EventBus) {
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val requests = ConcurrentHashMap<Long, String>()
    private var seq = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra("id", 0L)
            val future = pending[id] ?: return
            when (intent.action) {
                ACTION_CONFIRM -> future.complete(
                    buildJsonObject { put("approved", intent.getBooleanExtra("approved", false)) },
                )
                ACTION_ANSWER -> {
                    val answer = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    future.complete(buildJsonObject { put("answer", answer) })
                }
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "人工接管", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val filter = IntentFilter().apply { addAction(ACTION_CONFIRM); addAction(ACTION_ANSWER) }
        context.registerReceiver(receiver, filter)
    }

    suspend fun confirm(prompt: String): Boolean {
        val result = await(requests(prompt)) ?: return false
        return result.jsonObject["approved"]?.jsonPrimitive?.content == "true"
    }

    suspend fun ask(prompt: String): String {
        val result = await(requests(prompt)) ?: return ""
        return result.jsonObject["answer"]?.jsonPrimitive?.content ?: ""
    }

    suspend fun handoff(reason: String): Boolean {
        val result = await(requests("人工接管：$reason")) ?: return false
        return result.jsonObject["taken"]?.jsonPrimitive?.content == "true"
    }

    private fun requests(prompt: String): Long {
        val id = ++seq
        requests[id] = prompt
        pending[id] = CompletableFuture()
        post(id, prompt)
        return id
    }

    private suspend fun await(id: Long): JsonElement? {
        val future = pending[id] ?: return null
        val result = try {
            future.get(60, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
        if (result != null) {
            val approved = result.jsonObject["approved"]?.jsonPrimitive?.content ?: ""
            events.emit("hitl", buildJsonObject { put("id", id); put("approved", approved) })
        }
        cleanup(id)
        return result
    }

    private fun cleanup(id: Long) {
        pending.remove(id)
        requests.remove(id)
        nm.cancel(id.toInt())
    }

    private fun post(id: Long, prompt: String) {
        val confirmIntent = Intent(ACTION_CONFIRM).setPackage(context.packageName).putExtra("id", id).putExtra("approved", true)
        val denyIntent = Intent(ACTION_CONFIRM).setPackage(context.packageName).putExtra("id", id).putExtra("approved", false)
        val answerIntent = Intent(ACTION_ANSWER).setPackage(context.packageName).putExtra("id", id)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("超级AI助手 · 需要你")
            .setContentText(prompt)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .addAction(0, "同意", pi(confirmIntent))
            .addAction(0, "拒绝", pi(denyIntent))
            .addAction(0, "回复", pi(answerIntent))
            .build()
        nm.notify(id.toInt(), notification)
    }

    private fun pi(intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            intent.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val CHANNEL = "super-agent-hitl"
        private const val ACTION_CONFIRM = "com.superagent.body.HITL_CONFIRM"
        private const val ACTION_ANSWER = "com.superagent.body.HITL_ANSWER"
    }
}