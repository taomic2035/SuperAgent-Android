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

    /** 非阻塞就绪查询（G1-07/#32：状态路径不得阻塞 RPC 线程 5s）。 */
    fun isReady(): Boolean = ready

    /** 播报路径专用：未就绪时等初始化（最多 5s）——speak 本身是阻塞语义，等待合理。 */
    private fun awaitReady(): Boolean {
        if (!ready) initLatch.await(5, TimeUnit.SECONDS)
        return ready
    }

    /** 播报文本（阻塞直到播完或超时）。@Synchronized 防并发——先 stop 清队列再播。 */
    @Synchronized
    fun speak(text: String): Boolean {
        if (!awaitReady()) return false
        // 先清空可能残留的队列（之前的 utterance 可能堵着）
        tts?.stop()
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
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sa-${System.currentTimeMillis()}")
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
