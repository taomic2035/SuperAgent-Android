import type { BodyClient } from "../ipc/client.ts"
import { redactText } from "../guards/redact.ts"

/**
 * ME-3a lessons 自动采集（docs/15 §5 gate 钩子）：gate 拦截/技能失配时规则化构造 lesson 记忆
 * （不走 LLM——事件语义确定）。fire-and-forget：失败静默，不得影响任务路径。
 */

export async function recordLesson(body: BodyClient, topic: string, content: string): Promise<void> {
  try {
    // ME 隐私红线：打码命中即不入库（与 remember/reflect 同规则）
    if (redactText(topic) !== topic || redactText(content) !== content) return
    await body.rpc("memory.write", { kind: "lesson", topic, content, source: "gate-lesson" })
  } catch {
    /* 教训采集失败静默 */
  }
}

export interface LessonDraft {
  topic: string
  content: string
}

/** COMMIT_BOUNDARY 拦截 → lesson（区分敏感会话确认路径与支付红线转人工路径） */
export function commitBoundaryLesson(label: string, sensitive: boolean): LessonDraft {
  return sensitive
    ? {
        topic: `敏感拦截:${label}`,
        content: `「${label}」在敏感会话中被拦——需 hitl.confirm 用户确认后重试，用户拒绝则转人工`,
      }
    : {
        topic: `提交边界:${label}`,
        content: `「${label}」是提交边界动作（支付/提交类红线），不可自动执行，应转人工`,
      }
}

/** SKILL_STALE 回放失配 → lesson（界面变化提示，供下次任务优先现场规划） */
export function skillStaleLesson(name: string, reason: string): LessonDraft {
  return {
    topic: `技能失配:${name}`,
    content: `技能 ${name} 回放失配（${reason.slice(0, 60)}）——界面已变化，从失配处现场续走而非重跑`,
  }
}
