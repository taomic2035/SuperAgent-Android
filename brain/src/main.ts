import { Agent } from "@earendil-works/pi-agent-core"
import { createInterface } from "node:readline/promises"
import { stdin, stdout } from "node:process"
import { BodyClient } from "./ipc/client.ts"
import { resolveModel } from "./model.ts"
import { buildTools } from "./tools/index.ts"
import { beforeToolCall, afterToolCall, resetSensitiveSession } from "./guards/index.ts"
import { buildLlmRelevanceCheck } from "./guards/relevance.ts"
import { buildLlmVisionMarks } from "./guards/vision.ts"
import { loadPersonas } from "./personas/personas.ts"
import { buildSystemPrompt, buildChatOnlyPrompt } from "./personas/promptBuilder.ts"
import { beginRun, hasResumableRun, resumeRun, finishRun, peekRun, buildResumeContext, getRun } from "./runState.ts"
import { env } from "./env.ts"
import type { AsrResult, BodyEvent, SkillListResult } from "./ipc/types.ts"

const BODY_URL = env("BODY_URL", "http://127.0.0.1:8765")
const BODY_TOKEN = env("BODY_TOKEN", "super-agent-dev")
const PERSONA_NAME = env("PERSONA", "assistant")
const VOICE_MODE = env("VOICE_MODE", "0") === "1"

let responseBuffer = ""

async function main(): Promise<void> {
  const body = new BodyClient(BODY_URL, BODY_TOKEN)
  console.log(`[brain] 等待躯体服务 ${BODY_URL} ...`)
  await body.waitForBody()
  console.log("[brain] 躯体已连接")

  // TC-14 断点续跑：只预览不灌入 current，避免 beginRun 前的旧状态污染新任务
  const pending = peekRun()
  if (pending) {
    console.log(
      `[brain] 发现有未完成任务：「${pending.goal}」（${pending.trace.length} 步，${pending.outcome ?? "中断"}）— 输入「继续」恢复，或输入新任务放弃`,
    )
  }

  const personaConfig = loadPersonas()
  const persona = personaConfig.personas[PERSONA_NAME] ?? personaConfig.personas.assistant
  console.log(`[brain] 角色：${persona.name}（${PERSONA_NAME}）`)

  let skills: SkillListResult = { skills: [] }
  try {
    skills = await body.rpc<SkillListResult>("skill.list", {})
  } catch (err) {
    console.log(`[brain] 技能目录读取失败（不影响启动）：${err instanceof Error ? err.message : String(err)}`)
  }
  console.log(`[brain] 已加载技能 ${skills.skills.length} 个（检索由 body skill.search 提供）`)

  const resolved = resolveModel()
  const { models, localOnly } = resolved
  let modelTier: "primary" | "backup" | "local" = localOnly ? "local" : "primary"
  let tierLabel = resolved.label
  console.log(`[brain] 模型：${tierLabel}${localOnly ? "（离线闲聊模式，工具集已清空）" : ""}`)

  // BR-04.3 证据相关性软门（fail-open）+ 感知 L1 视觉识别；**随模型层联动重建**（降级链脑裂修复：
  // 切到 backup 后若仍绑垂死的 primary，相关性/视觉会持续失败只能靠 fail-open 硬扛）
  let relevance: ReturnType<typeof buildLlmRelevanceCheck> | undefined
  let vision: ReturnType<typeof buildLlmVisionMarks> | undefined
  function rebuildSidecars(tier: "primary" | "backup" | "local") {
    relevance =
      env("EVIDENCE_RELEVANCE", "1") === "1" && tier !== "local"
        ? buildLlmRelevanceCheck(models, tier === "primary" ? resolved.model : resolved.backupModel!!)
        : undefined
    vision =
      env("VISION", "1") === "1" && tier === "primary" && !localOnly
        ? buildLlmVisionMarks(models, resolved.model)
        : undefined
  }
  rebuildSidecars(modelTier)

  let agent = new Agent({
    initialState: {
      systemPrompt: localOnly ? buildChatOnlyPrompt(persona) : buildSystemPrompt(persona, skills.skills),
      model: resolved.model,
      // BR-02.3 安全铁律：M3 本地模型不授予设备控制权（弱模型+控制权=安全反模式）
      tools: localOnly ? [] : buildTools(body, personaConfig.personas, relevance, vision),
    },
    streamFn: models.streamSimple.bind(models),
    beforeToolCall,
    afterToolCall,
  })

  const subscribe = (a: Agent): void => {
    a.subscribe((event) => {
      if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta") {
        process.stdout.write(event.assistantMessageEvent.delta)
        responseBuffer += event.assistantMessageEvent.delta
      }
    })
  }
  subscribe(agent)

  /** BR-02.2 主模型连续失败 ≥3 次 → 降级切换（backup 无视觉 → local 仅闲聊）。 */
  let llmFailures = 0
  function switchModel(reason: string): boolean {
    if (modelTier === "primary" && resolved.backupModel) {
      modelTier = "backup"
      tierLabel = resolved.backupLabel!
      console.log(`\n[brain] 主模型连续失败 ≥3 次，已切换备用：${tierLabel}（能力降级：无视觉）。${reason}`)
    } else if (modelTier !== "local" && resolved.localModel) {
      modelTier = "local"
      tierLabel = resolved.localLabel!
      console.log(`\n[brain] 已降级到本地离线闲聊模式：${tierLabel}（设备控制禁用）。${reason}`)
    } else {
      return false
    }
    llmFailures = 0
    rebuildSidecars(modelTier) // 相关性判官跟随新模型层；local 层清空、backup 层无视觉
    const nextModel = modelTier === "backup" ? resolved.backupModel! : resolved.localModel!
    agent = new Agent({
      initialState: {
        // 切到 local = 离线闲聊铁律；backup 保留全部工具（仅丢视觉，工具仍可用）
        systemPrompt: modelTier === "local" ? buildChatOnlyPrompt(persona) : buildSystemPrompt(persona, skills.skills),
        model: nextModel,
        tools: modelTier === "local" ? [] : buildTools(body, personaConfig.personas, relevance, vision),
      },
      streamFn: models.streamSimple.bind(models),
      beforeToolCall,
      afterToolCall,
    })
    subscribe(agent)
    return true
  }

  /** 带 failover 的 prompt：连续失败 ≥3 次切换模型并用新模型重试当次输入一次。 */
  async function promptAgent(input: string): Promise<void> {
    try {
      await agent.prompt(input)
      llmFailures = 0
    } catch (err) {
      llmFailures++
      const msg = err instanceof Error ? err.message : String(err)
      if (llmFailures >= 3) {
        if (switchModel(`失败原因：${msg}`)) {
          await agent.prompt(input)
          return
        }
      }
      throw err
    }
  }

  if (VOICE_MODE) {
    await runVoiceLoop(body, promptAgent)
  } else {
    await runRepl(promptAgent)
  }
}

