import type { AfterToolCallContext, BeforeToolCallContext } from "@earendil-works/pi-agent-core"
import { addTrace, getRun } from "../runState.ts"
import type { ScreenResult } from "../ipc/types.ts"
import { ReActGuard } from "./reactGuard.ts"

/**
 * brain 守卫层（AD-01 + AD-05）：
 * - 不持有任何业务词表，提交边界/敏感会话判定权威单点在 body（Guard.kt）。
 * - beforeToolCall：ReAct 止损（参照 Kestrel ReActGuard）+ 读 body 下发的敏感会话标记。
 * - afterToolCall：trace 追踪（含 resultKind/sensitive）+ 喂 ReActGuard + 镜像敏感态。
 */

const TRACE_TOOLS = new Set([
  "control.tap",
  "control.longPress",
  "control.swipe",
  "control.typeText",
  "control.selectOption",
  "control.selectSpec",
  "control.back",
  "control.home",
  "control.launch",
])

let inSensitiveSession = false
let lastScreenSig = ""
const reactGuard = new ReActGuard()

export function resetSensitiveSession(): void {
  inSensitiveSession = false
  reactGuard.reset()
}

export function resetGuard(): void {
  reactGuard.reset()
}

export async function beforeToolCall(
  context: BeforeToolCallContext,
): Promise<{ block: true; reason: string; terminate: true } | undefined> {
  // AD-05：ReAct 止损。命中 → 强制终止本轮，交回模型处理（模型应据 reason 决定 finish(false) 或 NeedsHuman）。
  const abort = reactGuard.shouldAbort()
  if (abort) {
    return {
      block: true,
      reason: `ReAct 止损：${abort}（已执行 ${reactGuard.totalSteps} 步）。请重新感知屏幕评估现状，若任务无法继续请 task.finish 声明失败或 hitl.handoff 转人工。`,
      terminate: true,
    }
  }
  void context
  void inSensitiveSession
  return undefined
}

export async function afterToolCall(context: AfterToolCallContext): Promise<undefined> {
  if (context.isError) return undefined

  if (TRACE_TOOLS.has(context.toolCall.name)) {
    const details = (context.result?.details ?? {}) as { located?: boolean; signature?: string }
    const sigAfter = details.signature ?? lastScreenSig
    const resultKind = details.located === false ? "not_located" : "ok"
    addTrace({
      tool: context.toolCall.name,
      args: (context.args as Record<string, unknown>) ?? {},
      located: details.located ?? true,
      signature: details.signature,
      timestamp: Date.now(),
      resultKind,
    })
    // 喂 ReActGuard：sigBefore=上一帧签名，sigAfter=本步返回签名（无则视为未变）
    reactGuard.record(
      context.toolCall.name,
      (context.args as Record<string, unknown>) ?? {},
      lastScreenSig,
      sigAfter,
    )
  }

  // AD-01：敏感态纯靠 perceive.screen 下发的 sensitiveSession 镜像，不自行判敏感 App。
  if (context.toolCall.name === "control.home") {
    inSensitiveSession = false
  }

  if (context.toolCall.name === "perceive.screen") {
    const content = context.result?.content
    if (content && Array.isArray(content)) {
      for (const c of content) {
        if (c.type === "text") {
          try {
            const screen = JSON.parse(c.text) as ScreenResult
            inSensitiveSession = screen.sensitiveSession === true
            lastScreenSig = screen.signature ?? lastScreenSig
          } catch {
            // parse failed, ignore
          }
        }
      }
    }
  }

  return undefined
}
