import { Agent } from "@earendil-works/pi-agent-core"
import type { Model, MutableModels } from "@earendil-works/pi-ai"

/**
 * BR-04.3 证据相关性校验（防"随便指新文字交差"：任务点奶茶、证据却是无关新弹窗文字）。
 * 独立判官 Agent 单轮判定；**fail-open**——审查调用失败时放行（存在性+新颖性仍是硬门，
 * 相关性是软增强，不得因审查服务抖动卡死 finish）。
 */

export interface RelevanceVerdict {
  ok: boolean
  reason: string
}

export type RelevanceCheck = (goal: string, evidence: string, screenTexts: string[]) => Promise<RelevanceVerdict>

export function buildLlmRelevanceCheck(models: MutableModels, model: Model<"openai-completions">): RelevanceCheck {
  return async (goal, evidence, screenTexts) => {
    let out = ""
    const judge = new Agent({
      initialState: {
        systemPrompt:
          "你是任务完成证据的相关性审查员。判断给定的屏幕证据文字是否真的与任务目标的达成相关" +
          "（防模型随便挑屏幕上无关的新文字冒充完成证据，如任务点奶茶却拿「猜你喜欢」当证据）。" +
          "只输出一行，严格二选一：PASS 或 FAIL:原因（原因≤20字）",
        model,
        tools: [],
      },
      streamFn: models.streamSimple.bind(models),
    })
    judge.subscribe((event) => {
      if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta") {
        out += event.assistantMessageEvent.delta
      }
    })
    await judge.prompt(
      `任务目标：${goal}\n声明的完成证据：${evidence}\n当前屏幕可见文字节选：${screenTexts.slice(0, 12).join(" | ")}`,
    )
    const answer = out.trim()
    if (/^PASS/i.test(answer)) return { ok: true, reason: "" }
    const reason = answer.replace(/^FAIL[:：]?/i, "").trim()
    return { ok: false, reason: reason || "证据与目标不相关" }
  }
}
