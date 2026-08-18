import type { ScreenResult, TraceStep } from "./ipc/types.ts"

export interface RunState {
  goal: string
  baselineScreen?: ScreenResult
  trace: TraceStep[]
  startedAt: number
}

let current: RunState | null = null

export function beginRun(goal: string): void {
  current = { goal, trace: [], startedAt: Date.now() }
}

export function getRun(): RunState {
  if (!current) throw new Error("run 未初始化，请先 beginRun")
  return current
}

export function setBaseline(screen: ScreenResult): void {
  if (!current) return
  if (!current.baselineScreen) current.baselineScreen = screen
}

export function addTrace(step: TraceStep): void {
  if (!current) return
  current.trace.push(step)
}

export function resetRun(): void {
  current = null
}