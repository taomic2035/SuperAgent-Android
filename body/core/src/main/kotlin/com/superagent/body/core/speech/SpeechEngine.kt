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
    /** DS-016：vits 构造失败标记——失败后不再重试（直接 system TTS） */
    @Volatile private var vitsFailed = false  // 恢复 false——重新尝试 TTS
    private val persisted = mutableMapOf<String, MutableList<FloatArray>>()

    private val playing = AtomicBoolean(false)
    private var activeTrack: AudioTrack? = null

    private val recorder = AudioRecorder(File(context.cacheDir, "audio"))

    /** DS-014：TTS 初始化/播放失败写文件日志（华为 logcat 裁剪后台线程 Log.e，必须落盘） */
    private fun logTtsError(stage: String, e: Throwable? = null, extra: String = "") {
        val msg = buildString {
            append("[${System.currentTimeMillis()}] $stage")
            if (e != null) append(" ${e.javaClass.simpleName}: ${e.message}")
            if (extra.isNotEmpty()) append(" | $extra")
            append("\n")
        }
        runCatching {
            File(context.filesDir, "tts-error.log").appendText(msg)
        }
        Log.e("SpeechEngine", msg.trim())
    }

    /** Plan C 兜底：Android 系统 TTS（sherpa 全失败时保证出声） */
    val systemTts by lazy { SystemTts(context).also { it.initialize() } }

    /** AD-12：Barge-in 基础设施——VAD 引擎 + 流式录音器（TTS 播放中检测用户说话） */
    val vadEngine by lazy { VadEngine(context) }
    private var bargeInRecorder: StreamingRecorder? = null
    /** AD-12：barge-in 事件回调（BodyCore 注入——emit 到 EventBus 通知 brain） */
    var onBargeInEvent: (() -> Unit)? = null
    private var onBargeIn: (() -> Unit)? = null

    /** DS-012：vits 全量文件落盘——sherpa 从 assets 读 .txt/.fst 可能失败（mmap 路径不匹配），
     * 全部拷到 filesDir 后用文件路径初始化（同 jieba dict 法，彻底绕开 assets 路径）。 */
    private val vitsFileDir: String by lazy {
        val target = File(context.filesDir, "sherpa/vits")
        val src = "sherpa/models/vits-zh-hf-fanchen-C"
        val sentinel = File(target, "model.onnx")
        if (!sentinel.isFile && hasAssetDir(src)) {
            if (target.exists()) target.deleteRecursively()
            copyAssetsDir(src, target)
        }
        android.util.Log.i("SpeechEngine", "vitsFileDir=${target.absolutePath} model=${sentinel.isFile} files=${target.list()?.size ?: 0}")
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

    /** DS-014 诊断：TTS 引擎状态（vits/kokoro/system 哪个可用） */
    fun ttsStatus(): Map<String, Any> {
        val sherpaReady = try {
            tts() != null
        } catch (e: Exception) {
            false
        }
        return mapOf(
            "sherpaReady" to sherpaReady,
            "systemTtsReady" to systemTts.isReady(),
            "activeEngine" to when {
                sherpaReady -> "sherpa"
                systemTts.isReady() -> "system"
                else -> "none"
            },
        )
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

    /** 播报文本。vits/kokoro 优先，sherpa 全失败时 fallback 到 Android 系统 TTS（Plan C）。 */
    fun say(text: String, voice: VoiceConfig?): SayResult {
        val wake = wakeLock()
        try {
            // DS-016：catch Throwable（非仅 Exception）——Error 族（NoSuchMethodError 等）也要走 fallback
            val t = try {
                tts()
            } catch (e: Throwable) {
                logTtsError("sherpa TTS FAILED (${e.javaClass.simpleName}: ${e.message}), fallback to system TTS")
                null
            }
            if (t == null) return systemTtsFallback(text)
            synchronized(this) {
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
                    // AD-12 Barge-in：TTS 播放时启动 VAD 监听（用户开口 → interrupt → 播放停止 ≤300ms）
                    if (vadEngine.isReady()) {
                        startBargeInListener {
                            interrupt()
                            onBargeInEvent?.invoke()  // AD-12：通知 EventBus → brain
                        }
                    }
                    val callback = object : Function1<FloatArray, Int> {
                        override fun invoke(samples: FloatArray): Int {
                            if (!playing.get()) return 1
                            val pcm = ShortArray(samples.size)
                            for (i in samples.indices) pcm[i] = (samples[i] * 32767).toInt().toShort()
                            track.write(pcm, 0, pcm.size)
                            return 1
                        }
                    }
                    t.generateWithCallback(text, sid, voice?.speed ?: 1.0f, callback)
                } finally {
                    stopBargeInListener() // AD-12：播放结束停 VAD（省电）
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

    /** Plan C：Android 系统 TTS 兜底——即使未完全就绪也尝试播放 */
    private fun systemTtsFallback(text: String): SayResult {
        logTtsError("systemTtsFallback called", extra = "isReady=${systemTts.isReady()}")
        if (!systemTts.isReady()) {
            // 强制再等一次（可能初始化还没完成）
            systemTts.initialize()
            if (!systemTts.isReady()) {
                logTtsError("systemTts still NOT ready after retry")
                throw SpeechUnavailable("系统 TTS 不可用（无中文语音引擎）")
            }
        }
        val ok = systemTts.speak(text)
        logTtsError("systemTts speak result=$ok")
        return SayResult("speaker")
    }

    /** DS-014：对外暴露 logTtsError（BodyCore say 失败时也写文件） */
    fun logTtsErrorPublic(stage: String, e: Throwable? = null) = logTtsError(stage, e)

    fun interrupt() {
        playing.set(false)
        activeTrack?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        activeTrack = null
        stopBargeInListener()
    }

    /** AD-12：开始监听用户说话（TTS 播放时调用——用户开口 ≤300ms 触发 onBargeIn） */
    fun startBargeInListener(callback: () -> Unit) {
        if (!vadEngine.isReady()) return
        stopBargeInListener()
        onBargeIn = callback
        bargeInRecorder = StreamingRecorder().also { recorder ->
            recorder.start { samples ->
                if (vadEngine.process(samples) == VadEngine.VadEvent.SPEECH_START) {
                    Log.i("SpeechEngine", "Barge-in detected → interrupt TTS")
                    callback()
                }
            }
        }
    }

    fun stopBargeInListener() {
        bargeInRecorder?.stop()
        bargeInRecorder = null
        onBargeIn = null
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
        if (vitsFailed) return null
        synchronized(this) {
            tts?.let { return it }
            if (vitsFailed) return null

            // ============ 路径 A：kokoro int8（全文件路径，跳过 assets mmap）============
            // DS-016c：vits 构造在华为 native 层阻塞（根因不明），kokoro int8 之前不卡只是内存不够 exit(1)
            // → 尝试 kokoro int8 + 全文件路径 + 全量落盘
            val kokoroInt8Dir = "sherpa/models/kokoro-int8-multi-lang-v1_0"
            if (hasAsset("$kokoroInt8Dir/model.int8.onnx")) {
                val kTarget = File(context.filesDir, "sherpa/kokoro-int8")
                val kModel = File(kTarget, "model.int8.onnx")
                if (!kModel.isFile) {
                    // 全量拷贝（model+voices+tokens+espeak）
                    if (kTarget.exists()) kTarget.deleteRecursively()
                    copyAssetsDir(kokoroInt8Dir, kTarget)
                    logTtsError("kokoro-int8 files copied", extra = "dir=${kTarget.absolutePath} files=${kTarget.list()?.size ?: 0}")
                }
                val kVoices = File(kTarget, "voices.bin")
                val kTokens = File(kTarget, "tokens.txt")
                val kEspeak = File(kTarget, "espeak-ng-data")
                if (!kModel.isFile || !kVoices.isFile || !kTokens.isFile || !File(kEspeak, "lang").isDirectory) {
                    logTtsError("kokoro-int8 files incomplete", extra = "model=${kModel.isFile} voices=${kVoices.isFile} tokens=${kTokens.isFile} espeak=${File(kEspeak,"lang").isDirectory}")
                } else {
                    val kConfig = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            kokoro = OfflineTtsKokoroModelConfig(
                                model = kModel.absolutePath,
                                voices = kVoices.absolutePath,
                                tokens = kTokens.absolutePath,
                                dataDir = kEspeak.absolutePath,
                                lang = "auto",
                            ),
                            numThreads = 2,
                        ),
                        ruleFsts = "",
                        ruleFars = "",
                        maxNumSentences = 1,
                    )
                    logTtsError("kokoro-int8 all-file init", extra = "model=${kModel.absolutePath}")
                    val kExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
                    val kFuture = kExecutor.submit<OfflineTts> {
                        try {
                            OfflineTts(assets, kConfig)
                        } catch (e: Throwable) {
                            logTtsError("kokoro constructor THREW ${e.javaClass.simpleName}: ${e.message}")
                            throw e
                        }
                    }
                    val kEngine = try {
                        kFuture.get(15, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (e: Throwable) {
                        kFuture.cancel(true)
                        kExecutor.shutdownNow()
                        val cause = if (e is java.util.concurrent.ExecutionException) e.cause ?: e else e
                        logTtsError("kokoro-int8 constructor FAILED", cause as? Exception, "type=${cause.javaClass.simpleName}")
                        null  // kokoro 失败，不设 vitsFailed（继续试 vits）
                    }
                    kExecutor.shutdown()
                    if (kEngine != null) {
                        logTtsError("kokoro-int8 constructed OK", extra = "sampleRate=${kEngine.sampleRate()}")
                        return kEngine.also { tts = it }
                    }
                }
            }

            // ============ 路径 B：vits（原有逻辑）============
            val vitsDir = "sherpa/models/vits-zh-hf-fanchen-C"
            if (hasAsset("$vitsDir/model.onnx") && !vitsFailed) {
                val fileDir = vitsFileDir
                val modelFile = File(fileDir, "model.onnx")
                val lexiconFile = File(fileDir, "lexicon.txt")
                val tokensFile = File(fileDir, "tokens.txt")
                val dictDir = File(fileDir, "dict").absolutePath
                if (!modelFile.isFile || !lexiconFile.isFile || !tokensFile.isFile) {
                    logTtsError("vits files incomplete")
                    vitsFailed = true
                } else {
                    val fstPaths = listOf("phone.fst", "date.fst", "number.fst", "new_heteronym.fst")
                        .map { File(fileDir, it) }
                        .filter { it.isFile }
                        .joinToString(",") { it.absolutePath }
                    val config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = modelFile.absolutePath,
                                lexicon = lexiconFile.absolutePath,
                                tokens = tokensFile.absolutePath,
                                dataDir = dictDir,
                            ),
                            numThreads = 2,
                        ),
                        ruleFsts = fstPaths,
                        ruleFars = "",
                        maxNumSentences = 1,
                    )
                    logTtsError("vits all-file init", extra = "model=${modelFile.absolutePath}")
                    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                    val future = executor.submit<OfflineTts> {
                        try {
                            OfflineTts(assets, config)
                        } catch (e: Throwable) {
                            logTtsError("vits constructor THREW ${e.javaClass.simpleName}: ${e.message}")
                            throw e
                        }
                    }
                    val engine = try {
                        future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (e: Throwable) {
                        future.cancel(true)
                        executor.shutdownNow()
                        vitsFailed = true
                        val cause = if (e is java.util.concurrent.ExecutionException) e.cause ?: e else e
                        logTtsError("vits constructor FAILED", cause as? Exception, "type=${cause.javaClass.simpleName} vitsFailed=true")
                        null
                    }
                    executor.shutdown()
                    if (engine != null) {
                        logTtsError("vits constructed OK", extra = "sampleRate=${engine.sampleRate()}")
                        return engine.also { tts = it }
                    }
                }
            }

            // 全部失败
            logTtsError("all TTS engines failed")
            return null
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