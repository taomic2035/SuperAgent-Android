import type { AfterToolCallContext, BeforeToolCallContext } from "@earendil-works/pi-agent-core"
import { addTrace, getRun } from "../runState.ts"
import type { ScreenResult } from "../ipc/types.ts"
import { redactText } from "./redact.ts"
import { ReActGuard } from "./reactGuard.ts"

/** 驳回证据入 trace 前的脱敏（身份证/卡号/密码类值不打入历史）。 */
function redactForTrace(s: string): string {
  return redactText(s).slice(0, 80)
}

/**
 * brain 守卫层（AD-01 + AD-05）：
 * - 不持有任何业务词表，提交边界/敏感会话判定权威单点在 body（Guard.kt）。
 * - beforeToolCall：ReAct 止损（参照 Kestrel ReActGuard）。
 * - afterToolCall：trace 追踪（含 resultKind/sensitive）+ 喂 ReActGuard。
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

/**
 * 止损豁免：感知/收尾/人机通道不拦。否则止损提示让模型"重新感知屏幕评估、
 * task.finish 声明失败或 hitl.handoff"，而这些调用本身又被拦——死锁出不去。
 */
const ABORT_EXEMPT_TOOLS = new Set([
  "perceive.screen",
  "task.finish",
  "hitl.confirm",
  "hitl.ask",
  "hitl.handoff",
  "speech.say",
  "speech.interrupt",
])

let lastScreenSig = ""
const reactGuard = new ReActGuard()

export function resetSensitiveSession(): void {
  reactGuard.reset()
}

export function resetGuard(): void {
  reactGuard.reset()
}

/**
 * task.finish 证据驳回留痕（Kestrel 语义，AD-06 采纳）：
 * 驳回计入 trace（resultKind=finish_rejected，含驳回原因与证据原文——复盘"模型为何谎报"必需）
 * + 计入止损全局步数，且前后签名相同推高 NoProgress——反复谎报完成本身就是一种"无进展"。
 */
export function markFinishRejected(evidence: string, reason: string): void {
  addTrace({
    tool: "task.finish",
    args: { evidence: redactForTrace(evidence), reason: reason.slice(0, 80) },
    located: false,
    signature: lastScreenSig,
    timestamp: Date.now(),
    resultKind: "finish_rejected",
  })
  reactGuard.record("task.finish", { evidence, reason }, lastScreenSig, lastScreenSig)
}

export async function beforeToolCall(
  context: BeforeToolCallContext,
): Promise<{ block: true; reason: string; terminate: true } | undefined> {
  if (ABORT_EXEMPT_TOOLS.has(context.toolCall.name)) return undefined
  // AD-05：ReAct 止损。命中 → 强制终止本轮，交回模型处理（模型应据 reason 决定 finish(false) 或 NeedsHuman）。
  const abort = reactGuard.shouldAbort()
  if (abort) {
    return {
      block: true,
      reason: `ReAct 止损：${abort}（本轮已执行 ${reactGuard.totalSteps} 步）。请重新感知屏幕评估现状，若任务无法继续请 task.finish 声明失败或 hitl.handoff 转人工。`,
      terminate: true,
    }
  }
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

  // AD-01：敏感态判定权威在 body（perceive.screen 下发 sensitiveSession），brain 不镜像。
  if (context.toolCall.name === "perceive.screen") {
    const content = context.result?.content
    if (content && Array.isArray(content)) {
      for (const c of content) {
        if (c.type === "text") {
          try {
            const screen = JSON.parse(c.text) as ScreenResult
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
