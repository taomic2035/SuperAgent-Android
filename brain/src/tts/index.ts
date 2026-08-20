import { MsEdgeTTS, OUTPUT_FORMAT } from "msedge-tts"
import type { BodyClient } from "../ipc/client.ts"
import type { SayResult } from "../ipc/types.ts"
import { env } from "../env.ts"

/**
 * 在线 TTS（brain 端合成 → speech.playBytes 传 body 播放）。
 *
 * 三层播报链（BD-04 语音）：在线 edge/azure（音质优先）→ 本地 sherpa 流式（断网兜底）
 * → 系统 TTS（speech.say 自带）。TTS_MODE=local 可强制只走本地。
 *
 * provider 选择：配置 AZURE_TTS_KEY + AZURE_TTS_REGION 走 Azure 官方（同款音色、有 SLA），
 * 否则 edge 免费通道（无 key，非官方接口，失败自动降级本地）。
 */

const DEFAULT_EDGE_VOICE = "zh-CN-XiaoxiaoNeural"
const PLAY_BYTES_RPC_TIMEOUT_MS = 15_000

export interface OnlineTtsResult {
  audio: Buffer
  format: "mp3"
  provider: "edge" | "azure" | "cache"
  tookMs: number
  cached: boolean
}

export interface SpeakOutcome {
  via: "online" | "local"
  provider?: string
  synthMs?: number
  result: SayResult
}

interface PersonaVoice {
  /** 本地链音色（speech.say 的 voice 参数）；缺省由 body 侧默认 */
  bodyVoice?: unknown
  edgeVoice?: string
}

const cache = new Map<string, Buffer>()
const CACHE_MAX = 100

function cacheGet(key: string): Buffer | undefined {
  const hit = cache.get(key)
  if (hit) {
    cache.delete(key)
    cache.set(key, hit) // LRU：命中后移到最新端
  }
  return hit
}

function cachePut(key: string, audio: Buffer): void {
  if (cache.has(key)) cache.delete(key)
  cache.set(key, audio)
  if (cache.size > CACHE_MAX) cache.delete(cache.keys().next().value as string)
}

function escapeXml(text: string): string {
  return text.replace(/[<>&'"]/g, (ch) => ({ "<": "&lt;", ">": "&gt;", "&": "&amp;", "'": "&apos;", '"': "&quot;" })[ch] as string)
}

/** Azure 官方通道（预留：设 AZURE_TTS_KEY 即启用，音色名与 edge 相同）。 */
async function synthesizeAzure(text: string, voice: string, timeoutMs: number): Promise<Buffer> {
  const key = process.env.AZURE_TTS_KEY
  const region = process.env.AZURE_TTS_REGION ?? "eastasia"
  const ssml = `<speak version='1.0' xml:lang='zh-CN'><voice name='${voice}'>${escapeXml(text)}</voice></speak>`
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const res = await fetch(`https://${region}.tts.speech.microsoft.com/cognitiveservices/v1`, {
      method: "POST",
      headers: {
        "Ocp-Apim-Subscription-Key": key as string,
        "Content-Type": "application/ssml+xml",
        "X-Microsoft-OutputFormat": "audio-24khz-48kbitrate-mono-mp3",
      },
      body: ssml,
      signal: controller.signal,
    })
    if (!res.ok) throw new Error(`azure tts HTTP ${res.status}`)
    const len = Number(res.headers.get("content-length") ?? "0")
    if (len > 10 * 1024 * 1024) throw new Error(`azure tts 响应过大（${len}B）`)
    const audio = Buffer.from(await res.arrayBuffer())
    if (audio.length < 512) throw new Error(`azure tts 返回音频过小（${audio.length}B）`)
    if (audio.length > 10 * 1024 * 1024) throw new Error(`azure tts 音频超上限（${audio.length}B）`)
    return audio
  } finally {
    clearTimeout(timer)
  }
}

/** edge 免费通道（Edge「大声朗读」接口，无 key；非官方，失败由调用方降级本地）。 */
async function synthesizeEdge(text: string, voice: string, timeoutMs: number): Promise<Buffer> {
  const tts = new MsEdgeTTS()
  let timer: NodeJS.Timeout | undefined
  try {
    const synth = (async () => {
      await tts.setMetadata(voice, OUTPUT_FORMAT.AUDIO_24KHZ_48KBITRATE_MONO_MP3)
      const { audioStream } = tts.toStream(text)
      const chunks: Buffer[] = []
      await new Promise<void>((resolve, reject) => {
        audioStream.on("data", (chunk: Buffer) => chunks.push(chunk))
        audioStream.on("end", () => resolve())
        audioStream.on("error", (err: Error) => reject(err))
      })
      const audio = Buffer.concat(chunks)
      if (audio.length < 512) throw new Error(`edge tts 返回音频过小（${audio.length}B）`)
      return audio
    })()
    const timeout = new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error(`edge tts 超时（${timeoutMs}ms）`)), timeoutMs)
    })
    const audio = await Promise.race([synth, timeout])
    return audio
  } finally {
    clearTimeout(timer) // codex-P1-06：超时/完成后都清 timer，不留悬挂句柄
    try {
      tts.close()
    } catch {
      // 关闭已断开的连接无害；超时路径下必须关，避免 ws 泄漏
    }
  }
}

export async function synthesizeOnline(text: string, voice?: string): Promise<OnlineTtsResult> {
  const timeoutMs = Number(env("TTS_ONLINE_TIMEOUT_MS", "8000"))
  const v = voice || DEFAULT_EDGE_VOICE
  const key = `${v}::${text}`
  const cached = cacheGet(key)
  if (cached) return { audio: cached, format: "mp3", provider: "cache", tookMs: 0, cached: true }

  const t0 = Date.now()
  const useAzure = Boolean(process.env.AZURE_TTS_KEY)
  const provider = useAzure ? "azure" : "edge"
  const audio = useAzure ? await synthesizeAzure(text, v, timeoutMs) : await synthesizeEdge(text, v, timeoutMs)
  cachePut(key, audio)
  return { audio, format: "mp3", provider, tookMs: Date.now() - t0, cached: false }
}

/**
 * 统一播报入口：在线合成 → speech.playBytes；失败（网络/超时/body 不支持）降级 speech.say
 * （body 侧 sherpa 流式 → 系统 TTS）。语音循环与 speech.say 工具共用此链。
 */
export async function speak(body: BodyClient, text: string, persona?: PersonaVoice): Promise<SpeakOutcome> {
  const mode = env("TTS_MODE", "auto")
  if (mode !== "local") {
    try {
      const { audio, provider, tookMs } = await synthesizeOnline(text, persona?.edgeVoice)
      const result = await body.rpc<SayResult>(
        "speech.playBytes",
        { audio: audio.toString("base64"), format: "mp3" },
        undefined,
        PLAY_BYTES_RPC_TIMEOUT_MS,
      )
      return { via: "online", provider, synthMs: tookMs, result }
    } catch (err) {
      console.warn(`[tts] 在线播报失败，降级本地链：${err instanceof Error ? err.message : String(err)}`)
    }
  }
  const result = await body.rpc<SayResult>("speech.say", { text, voice: persona?.bodyVoice })
  return { via: "local", result }
}
