package com.superagent.body.core.speech

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * AD-12 Silero VAD 封装：20ms 帧语音活动检测。
 * 三事件：SPEECH_START（用户开始说话）/ SPEECH_END（静音确认）/ NONE。
 * Barge-in：TTS 播放中检测到 SPEECH_START → 调用方 interrupt()。
 */
class VadEngine(private val context: Context) {

    enum class VadEvent { NONE, SPEECH_START, SPEECH_END }

    private var vad: Vad? = null
    private var inSpeech = false

    private fun engine(): Vad? {
        vad?.let { return it }
        synchronized(this) {
            vad?.let { return it }
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "sherpa/models/silero_vad/model.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                ),
                sampleRate = 16000,
                numThreads = 1,
            )
            return Vad(context.assets, config).also { vad = it }
        }
    }

    fun isReady(): Boolean = runCatching {
        context.assets.open("sherpa/models/silero_vad/model.onnx").close(); true
    }.getOrDefault(false)

    fun process(samples: FloatArray): VadEvent {
        val engine = engine() ?: return VadEvent.NONE
        return try {
            engine.acceptWaveform(samples)
            val isSpeech = engine.isSpeechDetected()
            when {
                isSpeech && !inSpeech -> {
                    inSpeech = true
                    VadEvent.SPEECH_START
                }
                !isSpeech && inSpeech -> {
                    inSpeech = false
                    VadEvent.SPEECH_END
                }
                else -> VadEvent.NONE
            }
        } catch (e: Exception) {
            Log.e("VadEngine", "process error", e)
            VadEvent.NONE
        }
    }

    fun reset() {
        inSpeech = false
        vad?.reset()
    }
}
