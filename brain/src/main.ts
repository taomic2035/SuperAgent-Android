import { Agent } from "@earendil-works/pi-agent-core"
import { createInterface } from "node:readline/promises"
import { stdin, stdout } from "node:process"
import { BodyClient } from "./ipc/client.ts"
import { resolveModel } from "./model.ts"
import { buildTools } from "./tools/index.ts"
import { beforeToolCall, afterToolCall, resetSensitiveSession } from "./guards/index.ts"
import { loadPersonas } from "./personas/personas.ts"
import { buildSystemPrompt } from "./personas/promptBuilder.ts"
import { beginRun, hasResumableRun, resumeRun, finishRun } from "./runState.ts"
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

  if (hasResumableRun()) {
    const saved = resumeRun()
    if (saved) console.log(`[brain] 发现有未完成任务：「${saved.goal}」（${saved.trace.length} 步），可继续输入续跑`)
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

  const { models, model, label } = resolveModel()
  console.log(`[brain] 模型：${label}`)

  const agent = new Agent({
    initialState: {
      systemPrompt: buildSystemPrompt(persona, skills.skills),
      model,
      tools: buildTools(body, personaConfig.personas),
    },
    streamFn: models.streamSimple.bind(models),
    beforeToolCall,
    afterToolCall,
  })

  agent.subscribe((event) => {
    if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta") {
      process.stdout.write(event.assistantMessageEvent.delta)
      responseBuffer += event.assistantMessageEvent.delta
    }
  })

  if (VOICE_MODE) {
    await runVoiceLoop(body, agent)
  } else {
    await runRepl(agent)
  }
}

async function runRepl(agent: Agent): Promise<void> {
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
    beginRun(input)
    resetSensitiveSession()
    try {
      console.log("\n助手> ")
      responseBuffer = ""
      await agent.prompt(input)
      process.stdout.write("\n\n")
      finishRun("success")
    } catch (err) {
      process.stdout.write("\n")
      console.log(`[brain] 任务失败：${err instanceof Error ? err.message : String(err)}`)
      finishRun("crashed", err instanceof Error ? err.message : String(err))
    }
  }
  rl.close()
}

async function runVoiceLoop(body: BodyClient, agent: Agent): Promise<void> {
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
        await voiceTurn(body, agent)
      }
    }
    await sleep(500)
  }
}

async function voiceTurn(body: BodyClient, agent: Agent): Promise<void> {
  try {
    const asr = await body.rpc<AsrResult>("speech.asr", {})
    if (!asr.text.trim()) return
    console.log(`你(语音)> ${asr.text}`)
    beginRun(asr.text)
    resetSensitiveSession()
    console.log("助手> ")
    responseBuffer = ""
    await agent.prompt(asr.text)
    if (responseBuffer.trim()) {
      await body.rpc("speech.say", { text: responseBuffer.trim() })
    }
    finishRun("success")
    console.log("\n---")
  } catch (err) {
    console.log(`[brain] 语音轮次失败：${err instanceof Error ? err.message : String(err)}`)
    finishRun("crashed", err instanceof Error ? err.message : String(err))
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}

main().catch((err) => {
  console.error(`[brain] 启动失败：${err instanceof Error ? err.stack : String(err)}`)
  process.exitCode = 1
})