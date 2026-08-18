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
import androidx.core.app.RemoteInput
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
 * - confirm：同意/拒绝 两个通知按钮
 * - ask：通知栏 RemoteInput 内联回复（API 24+ 支持）
 * - handoff：通知 + 接管完成按钮
 * 超时默认拒绝/空答（denial is data）。
 */
class Hitl(private val context: Context, private val events: EventBus) {
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val requestTypes = ConcurrentHashMap<Long, String>()
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
                    val results = RemoteInput.getResultsFromIntent(intent)
                    val answer = results?.getCharSequence(KEY_REPLY)?.toString() ?: ""
                    future.complete(buildJsonObject { put("answer", answer) })
                }
                ACTION_HANDOFF -> future.complete(buildJsonObject { put("taken", true) })
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "人工接管", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_CONFIRM); addAction(ACTION_ANSWER); addAction(ACTION_HANDOFF)
        }
        context.registerReceiver(receiver, filter)
    }

    suspend fun confirm(prompt: String): Boolean {
        val result = await(requests(prompt, "confirm")) ?: return false
        return result.jsonObject["approved"]?.jsonPrimitive?.content == "true"
    }

    suspend fun ask(prompt: String): String {
        val result = await(requests(prompt, "ask")) ?: return ""
        return result.jsonObject["answer"]?.jsonPrimitive?.content ?: ""
    }

    suspend fun handoff(reason: String): Boolean {
        val result = await(requests("人工接管：$reason", "handoff")) ?: return false
        return result.jsonObject["taken"]?.jsonPrimitive?.content == "true"
    }

    private fun requests(prompt: String, type: String): Long {
        val id = ++seq
        requestTypes[id] = type
        pending[id] = CompletableFuture()
        post(id, prompt, type)
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
            val approved = result.jsonObject["approved"]?.jsonPrimitive?.content
                ?: result.jsonObject["answer"]?.jsonPrimitive?.content
                ?: result.jsonObject["taken"]?.jsonPrimitive?.content
                ?: ""
            events.emit("hitl", buildJsonObject { put("id", id); put("result", approved) })
        }
        cleanup(id)
        return result
    }

    private fun cleanup(id: Long) {
        pending.remove(id)
        requestTypes.remove(id)
        nm.cancel(id.toInt())
    }

    private fun post(id: Long, prompt: String, type: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("超级AI助手 · 需要你")
            .setContentText(prompt)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)

        when (type) {
            "confirm" -> {
                val yes = Intent(ACTION_CONFIRM).setPackage(context.packageName).putExtra("id", id).putExtra("approved", true)
                val no = Intent(ACTION_CONFIRM).setPackage(context.packageName).putExtra("id", id).putExtra("approved", false)
                builder.addAction(0, "同意", pi(yes)).addAction(0, "拒绝", pi(no))
            }
            "ask" -> {
                val replyIntent = Intent(ACTION_ANSWER).setPackage(context.packageName).putExtra("id", id)
                val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("回复…").build()
                val replyAction = NotificationCompat.Action.Builder(0, "回复", pi(replyIntent))
                    .addRemoteInput(remoteInput).build()
                builder.addAction(replyAction)
            }
            "handoff" -> {
                val done = Intent(ACTION_HANDOFF).setPackage(context.packageName).putExtra("id", id)
                builder.addAction(0, "接管完成", pi(done))
            }
        }
        nm.notify(id.toInt(), builder.build())
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
        private const val ACTION_HANDOFF = "com.superagent.body.HITL_HANDOFF"
        private const val KEY_REPLY = "super_agent_reply"
    }
}