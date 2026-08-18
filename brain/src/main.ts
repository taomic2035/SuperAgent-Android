import { Agent } from "@earendil-works/pi-agent-core"
import { createInterface } from "node:readline/promises"
import { stdin, stdout } from "node:process"
import { BodyClient } from "./ipc/client.ts"
import { resolveModel } from "./model.ts"
import { buildTools } from "./tools/index.ts"
import { beforeToolCall, afterToolCall } from "./guards/index.ts"
import { loadPersonas } from "./personas/personas.ts"
import { buildSystemPrompt } from "./personas/promptBuilder.ts"
import { SkillIndex } from "./skills/index.ts"
import { beginRun } from "./runState.ts"
import { env, envInt } from "./env.ts"
import type { SkillListResult } from "./ipc/types.ts"

const BODY_URL = env("BODY_URL", "http://127.0.0.1:8765")
const BODY_TOKEN = env("BODY_TOKEN", "super-agent-dev")
const PERSONA_NAME = env("PERSONA", "assistant")

async function main(): Promise<void> {
  const body = new BodyClient(BODY_URL, BODY_TOKEN)
  console.log(`[brain] 等待躯体服务 ${BODY_URL} ...`)
  await body.waitForBody()
  console.log("[brain] 躯体已连接")

  const personaConfig = loadPersonas()
  const persona = personaConfig.personas[PERSONA_NAME] ?? personaConfig.personas.assistant
  console.log(`[brain] 角色：${persona.name}（${PERSONA_NAME}）`)

  let skills: SkillListResult = { skills: [] }
  try {
    skills = await body.rpc<SkillListResult>("skill.list", {})
  } catch (err) {
    console.log(`[brain] 技能目录读取失败（不影响启动）：${err instanceof Error ? err.message : String(err)}`)
  }
  const skillIndex = new SkillIndex()
  skillIndex.rebuild(skills.skills)
  console.log(`[brain] 已加载技能 ${skills.skills.length} 个`)

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
    }
  })

  const rl = createInterface({ input: stdin, output: stdout })
  console.log("\n[brain] 就绪。输入任务（如「帮我点一杯奶茶」），Ctrl+C 退出。\n")

  for (;;) {
    const line = await rl.question("你> ")
    const input = line.trim()
    if (!input) continue
    if (input === "exit" || input === "quit") break
    beginRun(input)
    try {
      console.log("\n助手> ")
      await agent.prompt(input)
      process.stdout.write("\n\n")
    } catch (err) {
      process.stdout.write("\n")
      console.log(`[brain] 任务失败：${err instanceof Error ? err.message : String(err)}`)
    }
  }
  rl.close()
}

main().catch((err) => {
  console.error(`[brain] 启动失败：${err instanceof Error ? err.stack : String(err)}`)
  process.exitCode = 1
})