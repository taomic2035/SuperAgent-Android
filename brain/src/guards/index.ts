import type { AfterToolCallContext, BeforeToolCallContext } from "@earendil-works/pi-agent-core"
import { isCommitBoundary, isSensitiveApp, isSensitiveSessionAction } from "./commitBoundary.ts"
import { addTrace, getRun } from "../runState.ts"
import type { ScreenResult } from "../ipc/types.ts"

const LABEL_TOOLS = new Set(["control.selectOption", "control.selectSpec"])
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

export function resetSensitiveSession(): void {
  inSensitiveSession = false
}

export async function beforeToolCall(
  context: BeforeToolCallContext,
): Promise<{ block: true; reason: string; terminate: true } | undefined> {
  if (LABEL_TOOLS.has(context.toolCall.name)) {
    const label = String((context.args as Record<string, unknown>)?.label ?? "")
    if (isCommitBoundary(label)) {
      return {
        block: true,
        reason: `提交边界：目标「${label}」属于不可逆操作（支付/下单确认），绝不自动触碰。请调用 hitl.handoff 转人工接管。`,
        terminate: true,
      }
    }
    if (inSensitiveSession && isSensitiveSessionAction(label) && !isCommitBoundary(label)) {
      return {
        block: true,
        reason: `敏感会话内确认动作：「${label}」在敏感应用中需人工确认。请调用 hitl.confirm 请求用户确认后再执行。`,
        terminate: true,
      }
    }
  }
  return undefined
}

export async function afterToolCall(context: AfterToolCallContext): Promise<undefined> {
  if (context.isError) return undefined

  if (TRACE_TOOLS.has(context.toolCall.name)) {
    const details = (context.result?.details ?? {}) as { located?: boolean; signature?: string }
    addTrace({
      tool: context.toolCall.name,
      args: (context.args as Record<string, unknown>) ?? {},
      located: details.located ?? true,
      signature: details.signature,
      timestamp: Date.now(),
    })
  }

  if (context.toolCall.name === "control.launch") {
    const pkg = String((context.args as Record<string, unknown>)?.pkg ?? "")
    if (pkg && isSensitiveApp(pkg)) {
      inSensitiveSession = true
    } else if (pkg) {
      inSensitiveSession = false
    }
  }

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
            if (screen.sensitiveSession) {
              inSensitiveSession = true
            }
          } catch {
            // parse failed, ignore
          }
        }
      }
    }
  }

  return undefined
}
