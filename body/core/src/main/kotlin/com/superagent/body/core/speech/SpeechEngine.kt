package com.superagent.body.core.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import com.k2fsa.sherpa.onnx.WaveReader
import com.superagent.common.AsrResult
import com.superagent.common.SayResult
import com.superagent.common.VoiceprintEnrollResult
import com.superagent.common.VoiceprintIdentifyResult
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SpeechUnavailable(message: String) : Exception(message)

/**
 * 端侧语音三件套（sherpa-onnx v1.13.2，模型来自 scripts/fetch-models）：
 * - ASR: SenseVoice（assets/sherpa/models/sensevoice-ctc-int8-zh/）
 * - TTS: Kokoro 多语言（assets/sherpa/models/kokoro-multi-lang-v1_0/）
 * - 声纹: 3D-Speaker eres2net（assets/sherpa/models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx）
 * 模型缺失时优雅降级：抛 SpeechUnavailable，由服务层转成体侧错误码。
 */
class SpeechEngine(private val context: Context) {
    private val assets = context.assets
    private val dir = File(context.filesDir, "speaker-embeddings")

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null
    @Volatile private var manager: SpeakerEmbeddingManager? = null
    private val persisted = mutableMapOf<String, MutableList<FloatArray>>()

    private val playing = AtomicBoolean(false)
    private var activeTrack: AudioTrack? = null

    private val recorder = AudioRecorder(File(context.cacheDir, "audio"))

    /** VITS 的 jieba dict 必须落盘（同 espeak：sherpa 只能从文件系统读目录），首次使用时复制。
     * DS-005 修复：守卫从 target.exists() 改为检查 jieba.dict.utf8 具体文件——半成品目录自动重拷。 */
    private val vitsDictDir: String by lazy {
        val target = File(context.filesDir, "sherpa/vits-dict")
        val src = "sherpa/models/vits-zh-hf-fanchen-C/dict"
        val sentinel = File(target, "jieba.dict.utf8")
        if (!sentinel.isFile && hasAssetDir(src)) {
            if (target.exists()) target.deleteRecursively() // 清半成品
            copyAssetsDir(src, target)
        }
        android.util.Log.i("SpeechEngine", "vitsDictDir=${target.absolutePath} jieba=${sentinel.isFile} files=${target.list()?.size ?: 0}")
        target.absolutePath
    }

    /** Kokoro 的 espeak-ng-data 必须落盘（sherpa 无法直接从 assets 读目录），首次使用时复制。 */
    private val espeakDir: String by lazy {
        val target = File(context.filesDir, "sherpa/espeak-ng-data")
        // int8 包自带同份 espeak 数据；fp32 目录可能被精简，两个来源按存在性取
        val src =
            if (hasAssetDir("sherpa/models/kokoro-multi-lang-v1_0/espeak-ng-data")) "sherpa/models/kokoro-multi-lang-v1_0/espeak-ng-data"
            else "sherpa/models/kokoro-int8-multi-lang-v1_0/espeak-ng-data"
        // 注意探测目录必须用 assets.list()：assets.open() 对目录抛 FileNotFoundException，
        // 曾导致守卫恒 false → 拷贝被跳过 → dataDir 指向不存在路径 → sherpa native exit(1) 整进程死亡
        if (!target.exists() && hasAssetDir(src)) {
            copyAssetsDir(src, target)
        }
        target.absolutePath
    }

