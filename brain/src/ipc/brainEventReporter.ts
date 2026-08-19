import type { BodyClient } from "./client.ts"
import type { BrainEvent } from "./types.ts"

/**
 * UI-0 事件埋点（docs/12 §7 契约 / CT-06）：brain 按 BrainEvent 类型化上报 body，
 * 悬浮层经 UiBus 渲染。displayText 一律用户可读语义（禁坐标/工具名/JSON）；
 * fire-and-forget（上报失败不影响任务）。
 */
let body: BodyClient | null = null
let seq = 0
let taskId = "none"
let stepIndex = 0
let stopRequested = false

export function initBrainEvents(client: BodyClient): void {
  body = client
}

export function requestStop(): void {
  stopRequested = true
}

export function isStopRequested(): boolean {
  return stopRequested
}

export function clearStop(): void {
  stopRequested = false
}

async function emit(state: BrainEvent["state"], displayText: string, extra?: Partial<BrainEvent>): Promise<void> {
  if (!body) return
  const event: BrainEvent = {
    taskId,
    seq: ++seq,
    state,
    displayText: displayText.slice(0, 40),
    requiresUser: "none",
    timestamp: Date.now(),
    ...extra,
  }
  await body.rpc("brain.event", event).catch(() => undefined)
}

export async function reportPromptStart(goal: string): Promise<void> {
  taskId = `task-${Date.now()}`
  stepIndex = 0
  await emit("prompt_start", `目标：${goal.slice(0, 20)}`)
}

/** 步骤显示文案：控件语义优先（docs/12 §3.1），禁坐标/工具名。 */
export function actDisplay(tool: string, args: Record<string, unknown>): string {
  const label = typeof args?.label === "string" ? args.label : undefined
  switch (tool) {
    case "control.selectOption":
    case "control.selectSpec":
      return `正在选择「${label ?? "选项"}」`
    case "control.launch":
      return typeof args?.pkg === "string" ? `正在打开应用` : "正在打开应用"
    case "control.typeText":
      return "正在输入文字"
    case "control.swipe":
      return "正在滑动屏幕"
    case "control.back":
      return "正在返回"
    case "control.home":
      return "正在回到桌面"
    case "skill.run":
      return typeof args?.name === "string" ? `正在执行技能` : "正在执行技能"
    default:
      return "正在操作屏幕"
  }
}

export async function reportAct(tool: string, args: Record<string, unknown>): Promise<void> {
  await emit("act", `第 ${++stepIndex} 步 · ${actDisplay(tool, args)}`)
}

export async function reportHitlWait(text: string): Promise<void> {
  await emit("hitl_wait", text, { requiresUser: "confirm" })
}

export async function reportFinish(resultKind: "success" | "failed" | "aborted", text: string): Promise<void> {
  await emit("finish", text, { resultKind })
}
