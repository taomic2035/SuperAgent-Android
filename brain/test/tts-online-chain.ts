/**
 * BD-04 三层播报链测试（brain/src/tts）：
 * 在线 edge/azure → speech.playBytes 为主路；失败自动降级 speech.say（本地链）。
 * 在线真实合成默认跳过（RUN_ONLINE_TTS=1 且有外网时启用）。
 */
import assert from "node:assert/strict"
import { startMockBody } from "./mock-body.ts"
import { BodyClient } from "../src/ipc/client.ts"
import { speak, synthesizeOnline } from "../src/tts/index.ts"

let passed = 0
function ok(name: string): void {
  passed++
  console.log(`  ✓ ${name}`)
}

async function main(): Promise<void> {
  const mock = await startMockBody({ port: 0 })
  try {
    const body = new BodyClient(`http://127.0.0.1:${mock.port}`, "super-agent-dev")
    await body.waitForBody()
    console.log("== TTS 三层播报链 ==")

    // 1. TTS_MODE=local：强制本地，不发起在线合成
    process.env.TTS_MODE = "local"
    {
      const out = await speak(body, "本地链测试", { bodyVoice: { voice: "kokoro_zh" } })
      assert.equal(out.via, "local", "local 模式必须走 speech.say")
      assert.equal(out.result.route, "speaker")
      ok("TTS_MODE=local → speech.say 本地链")
    }

    // 2. auto + 在线合成超时（1ms 必超）→ 降级本地，不抛错
    process.env.TTS_MODE = "auto"
    process.env.TTS_ONLINE_TIMEOUT_MS = "1"
    {
      const out = await speak(body, "降级链测试", {})
      assert.equal(out.via, "local", "在线失败必须降级本地而非抛错")
      ok("在线超时 → 自动降级本地链")
    }

    // 3. 真实在线合成（可选门控：外网环境）
    delete process.env.TTS_ONLINE_TIMEOUT_MS
    if (process.env.RUN_ONLINE_TTS === "1") {
      const out = await speak(body, "在线链路测试", { edgeVoice: "zh-CN-XiaoxiaoNeural" })
      assert.equal(out.via, "online", "有网时应走在线合成")
      assert.ok(["edge", "azure"].includes(out.provider!), "provider 应为 edge/azure")
      assert.ok((out.synthMs ?? 0) < 15_000, "合成应在 15s 内")
      const r = await synthesizeOnline("音色缓存验证")
      assert.ok(r.audio.length > 512 && r.format === "mp3", "应返回可用 MP3")
      ok(`在线真实合成 → playBytes（${out.provider} ${out.synthMs}ms）+ synthesizeOnline 直连`)
    } else {
      console.log("  - 在线真实合成跳过（RUN_ONLINE_TTS=1 启用）")
    }

    delete process.env.TTS_MODE
    console.log(`\n${passed}/${passed + 0} 通过 ✓`)
    await mock.close()
    process.exit(0)
  } catch (err) {
    console.error(err)
    await mock.close()
    process.exit(1)
  }
}

main()
