package com.superagent.body.core.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 流式录音器（AD-12 基础设施）：20ms 帧（320 样本 @16kHz）实时回调，
 * 支持 VAD/KWS/Barge-in 的连续音频处理。非阻塞——与 AudioRecorder（阻塞式）互补。
 *
 * 用法：
 * ```kotlin
 * val recorder = StreamingRecorder(context.cacheDir)
 * recorder.start { samples ->
 *     val vadEvent = vadEngine.process(samples)
 *     if (vadEvent == VadEvent.SPEECH_START) { /* 用户开始说话 */ }
 * }
 * // ... 运行中 ...
 * recorder.stop()  // 停止采集
 * ```
 */
class StreamingRecorder(
    private val sampleRate: Int = 16000,
    private val frameMs: Int = 20,  // 每帧 20ms = 320 样本
) {
    companion object {
        private const val TAG = "StreamingRecorder"
        const val SAMPLES_PER_FRAME = 320  // 16000Hz * 0.02s
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    /** 帧回调：每 20ms 一帧（320 个 float 样本），在录音线程执行 */
    fun start(onFrame: (FloatArray) -> Unit) {
        if (running.get()) return
        running.set(true)

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuf, SAMPLES_PER_FRAME * 4)  // 至少 4 帧缓冲

        record = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize,
        )

        if (record?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败")
            running.set(false)
            return
        }

        record?.startRecording()

        thread = Thread {
            val pcmBuf = ShortArray(SAMPLES_PER_FRAME)
            val floatBuf = FloatArray(SAMPLES_PER_FRAME)
            try {
                while (running.get()) {
                    val read = record?.read(pcmBuf, 0, SAMPLES_PER_FRAME) ?: -1
                    if (read <= 0) continue
                    // Short → Float 归一化 [-1, 1]
                    for (i in 0 until read) {
                        floatBuf[i] = pcmBuf[i] / 32767.0f
                    }
                    // 回调（异常不中断采集）
                    runCatching { onFrame(floatBuf.copyOf(read)) }
                        .onFailure { Log.e(TAG, "frame callback error", it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "streaming thread error", e)
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
            }
        }.apply {
            name = "streaming-recorder"
            isDaemon = true
            start()
        }

        Log.i(TAG, "streaming recorder started (${sampleRate}Hz, ${frameMs}ms frames)")
    }

    fun stop() {
        running.set(false)
        thread?.join(1000)
        thread = null
        record = null
        Log.i(TAG, "streaming recorder stopped")
    }

    fun isRunning(): Boolean = running.get()
}
