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
let pauseRequested = false
/** U2-H03：brain boot 会话标识——重启后嵌入 taskId，UI 侧安全重置 seq 水位 */
let bootSession = `boot-${Date.now()}`
/** C-12（docs/16 §8）：run 终态已上报——迟到的 act/act_done 不再发送（UI 已收终态，迟到步进只添噪） */
let finished = false
/** C-12：事件发送串行链——seq 单调不保证到达序（HTTP 并发），act_done 晚于 finish 到达会被
 *  UI 的 seq 水线去重丢弃终态（UI 卡 RUNNING）。链内分配 seq + 顺序发送，双保险。 */
let emitChain: Promise<void> = Promise.resolve()

export function initBrainEvents(client: BodyClient): void {
  body = client
  bootSession = `boot-${Date.now()}`
  seq = 0
  finished = false
}

export function requestStop(): void {
  stopRequested = true
  pauseRequested = false // 停止优先于暂停
}

export function isStopRequested(): boolean {
  return stopRequested
}

export function clearStop(): void {
  stopRequested = false
}

/** I3：暂停/恢复——暂停阻断动作工具但可恢复（stop 是终态不可恢复） */
export function requestPause(): void {
  pauseRequested = true
}

export function resumeFromPause(): void {
  pauseRequested = false
}

export function isPaused(): boolean {
  return pauseRequested
}

async function emit(state: BrainEvent["state"], displayText: string, extra?: Partial<BrainEvent>): Promise<void> {
  if (!body) return
  // C-12：终态后只放行 heartbeat（连通性信号），步进/终态重复全丢弃
  if (finished && state !== "heartbeat") return
  const event: BrainEvent = {
    taskId,
    seq: ++seq,
    state,
    displayText: displayText.slice(0, 40),
    requiresUser: "none",
    timestamp: Date.now(),
    ...extra,
  }
  // C-12：入串行链——seq 分配与 HTTP 发送同序，杜绝并发乱序到达挤掉终态
  const sent: Promise<void> = emitChain.then(() => body!.rpc("brain.event", event).catch(() => undefined)).then(() => undefined)
  emitChain = sent.catch(() => undefined)
  return sent
}

export async function reportPromptStart(goal: string): Promise<void> {
  taskId = `task-${Date.now()}-${bootSession}`
  stepIndex = 0
  finished = false // C-12：新任务开启，恢复事件上报
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
      return `正在打开应用`
    case "control.typeText":
      return "正在输入文字"
    case "control.swipe":
      return "正在滑动屏幕"
    case "control.back":
      return "正在返回"
    case "control.home":
      return "正在回到桌面"
    case "skill.run":
      return `正在执行技能`
    default:
      return "正在操作屏幕"
  }
}

/** U2-B03：动作下发前上报（UI 显示"正在..."时动作即将开始）。 */
export async function reportActBefore(tool: string, args: Record<string, unknown>): Promise<void> {
  await emit("act", actDisplay(tool, args), { stepIndex: stepIndex + 1 })
}

/** U2-B03：动作结束后上报（UI 推进到下一步或显示结果）。 */
export async function reportActDone(tool: string, args: Record<string, unknown>): Promise<void> {
  stepIndex++
  await emit("act_done", `${stepIndex}. ${actDisplay(tool, args).replace("正在", "")}`, { stepIndex })
}

export async function reportHitlWait(text: string): Promise<void> {
  await emit("hitl_wait", text, { requiresUser: "confirm" })
}

export type FinishResultKind =
  | "success"
  | "failed"
  | "aborted" // 用户停止（UI 映射 STOPPED）
  | "paused" // 用户暂停（#26：暂停 settle 终态，UI 映射 PAUSED——不得再误报 success）
  | "stopped" // 保留（docs/12 枚举完整；stopped 与 aborted 语义分流时启用）
  | "unknown_side_effect" // 停止后设备状态未确认（保留，UI 已映射 FAILED·确认中）

export async function reportFinish(resultKind: FinishResultKind, text: string): Promise<void> {
  await emit("finish", text, { resultKind })
  finished = true // C-12：终态已入链——此后迟到的 act/act_done 全丢弃
}