    private fun copyAssetsDir(assetPath: String, target: File) {
        target.mkdirs()
        assets.list(assetPath)?.forEach { name ->
            val childAsset = "$assetPath/$name"
            val childTarget = File(target, name)
            // DS-005 真正根因：assets.list(file) 返回空数组（非 null）→ `!= null` 恒 true
            // → 所有文件都被当目录→创建了空目录而非复制文件。修复：先试 open（能开=文件）。
            val isFile = try {
                assets.open(childAsset).close()
                true
            } catch (_: Exception) {
                false
            }
            if (isFile) {
                assets.open(childAsset).use { input ->
                    childTarget.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                copyAssetsDir(childAsset, childTarget)
            }
        }
    }

    fun isReady(): Map<String, Boolean> = mapOf(
        "asr" to hasAsset("sherpa/models/sensevoice-ctc-int8-zh/model.onnx"),
        "tts" to (hasAsset("sherpa/models/vits-zh-hf-fanchen-C/model.onnx") ||
            hasAsset("sherpa/models/kokoro-int8-multi-lang-v1_0/model.int8.onnx") ||
            hasAsset("sherpa/models/kokoro-multi-lang-v1_0/model.onnx")),
        "speaker" to hasAsset("sherpa/models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"),
    )

    fun recognize(): AsrResult {
        val r = recognizer() ?: throw SpeechUnavailable("ASR 模型未安装（scripts/fetch-models）")
        val wav = recorder.record()
        val wave = WaveReader.readWave(wav.wavFile.absolutePath)
        wav.wavFile.delete()
        if (wave.samples.isEmpty()) return AsrResult("", 0.0, wav.durationMs)
        val stream = r.createStream()
        try {
            stream.acceptWaveform(wave.samples, 16000)
            r.decode(stream)
            val result = r.getResult(stream)
            return AsrResult(result.text, 1.0, wav.durationMs)
        } finally {
            stream.release()
        }
    }

    /** 播报文本。refAudio/refText/instruct 走 ZipVoice 克隆（P0：Kokoro 多音色）。线程安全：串行播放。 */
    fun say(text: String, voice: VoiceConfig?): SayResult {
        val wake = wakeLock()
        try {
            synchronized(this) {
                val t = tts() ?: throw SpeechUnavailable("TTS 模型未安装（scripts/fetch-models）")
                interrupt()
                playing.set(true)
                val sampleRate = t.sampleRate()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(sampleRate * 2, 4096))
                    .build()
                activeTrack = track
                try {
                    track.play()
                    val sid = voice?.speakerId ?: 0
                    // P1 流式：sherpa Tts.kt 已修回调签名 Int→Int?（vendored，DS-007/010 根治）
                    // 恢复 generateWithCallback（首包 ~100ms）；如真机仍异常回退 generate() 全量版
                    val callback = object : Function1<FloatArray, Int?> {
                        override fun invoke(samples: FloatArray): Int? {
                            if (!playing.get()) return 1
                            val pcm = ShortArray(samples.size)
                            for (i in samples.indices) pcm[i] = (samples[i] * 32767).toInt().toShort()
                            track.write(pcm, 0, pcm.size)
                            return 1
                        }
                    }
                    t.generateWithCallback(text, sid, voice?.speed ?: 1.0f, callback)
                } finally {
                    runCatching { track.stop() }
                    runCatching { track.release() }
                    if (activeTrack === track) activeTrack = null
                    playing.set(false)
                }
                return SayResult("speaker")
            }
        } finally {
            // 合成/播放抛异常也必须释放，wakelock 虽有 60s 自释放兜底但不该占着
            wake?.release()
        }
    }

