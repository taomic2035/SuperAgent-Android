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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class Hitl(private val context: Context, private val events: EventBus) {
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<HitlResult>>()
    private val requestTypes = ConcurrentHashMap<Long, String>()
    private val idGen = AtomicLong(0)

    private val aggregateConfirms = mutableListOf<AggregateEntry>()
    private var aggregateNotifyId = -1

    sealed class HitlResult {
        data class Confirmed(val approved: Boolean) : HitlResult()
        data class Asked(val answer: String) : HitlResult()
        data class HandedOff(val taken: Boolean) : HitlResult()
    }

    private data class AggregateEntry(val id: Long, val prompt: String)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CONFIRM -> {
                    val approved = intent.getBooleanExtra("approved", false)
                    val ids = intent.getLongArrayExtra("ids")?.toList() ?: return
                    for (id in ids) {
                        pending[id]?.complete(HitlResult.Confirmed(approved))
                    }
                    synchronized(aggregateConfirms) {
                        aggregateConfirms.removeAll { it.id in ids }
                        if (aggregateConfirms.isEmpty()) {
                            nm.cancel(aggregateNotifyId)
                            aggregateNotifyId = -1
                        } else {
                            updateAggregateNotification()
                        }
                    }
                }
                ACTION_ANSWER -> {
                    val id = intent.getLongExtra("id", 0L)
                    val future = pending[id] ?: return
                    val results = RemoteInput.getResultsFromIntent(intent)
                    val answer = results?.getCharSequence(KEY_REPLY)?.toString() ?: ""
                    future.complete(HitlResult.Asked(answer))
                }
                ACTION_HANDOFF -> {
                    val id = intent.getLongExtra("id", 0L)
                    pending[id]?.complete(HitlResult.HandedOff(true))
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
        val filter = IntentFilter().apply {
            addAction(ACTION_CONFIRM); addAction(ACTION_ANSWER); addAction(ACTION_HANDOFF)
        }
        context.registerReceiver(receiver, filter)
    }

    suspend fun confirm(prompt: String): Boolean {
        val id = idGen.incrementAndGet()
        requestTypes[id] = "confirm"
        val deferred = CompletableDeferred<HitlResult>()
        pending[id] = deferred

        synchronized(aggregateConfirms) {
            if (aggregateConfirms.isNotEmpty()) {
                aggregateConfirms.add(AggregateEntry(id, prompt))
                updateAggregateNotification()
            } else {
                aggregateConfirms.add(AggregateEntry(id, prompt))
                aggregateNotifyId = id.toInt()
                postAggregateNotification(prompt)
            }
        }

        val result = withTimeoutOrNull(HITL_TIMEOUT_MS) { deferred.await() }
            ?: HitlResult.Confirmed(false)
        cleanup(id)
        emitEvent(id, result)
        return (result as? HitlResult.Confirmed)?.approved ?: false
    }

    suspend fun ask(prompt: String): String {
        val id = idGen.incrementAndGet()
        requestTypes[id] = "ask"
        val deferred = CompletableDeferred<HitlResult>()
        pending[id] = deferred
        postAskNotification(id, prompt)
        val result = withTimeoutOrNull(HITL_TIMEOUT_MS) { deferred.await() }
            ?: HitlResult.Asked("")
        cleanup(id)
        emitEvent(id, result)
        return (result as? HitlResult.Asked)?.answer ?: ""
    }

    suspend fun handoff(reason: String): Boolean {
        val id = idGen.incrementAndGet()
        requestTypes[id] = "handoff"
        val deferred = CompletableDeferred<HitlResult>()
        pending[id] = deferred
        postHandoffNotification(id, reason)
        val result = withTimeoutOrNull(HITL_TIMEOUT_MS) { deferred.await() }
            ?: HitlResult.HandedOff(false)
        cleanup(id)
        emitEvent(id, result)
        return (result as? HitlResult.HandedOff)?.taken ?: false
    }

    private fun postAggregateNotification(initialPrompt: String) {
        val count = aggregateConfirms.size
        val title = if (count > 1) "超级AI助手 · 需确认（$count 项）" else "超级AI助手 · 需要你"
        val text = if (count > 1) "${count} 个操作待确认" else initialPrompt
        val idsArray = aggregateConfirms.map { it.id }.toLongArray()

        val yes = Intent(ACTION_CONFIRM).setPackage(context.packageName)
            .putExtra("approved", true).putExtra("ids", idsArray)
        val no = Intent(ACTION_CONFIRM).setPackage(context.packageName)
            .putExtra("approved", false).putExtra("ids", idsArray)

        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(false)
            .addAction(0, "全部同意", pi(yes)).addAction(0, "全部拒绝", pi(no))
        nm.notify(aggregateNotifyId, builder.build())
    }

    private fun updateAggregateNotification() {
        if (aggregateConfirms.isEmpty()) return
        val first = aggregateConfirms.first()
        postAggregateNotification(first.prompt)
    }

    private fun postAskNotification(id: Long, prompt: String) {
        val replyIntent = Intent(ACTION_ANSWER).setPackage(context.packageName).putExtra("id", id)
        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("回复…").build()
        val replyAction = NotificationCompat.Action.Builder(0, "回复", pi(replyIntent))
            .addRemoteInput(remoteInput).build()
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("超级AI助手 · 需要你").setContentText(prompt)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(false)
            .addAction(replyAction)
        nm.notify(id.toInt(), builder.build())
    }

    private fun postHandoffNotification(id: Long, reason: String) {
        val done = Intent(ACTION_HANDOFF).setPackage(context.packageName).putExtra("id", id)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("超级AI助手 · 人工接管").setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(false)
            .addAction(0, "接管完成", pi(done))
        nm.notify(id.toInt(), builder.build())
    }

    private fun cleanup(id: Long) {
        pending.remove(id)
        requestTypes.remove(id)
        synchronized(aggregateConfirms) {
            aggregateConfirms.removeAll { it.id == id }
        }
        nm.cancel(id.toInt())
    }

    private fun emitEvent(id: Long, result: HitlResult) {
        val value = when (result) {
            is HitlResult.Confirmed -> if (result.approved) "approved" else "denied"
            is HitlResult.Asked -> result.answer
            is HitlResult.HandedOff -> if (result.taken) "taken" else "timeout"
        }
        events.emit("hitl", buildJsonObject { put("id", id); put("result", value) })
    }

    private fun pi(intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(
            context, intent.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val CHANNEL = "super-agent-hitl"
        private const val ACTION_CONFIRM = "com.superagent.body.HITL_CONFIRM"
        private const val ACTION_ANSWER = "com.superagent.body.HITL_ANSWER"
        private const val ACTION_HANDOFF = "com.superagent.body.HITL_HANDOFF"
        private const val KEY_REPLY = "super_agent_reply"
        private const val HITL_TIMEOUT_MS = 60_000L
    }
}
