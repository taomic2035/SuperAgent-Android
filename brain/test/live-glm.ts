/**
 * GLM live tool-call 测试（TL-03 / M5 验收项）。
 * 手动触发：GLM_API_KEY=你的key npx tsx test/live-glm.ts
 * 验证：主模型（历史验证 GLM-4.6v；当前配置见 SESSION.md）经 OpenAI 兼容层完成至少一次 tool_call 往返。
 */
import assert from "node:assert/strict"
import { startMockBody } from "./mock-body.ts"
import { BodyClient } from "../src/ipc/client.ts"
import { resolveModel } from "../src/model.ts"
import { buildTools } from "../src/tools/index.ts"
import { beforeToolCall, afterToolCall } from "../src/guards/index.ts"
import { loadPersonas } from "../src/personas/personas.ts"
import { buildSystemPrompt } from "../src/personas/promptBuilder.ts"
import { Agent } from "@earendil-works/pi-agent-core"
import type { SkillListResult } from "../src/ipc/types.ts"

async function main(): Promise<void> {
  if (!process.env.GLM_API_KEY) {
    console.error("请设置 GLM_API_KEY 环境变量")
    process.exitCode = 1
    return
  }

  console.log("== GLM Live Tool-Call 测试 ==")
  const mock = await startMockBody({ port: 0 })
  try {
    const body = new BodyClient(`http://127.0.0.1:${mock.port}`, "super-agent-dev")
    await body.waitForBody()

    const personaConfig = loadPersonas()
    const skills: SkillListResult = { skills: [] }
    try {
      const sl = await body.rpc<SkillListResult>("skill.list", {})
      Object.assign(skills, sl)
    } catch { /* mock 可能有也可能没有 */ }

    const { models, model, label } = resolveModel()
    console.log(`模型: ${label}`)

    let toolCalled = false
    const agent = new Agent({
      initialState: {
        systemPrompt: buildSystemPrompt(personaConfig.personas.assistant, skills.skills),
        model,
        tools: buildTools(body, personaConfig.personas),
      },
      streamFn: models.streamSimple.bind(models),
      beforeToolCall,
      afterToolCall,
    })

    agent.subscribe((event) => {
      if (event.type === "tool_execution_start") toolCalled = true
      if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta") {
        process.stdout.write(event.assistantMessageEvent.delta)
      }
    })

    console.log("\n发送: 感知一下当前屏幕\n")
    await agent.prompt("感知一下当前屏幕，告诉我你在什么应用里")

    assert.ok(toolCalled, "GLM 必须至少调用一次工具")
    console.log("\n\n✓ GLM tool-call 往返成功")
    console.log("✓ live 测试通过")
  } finally {
    await mock.close()
  }
}

main().catch((err) => {
  console.error("live 测试失败:", err)
  process.exitCode = 1
})