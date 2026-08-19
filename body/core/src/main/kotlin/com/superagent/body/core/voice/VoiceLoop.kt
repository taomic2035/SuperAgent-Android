package com.superagent.body.core.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.superagent.body.core.events.EventBus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 语音环状态机骨架（BD-04.4/WP3）。
 * P0：通知栏按钮触发 → emit voice trigger 事件 → brain 轮询到后走 ASR→agent→TTS
 * P1：KWS 常驻唤醒 + VAD barge-in（自动打断 TTS）
 *
 * 状态：idle → listening → thinking → acting → speaking → idle
 * body 侧只管触发与状态上报，实际 ASR/规划/TTS 在 brain 侧执行。
 */
class VoiceLoop(private val context: Context, private val events: EventBus) {
    enum class State { idle, listening, thinking, acting, speaking }

    @Volatile
    var state: State = State.idle
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TRIGGER -> {
                    setState(State.listening)
                    events.emit("voice", buildJsonObject { put("kind", "trigger") })
                }
                ACTION_STATE -> {
                    val newState = intent.getStringExtra("state") ?: return
                    runCatching { State.valueOf(newState) }.onSuccess { setState(it) }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply { addAction(ACTION_TRIGGER); addAction(ACTION_STATE) }
        // targetSdk 34+ 在 Android 14+ 必须声明导出标志；通知栏按钮为本应用自发广播 → NOT_EXPORTED
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun setState(newState: State) {
        if (state == newState) return
        state = newState
        events.emit("voice", buildJsonObject { put("kind", "state"); put("state", newState.name) })
    }

    fun teardown() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    companion object {
        const val ACTION_TRIGGER = "com.superagent.body.VOICE_TRIGGER"
        const val ACTION_STATE = "com.superagent.body.VOICE_STATE"

        fun triggerIntent(context: Context): Intent =
            Intent(ACTION_TRIGGER).setPackage(context.packageName)
    }
}