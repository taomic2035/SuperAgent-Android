package com.superagent.body.core.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * 16kHz/16bit/单声道录音，静音 1.2s 自动结束（最长 15s）。
 * 输出 WAV 文件供 sherpa-onnx 读取。
 */
class AudioRecorder(private val outDir: File, private val sampleRate: Int = 16000) {
    data class Recording(val wavFile: File, val durationMs: Long)

    fun record(silenceMs: Long = 1200, maxMs: Long = 15_000): Recording {
        outDir.mkdirs()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuf, sampleRate / 10 * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize,
        )
        record.startRecording()
        val pcm = mutableListOf<Short>()
        val buf = ShortArray(bufferSize / 2)
        var silenceSince = System.currentTimeMillis()
        var startedAt = System.currentTimeMillis()
        var hasAudio = false
        try {
            while (true) {
                val read = record.read(buf, 0, buf.size)
                if (read <= 0) continue
                var rms = 0.0
                for (i in 0 until read) rms += abs(buf[i].toDouble())
                rms /= read
                if (rms > 400) {
                    hasAudio = true
                    silenceSince = System.currentTimeMillis()
                } else if (hasAudio && System.currentTimeMillis() - silenceSince > silenceMs) {
                    break
                }
                if (System.currentTimeMillis() - startedAt > maxMs) break
                pcm.addAll(buf.asList().subList(0, read))
            }
        } finally {
            record.stop()
            record.release()
        }
        val durationMs = System.currentTimeMillis() - startedAt
        val file = File(outDir, "record-${System.currentTimeMillis()}.wav")
        writeWav(file, pcm.toShortArray())
        return Recording(file, durationMs)
    }

    companion object {
        fun writeWav(file: File, samples: ShortArray, sampleRate: Int = 16000) {
            FileOutputStream(file).use { out ->
                val dataSize = samples.size * 2
                out.write("RIFF".toByteArray())
                writeIntLE(out, 36 + dataSize)
                out.write("WAVE".toByteArray())
                out.write("fmt ".toByteArray())
                writeIntLE(out, 16)
                writeShortLE(out, 1)
                writeShortLE(out, 1)
                writeIntLE(out, sampleRate)
                writeIntLE(out, sampleRate * 2)
                writeShortLE(out, 2)
                writeShortLE(out, 16)
                out.write("data".toByteArray())
                writeIntLE(out, dataSize)
                val bytes = ByteArray(dataSize)
                for (i in samples.indices) {
                    bytes[i * 2] = (samples[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
                }
                out.write(bytes)
            }
        }

        private fun writeIntLE(out: FileOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }

        private fun writeShortLE(out: FileOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
    }
}