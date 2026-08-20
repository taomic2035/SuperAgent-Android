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
import { initBrainEvents, reportPromptStart, reportFinish, isStopRequested, clearStop, requestStop, requestPause, resumeFromPause } from "./ipc/brainEventReporter.ts"
import type { AsrResult, BodyEvent, SkillListResult } from "./ipc/types.ts"

const BODY_URL = env("BODY_URL", "http://127.0.0.1:8765")
const BODY_TOKEN = env("BODY_TOKEN", "super-agent-dev")
const PERSONA_NAME = env("PERSONA", "assistant")
const VOICE_MODE = env("VOICE_MODE", "0") === "1"

/** U2-#35：LLM 流超时（实测 GLM 可停滞 3-14min，120s 保护性 abort） */
const LLM_STREAM_TIMEOUT_MS = 120_000

let responseBuffer = ""

async function main(): Promise<void> {
  const body = new BodyClient(BODY_URL, BODY_TOKEN)
  initBrainEvents(body)
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
    // U2-B01：设备动作必须串行——pi 默认 parallel 可能并发下发多个 tap/swipe
    toolExecution: "sequential",

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
      toolExecution: "sequential",
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

  /** 带 failover 的 prompt：连续失败 ≥3 次切换模型；120s 流超时；自动注入匹配技能。 */
  async function promptAgent(input: string): Promise<void> {
    clearStop()
    // 技能路由增强：任务输入时自动检索匹配技能并注入提示（模型不用自觉查 skill.list）
    let enrichedInput = input
    try {
      const hits = await body.rpc<{ hits: Array<{ skill: { name: string; description: string } }> }>(
        "skill.search", { query: input }, undefined, 5_000,
      )
      if (hits.hits.length > 0) {
        const skillHint = hits.hits
          .slice(0, 3)
          .map((h) => `${h.skill.name}（${h.skill.description.slice(0, 30)}）`)
          .join("、")
        enrichedInput = `【技能匹配】${skillHint}。请优先 skill.run 执行。\n${input}`
        console.log(`[brain] 技能路由：${hits.hits.length} 个匹配`)
      }
    } catch { /* skill.search 失败不阻塞任务 */ }

    void reportPromptStart(input)
    // U2-#35：LLM 流停滞（GLM 4/4 实测卡 3-14min）——120s 超时 abort 防任务挂死
    const timeoutId = setTimeout(() => {
      try { agent.abort() } catch { /* 已完成 */ }
      console.log("[brain] LLM 流超时（120s），已 abort")
    }, LLM_STREAM_TIMEOUT_MS)
    try {
      await agent.prompt(enrichedInput)
      clearTimeout(timeoutId)
      llmFailures = 0
      if (isStopRequested()) {
        reportFinish("aborted", "用户已停止")
        finishRun("failed", "用户停止")
        return
      }
      // P1-01 语义一致：finishVerified → success；否则 closed（对话型收笔≠失败，不吓用户）
      if (getRun().finishVerified) {
        reportFinish("success", "任务完成")
      } else {
        reportFinish("success", "已回复") // UI 不显示"失败"——对话型收笔是正常行为
      }
    } catch (err) {
      clearTimeout(timeoutId)
      llmFailures++
      const msg = err instanceof Error ? err.message : String(err)
      if (llmFailures >= 3) {
        if (switchModel(`失败原因：${msg}`)) {
          await agent.prompt(enrichedInput)
          return
        }
      }
      reportFinish(isStopRequested() ? "aborted" : "failed", isStopRequested() ? "用户已停止" : msg.slice(0, 30))
      throw err
    }
  }

  // U2-B02：文字输入不依附 VOICE_MODE——常驻事件泵独立消费
  // U2-B04：心跳独立定时器——不被 await prompt() 阻塞
  const eventPump = async (): Promise<void> => {
    let lastEventSeq = 0
    let lastHeartbeat = Date.now()
    let lastConsumedTextInputId = ""
    // U2-#32：首次启动跳过历史事件（brain 重启不得重放旧指令——设水位为当前最大 seq）
    try {
      const existing = await body.events(0)
      if (existing.length > 0) {
        lastEventSeq = Math.max(...existing.map((e) => e.seq))
        console.log(`[brain] 跳过 ${existing.length} 条历史事件（水位 ${lastEventSeq}）`)
      }
    } catch { /* body 不可达时 waitForBody 会重试 */ }
    for (;;) {
      let events: BodyEvent[] = []
      try {
        events = await body.events(lastEventSeq)
      } catch {
        await sleep(3000)
        continue
      }
      if (Date.now() - lastHeartbeat > 5000) {
        lastHeartbeat = Date.now()
        void body.rpc("brain.event", {
          taskId: "heartbeat", seq: Date.now(), state: "heartbeat",
          displayText: "", requiresUser: "none", timestamp: Date.now(),
        }).catch(() => undefined)
      }
      for (const ev of events) {
        lastEventSeq = Math.max(lastEventSeq, ev.seq)
        if (ev.type !== "voice") continue
        const kind = (ev.payload as Record<string, unknown>)?.kind
        if (kind === "text_input") {
          // UX-02 §5.2.4 契约：同一指令只入队一次——用事件 seq 作为 commandId 去重
          const commandId = `ev-${ev.seq}`
          if (commandId === lastConsumedTextInputId) continue
          lastConsumedTextInputId = commandId
          const text = String((ev.payload as Record<string, unknown>)?.text ?? "").trim()
          if (text) {
            console.log(`你(输入)> ${text}`)
            beginRun(text)
            resetSensitiveSession()
            console.log("助手> ")
            responseBuffer = ""
            try {
              await promptAgent(text)
              process.stdout.write("\n\n")
              finishRun(getRun().finishVerified ? "success" : "closed")
            } catch (err) {
              console.log(`[brain] 任务失败：${err instanceof Error ? err.message : String(err)}`)
              finishRun("crashed", err instanceof Error ? err.message : String(err))
            }
          }
        } else if (kind === "stop_request") {
          requestStop()
          try { agent.abort() } catch { /* agent 可能已完成 */ }
          console.log("[brain] 收到用户停止请求，已 abort 当前运行")
        } else if (kind === "pause_request") {
          // I3：暂停——阻断下一动作（beforeToolCall 检查 isPaused），当前动作完成自然停
          requestPause()
          console.log("[brain] 收到暂停请求，下一动作将等待恢复")
        } else if (kind === "resume_request") {
          resumeFromPause()
          console.log("[brain] 收到恢复请求，继续执行")
        } else if (kind === "barge_in") {
          console.log("[brain] 用户打断了播报（barge-in），等待下一轮输入")
        }
      }
      await sleep(500)
    }
  }

  if (VOICE_MODE) {
    await runVoiceLoop(body, promptAgent)
  } else {
    // U2-B02：REPL 模式也启动事件泵——悬浮层文字指令可被消费
    void eventPump()
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
      // text_input/stop_request/heartbeat 由 eventPump 常驻处理（U2-B02/B04）
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
    // U2-H06：TTS 只播报关键节点（收到/需处理/完成/失败），不逐步朗读模型全文
    // 全文可能含金额/账号/验证码——用 redactText 脱敏 + 截断
    if (responseBuffer.trim()) {
      const { redactText } = await import("./guards/redact.ts")
      const safe = redactText(responseBuffer.trim().slice(0, 120))
      await body.rpc("speech.say", { text: safe }).catch(() => undefined)
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