    /** 播放期间持 CPU 锁，防后台播放被系统（LMK/省电）打断。 */
    private fun wakeLock(): android.os.PowerManager.WakeLock? =
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "superagent:tts")
                .apply {
                    setReferenceCounted(false)
                    acquire(60_000)
                }
        }.getOrNull()

    fun interrupt() {
        playing.set(false)
        activeTrack?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        activeTrack = null
    }

    fun enroll(name: String, samples: Int = 3): VoiceprintEnrollResult {
        val ex = extractor() ?: throw SpeechUnavailable("声纹模型未安装（scripts/fetch-models）")
        val mgr = manager(ex)
        val embeddings = mutableListOf<FloatArray>()
        for (i in 0 until samples) {
            val wav = recorder.record(silenceMs = 1000)
            val wave = WaveReader.readWave(wav.wavFile.absolutePath)
            wav.wavFile.delete()
            if (wave.samples.isEmpty()) continue
            val stream = ex.createStream()
            try {
                stream.acceptWaveform(wave.samples, 16000)
                if (ex.isReady(stream)) {
                    ex.compute(stream)?.let(embeddings::add)
                }
            } finally {
                stream.release()
            }
        }
        if (embeddings.isEmpty()) throw SpeechUnavailable("未采集到有效语音样本")
        mgr.add(name, embeddings.toTypedArray())
        persisted[name] = (persisted[name] ?: mutableListOf()).apply { addAll(embeddings) }
        persistManager()
        return VoiceprintEnrollResult(name, embeddings.size)
    }

    fun identify(): VoiceprintIdentifyResult {
        val ex = extractor() ?: throw SpeechUnavailable("声纹模型未安装（scripts/fetch-models）")
        val mgr = manager(ex)
        if (mgr.numSpeakers() == 0) return VoiceprintIdentifyResult(null, 0.0)
        val wav = recorder.record(silenceMs = 1000)
        val wave = WaveReader.readWave(wav.wavFile.absolutePath)
        wav.wavFile.delete()
        val stream = ex.createStream()
        val embedding: FloatArray?
        try {
            stream.acceptWaveform(wave.samples, 16000)
            embedding = if (ex.isReady(stream)) ex.compute(stream) else null
        } finally {
            stream.release()
        }
        if (embedding == null) return VoiceprintIdentifyResult(null, 0.0)
        val speaker = mgr.search(embedding, 0.7f)
        return VoiceprintIdentifyResult(speaker.takeIf { it.isNotEmpty() }, 0.8)
    }

    // ---- 内部 ----

    private fun recognizer(): OfflineRecognizer? {
        recognizer?.let { return it }
        synchronized(this) {
            recognizer?.let { return it }
            if (!hasAsset("sherpa/models/sensevoice-ctc-int8-zh/model.onnx")) return null
            val modelDir = "sherpa/models/sensevoice-ctc-int8-zh"
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "$modelDir/model.onnx",
                        language = "zh",
                        useInverseTextNormalization = true,
                    ),
                    numThreads = 4,
                    debug = false,
                    modelType = "sense_voice",
                    tokens = "$modelDir/tokens.txt",
                ),
            )
            return OfflineRecognizer(assets, config).also { recognizer = it }
        }
    }

    private fun tts(): OfflineTts? {
        tts?.let { return it }
        synchronized(this) {
            tts?.let { return it }
            // G3 Plan B（BD-04.2 规格：vits-zh 可一键切换）：Kokoro 两档在真机均 native exit(1)
            // （fp32 PSS 808MB / int8 零内存压力均干净退），vits 走独立路径（lexicon+jieba，无 espeak）
            // ——存在即优先，Kokoro 全缺才返回 null。
            val vitsDir = "sherpa/models/vits-zh-hf-fanchen-C"
            if (hasAsset("$vitsDir/model.onnx")) {
                val dictDir = vitsDictDir
                val jiebaFile = File(dictDir, "jieba.dict.utf8")
                if (!jiebaFile.isFile) {
                    // DS-005：强制重拷一次（lazy 守卫可能因时序/半成品漏拷）
                    val target = File(context.filesDir, "sherpa/vits-dict")
                    target.deleteRecursively()
                    val src = "$vitsDir/dict"
                    if (hasAssetDir(src)) copyAssetsDir(src, target)
                    if (!File(target, "jieba.dict.utf8").isFile) {
                        android.util.Log.e("SpeechEngine", "vits dict 重拷后仍缺 jieba.dict.utf8；dir=${target.absolutePath} files=${target.list()?.joinToString(",")}")
                        throw SpeechUnavailable("vits jieba dict 不完整（拷贝失败或包内缺数据）")
                    }
                }
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = "$vitsDir/model.onnx",
                            lexicon = "$vitsDir/lexicon.txt",
                            tokens = "$vitsDir/tokens.txt",
                            dataDir = dictDir,
                        ),
                        numThreads = 4,
                    ),
                    ruleFsts = "$vitsDir/phone.fst,$vitsDir/date.fst,$vitsDir/number.fst,$vitsDir/new_heteronym.fst",
                    ruleFars = "",
                    maxNumSentences = 1,
                )
                return OfflineTts(assets, config).also { tts = it }
            }
            // G3.1：int8 优先（fp32 实测 PSS 尖峰 808MB → native exit(1)；
            // int8 体积 114MB，session 内存预期 ~400MB）。int8 缺失时退回 fp32。
            val int8Dir = "sherpa/models/kokoro-int8-multi-lang-v1_0"
            val fp32Dir = "sherpa/models/kokoro-multi-lang-v1_0"
            val useInt8 = hasAsset("$int8Dir/model.int8.onnx")
            val modelDir = if (useInt8) int8Dir else fp32Dir
            val modelFile = if (useInt8) "model.int8.onnx" else "model.onnx"
            if (!hasAsset("$modelDir/$modelFile")) return null
            // native 层对坏 dataDir 是硬 exit(1)（整进程死、无异常可捕），必须先验后建：
            // 宁可返回可 typed 处理的 SpeechUnavailable，也不让进程被 native 拖死
            if (!File(espeakDir, "lang").isDirectory) {
                throw SpeechUnavailable("espeak-ng-data 不完整（lang/ 缺失，拷贝失败或包内缺数据）")
            }
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "$modelDir/$modelFile",
                        voices = "$modelDir/voices.bin",
                        tokens = "$modelDir/tokens.txt",
                        dataDir = espeakDir,
                        lang = "auto",
                    ),
                    numThreads = 4,
                ),
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1,
            )
            return OfflineTts(assets, config).also { tts = it }
        }
    }

    private fun extractor(): SpeakerEmbeddingExtractor? {
        extractor?.let { return it }
        synchronized(this) {
            extractor?.let { return it }
            val model = "sherpa/models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
            if (!hasAsset(model)) return null
            return SpeakerEmbeddingExtractor(assets, SpeakerEmbeddingExtractorConfig(model = model)).also { extractor = it }
        }
    }

    private fun manager(ex: SpeakerEmbeddingExtractor): SpeakerEmbeddingManager {
        manager?.let { return it }
        synchronized(this) {
            manager?.let { return it }
            val m = SpeakerEmbeddingManager(ex.dim())
            val file = File(dir, "embeddings.json")
            if (file.exists()) {
                runCatching {
                    val data = kotlinx.serialization.json.Json.decodeFromString<Map<String, List<List<Double>>>>(file.readText())
                    for ((name, vectors) in data) {
                        val floats = vectors.map { v -> v.map { it.toFloat() }.toFloatArray() }
                        if (floats.isNotEmpty()) m.add(name, floats.toTypedArray())
                        persisted[name] = floats.toMutableList()
                    }
                }
            }
            return m.also { manager = it }
        }
    }

    private fun persistManager() {
        val data = persisted.mapValues { (_, vectors) -> vectors.map { v -> v.map { it.toDouble() } } }
        dir.mkdirs()
        File(dir, "embeddings.json").writeText(
            kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(data),
        )
    }

    private fun hasAsset(path: String): Boolean =
        runCatching { assets.open(path).close(); true }.getOrDefault(false)

    /** 探测 assets 内目录（open() 对目录会抛异常，目录必须用 list()）。 */
    private fun hasAssetDir(path: String): Boolean =
        runCatching { !assets.list(path).isNullOrEmpty() }.getOrDefault(false)
}

/** 音色配置（对齐 Clowder VoiceConfig 契约子集）。 */
@kotlinx.serialization.Serializable
data class VoiceConfig(
    val speakerId: Int = 0,
    val speed: Float = 1.0f,
    val refAudio: String? = null,
    val refText: String? = null,
    val instruct: String? = null,
    val temperature: Float = 0.7f,
)