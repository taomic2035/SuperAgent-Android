import type { ScreenResult, TraceStep } from "./ipc/types.ts"
import { env } from "./env.ts"
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs"
import { join } from "node:path"

export interface RunState {
  goal: string
  baselineScreen?: ScreenResult
  trace: TraceStep[]
  startedAt: number
}

const STATE_DIR = env("SUPER_AGENT_STATE_DIR", join(process.env.HOME ?? process.cwd(), ".super-agent"))
const STATE_FILE = join(STATE_DIR, "runstate.json")

let current: RunState | null = null

export function beginRun(goal: string): void {
  current = { goal, trace: [], startedAt: Date.now() }
  persist()
}

export function getRun(): RunState {
  if (!current) throw new Error("run 未初始化，请先 beginRun")
  return current
}

export function setBaseline(screen: ScreenResult): void {
  if (!current) return
  if (!current.baselineScreen) current.baselineScreen = screen
  persist()
}

export function addTrace(step: TraceStep): void {
  if (!current) return
  current.trace.push(step)
  persist()
}

export function resetRun(): void {
  current = null
  clearPersisted()
}

export function hasResumableRun(): boolean {
  return loadPersisted() !== null
}

export function resumeRun(): RunState | null {
  const saved = loadPersisted()
  if (saved) current = saved
  return saved
}

function persist(): void {
  if (!current) return
  try {
    mkdirSync(STATE_DIR, { recursive: true })
    writeFileSync(STATE_FILE, JSON.stringify(current), "utf8")
  } catch {
    // 落盘失败不阻断运行
  }
}

function loadPersisted(): RunState | null {
  try {
    if (!existsSync(STATE_FILE)) return null
    const raw = readFileSync(STATE_FILE, "utf8")
    return JSON.parse(raw) as RunState
  } catch {
    return null
  }
}

function clearPersisted(): void {
  try {
    if (existsSync(STATE_FILE)) writeFileSync(STATE_FILE, "", "utf8")
  } catch {
    // 清理失败不阻断
  }
}