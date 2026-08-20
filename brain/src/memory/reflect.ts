import { Agent } from "@earendil-works/pi-agent-core"
import type { Model, MutableModels } from "@earendil-works/pi-ai"
import type { BodyClient } from "../ipc/client.ts"
import { redactText } from "../guards/redact.ts"

/**
 * ME-2 反思提取器（docs/15 §5）：task.finish 证据核验通过后异步执行——
 * 独立 Agent 单轮从 goal+summary+工具序列提取 0-2 条 candidate 记忆入库（source=run:*）。
 * fire-and-forget：所有失败只记日志，绝不影响任务完成路径。
 */

export interface ReflectInput {
  goal: string
  summary: string
  tools: string[]
}

export type Reflector = (input: ReflectInput) => Promise<void>

export interface MemoryCandidate {
  kind: "fact" | "preference" | "lesson" | "routine"
  topic: string
  content: string
}

const REFLECT_SYSTEM_PROMPT =
  "你是记忆提取器。从刚完成的手机任务中提取值得长期记住的用户事实/偏好/习惯流程/踩坑教训" +
  "（如「用户奶茶要少糖」「快递放前台」「美团下单会遇滑块，改走系统设置验证」）。" +
  '只输出 JSON 数组（0-2 条，无可提取输出 []），每条形如 {"kind":"fact|preference|lesson|routine","topic":"归并键（≤12字，如 奶茶口味）","content":"陈述句（≤60字）"}。' +
  "只记对未来有用的稳定信息，不记任务流水账；不含个人敏感信息（身份证/卡号/密码/验证码）。"

export function buildReflector(models: MutableModels, model: Model<"openai-completions">, body: BodyClient): Reflector {
  return async (input) => {
    try {
      let out = ""
      const extractor = new Agent({
        initialState: { systemPrompt: REFLECT_SYSTEM_PROMPT, model, tools: [] },
        streamFn: models.streamSimple.bind(models),
      })
      extractor.subscribe((event) => {
        if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta") {
          out += event.assistantMessageEvent.delta
        }
      })
      await extractor.prompt(
        `任务目标：${input.goal}\n完成摘要：${input.summary}\n工具序列：${input.tools.slice(0, 30).join(" → ")}`,
      )
      for (const c of parseCandidates(out)) {
        // ME 隐私红线（docs/15 §6）：写入前过 redact——打码命中即拒绝入库
        if (redactText(c.content) !== c.content || redactText(c.topic) !== c.topic) continue
        await body.rpc("memory.write", {
          kind: c.kind,
          topic: c.topic,
          content: c.content,
          source: `run:${input.goal.slice(0, 24)}`,
        })
      }
    } catch (err) {
      console.warn(`[brain] reflect 记忆提取失败（不影响任务）：${err instanceof Error ? err.message : String(err)}`)
    }
  }
}

/** 解析提取器输出：截取首个 JSON 数组段（容忍前后缀说明文字），字段非法的条目丢弃。导出供单测。 */
export function parseCandidates(raw: string): MemoryCandidate[] {
  const start = raw.indexOf("[")
  const end = raw.lastIndexOf("]")
  if (start < 0 || end <= start) return []
  let parsed: unknown
  try {
    parsed = JSON.parse(raw.slice(start, end + 1))
  } catch {
    return []
  }
  if (!Array.isArray(parsed)) return []
  const kinds = new Set(["fact", "preference", "lesson", "routine"])
  return parsed
    .filter((x): x is Record<string, unknown> => {
      if (typeof x !== "object" || x === null) return false
      const c = x as Record<string, unknown>
      return (
        typeof c.kind === "string" && kinds.has(c.kind) &&
        typeof c.topic === "string" && c.topic.trim().length > 0 &&
        typeof c.content === "string" && c.content.trim().length > 0
      )
    })
    .map((c) => ({
      kind: c.kind as MemoryCandidate["kind"],
      topic: String(c.topic).trim().slice(0, 32),
      content: String(c.content).trim().slice(0, 200),
    }))
}
