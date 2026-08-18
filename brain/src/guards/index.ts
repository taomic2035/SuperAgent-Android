import type { AfterToolCallContext, BeforeToolCallContext } from "@earendil-works/pi-agent-core"
import { checkPaymentTarget } from "./payment.ts"
import { addTrace } from "../runState.ts"

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

export async function beforeToolCall(
  context: BeforeToolCallContext,
): Promise<{ block: true; reason: string; terminate: true } | undefined> {
  if (LABEL_TOOLS.has(context.toolCall.name)) {
    const label = String((context.args as Record<string, unknown>)?.label ?? "")
    if (checkPaymentTarget(label)) {
      return {
        block: true,
        reason: `支付红线：目标「${label}」属于支付操作，绝不自动触碰。请调用 hitl.handoff 转人工接管。`,
        terminate: true,
      }
    }
  }
  return undefined
}

export async function afterToolCall(context: AfterToolCallContext): Promise<undefined> {
  if (!context.isError && TRACE_TOOLS.has(context.toolCall.name)) {
    const details = (context.result?.details ?? {}) as { located?: boolean; signature?: string }
    addTrace({
      tool: context.toolCall.name,
      args: (context.args as Record<string, unknown>) ?? {},
      located: details.located ?? true,
      signature: details.signature,
      timestamp: Date.now(),
    })
  }
  return undefined
}