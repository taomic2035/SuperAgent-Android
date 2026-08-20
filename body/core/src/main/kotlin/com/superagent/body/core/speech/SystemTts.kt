package com.superagent.body.core.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plan C TTS 兜底（DS-013 备选）：Android 系统 TTS。
 * 零模型依赖、零下载、保证出声——sherpa 全失败时的最终 fallback。
 * 质量：系统语音（华为=女声）不如 vits 神经网络，但 P0 验收够用。
 */
class SystemTts(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false

    private val initLatch = CountDownLatch(1)

    fun initialize() {
        if (ready) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.CHINESE
                ready = true
                Log.i("SystemTts", "系统 TTS 就绪（${tts?.isLanguageAvailable(java.util.Locale.CHINESE)}）")
            } else {
                Log.e("SystemTts", "系统 TTS 初始化失败 status=$status")
            }
            initLatch.countDown()
        }
    }

    fun isReady(): Boolean {
        if (!ready) {
            initLatch.await(5, TimeUnit.SECONDS)
        }
        return ready
    }

    /** 播报文本（阻塞直到播完或超时）。返回是否成功。 */
    fun speak(text: String): Boolean {
        if (!isReady()) return false
        val done = CountDownLatch(1)
        var success = false
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                success = true
                done.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                done.countDown()
            }
        })
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "super-agent-${System.currentTimeMillis()}")
        if (result != TextToSpeech.SUCCESS) return false
        done.await(30, TimeUnit.SECONDS)
        return success
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