async function runRepl(prompt: (input: string) => Promise<void>): Promise<void> {
  const rl = createInterface({ input: stdin, output: stdout })
  console.log("\n[brain] 就绪。输入任务（如「帮我点一杯奶茶」），Ctrl+C 退出。\n")
  for (;;) {
    let line: string
    try {
      line = await rl.question("你> ")
    } catch {
      break
    }
    const input = line.trim()
    if (!input) continue
    if (input === "exit" || input === "quit") break
    // TC-14：「继续」恢复上次中断任务（success 终态不可续）；新任务输入 = 放弃旧任务
    if (input === "继续" || input.toLowerCase() === "continue") {
      if (!hasResumableRun()) {
        console.log("[brain] 没有可恢复的任务（无历史或上次已成功）")
        continue
      }
      const saved = resumeRun()
      if (saved) {
        resetSensitiveSession()
        try {
          console.log(`\n[brain] 恢复任务「${saved.goal}」，已带 ${saved.trace.length} 步历史`)
          console.log("助手> ")
          responseBuffer = ""
          await prompt(buildResumeContext(saved))
          process.stdout.write("\n\n")
          finishRun(getRun().finishVerified ? "success" : "closed")
        } catch (err) {
          process.stdout.write("\n")
          console.log(`[brain] 任务失败：${err instanceof Error ? err.message : String(err)}`)
          finishRun("crashed", err instanceof Error ? err.message : String(err))
        }
      }
      continue
    }
    beginRun(input)
    resetSensitiveSession()
    try {
      console.log("\n助手> ")
      responseBuffer = ""
      await prompt(input)
      process.stdout.write("\n\n")
      finishRun(getRun().finishVerified ? "success" : "closed")
    } catch (err) {
      process.stdout.write("\n")
      console.log(`[brain] 任务失败：${err instanceof Error ? err.message : String(err)}`)
      finishRun("crashed", err instanceof Error ? err.message : String(err))
    }
  }
  rl.close()
}

async function runVoiceLoop(body: BodyClient, prompt: (input: string) => Promise<void>): Promise<void> {
  console.log("\n[brain] 语音模式就绪。按躯体通知栏按钮触发对话。\n")
  let lastEventSeq = 0
  for (;;) {
    let events: BodyEvent[] = []
    try {
      events = await body.events(lastEventSeq)
    } catch {
      await sleep(2000)
      continue
    }
    for (const ev of events) {
      lastEventSeq = Math.max(lastEventSeq, ev.seq)
      if (ev.type === "voice" && (ev.payload as Record<string, unknown>)?.kind === "trigger") {
        await voiceTurn(body, prompt)
      }
    }
    await sleep(500)
  }
}

async function voiceTurn(body: BodyClient, prompt: (input: string) => Promise<void>): Promise<void> {
  try {
    const asr = await body.rpc<AsrResult>("speech.asr", {}, undefined, 75_000)
    if (!asr.text.trim()) return
    console.log(`你(语音)> ${asr.text}`)
    beginRun(asr.text)
    resetSensitiveSession()
    console.log("助手> ")
    responseBuffer = ""
    await prompt(asr.text)
    if (responseBuffer.trim()) {
      await body.rpc("speech.say", { text: responseBuffer.trim() })
    }
    finishRun(getRun().finishVerified ? "success" : "closed")
    console.log("\n---")
  } catch (err) {
    console.log(`[brain] 语音轮次失败：${err instanceof Error ? err.message : String(err)}`)
    finishRun("crashed", err instanceof Error ? err.message : String(err))
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}

// Kestrel CrashBreadcrumb 对应物：进程异常退出前把 run 落成 crashed 终态，断点续跑才有据可依
process.on("uncaughtException", (err) => {
  finishRun("crashed", `uncaught: ${err.message}`)
  console.error("[brain] 未捕获异常，已留痕退出：", err)
  process.exit(1)
})
process.on("unhandledRejection", (reason) => {
  finishRun("crashed", `unhandledRejection: ${reason instanceof Error ? reason.message : String(reason)}`)
  console.error("[brain] 未处理的 Promise 拒绝（已留痕，继续运行）：", reason)
})

main().catch((err) => {
  console.error(`[brain] 启动失败：${err instanceof Error ? err.stack : String(err)}`)
  process.exitCode = 1
})