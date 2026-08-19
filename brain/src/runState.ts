import type { ScreenResult, TraceStep } from "./ipc/types.ts"
import { env } from "./env.ts"
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs"
import { join } from "node:path"

/**
 * 会话持久化与断点续跑（AD-05）。
 * 参照 Kestrel RunTraceBuilder：失败可见性留痕（成功/失败/崩溃都落全历史）、
 * trace 单调序号、脱敏后落盘。pi AgentHarness/Session 不用（见 AD-05）。
 */

export type RunOutcome = "success" | "failed" | "crashed" | "needs_human"

export interface RunState {
  goal: string
  baselineScreen?: ScreenResult
  trace: TraceStep[]
  startedAt: number
  /** 结束时填充（成功/失败/崩溃），落盘后供恢复判断。 */
  outcome?: RunOutcome
  failureReason?: string
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

/** 标记 run 结束（成功/失败/崩溃都调），落盘全历史。 */
export function finishRun(outcome: RunOutcome, failureReason?: string): void {
  if (!current) return
  current.outcome = outcome
  current.failureReason = failureReason
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
    // 落盘前脱敏：goal/failureReason 抹 PII，trace 的 args 只留工具名+located（不含载荷）
    const redacted: RunState = {
      goal: redactGoal(current.goal),
      baselineScreen: undefined, // baseline 含完整屏幕文本，不入盘
      trace: current.trace.map((s) => ({
        tool: s.tool,
        args: {}, // 载荷可能含 PII，落盘只留工具名
        located: s.located,
        signature: s.signature,
        timestamp: s.timestamp,
        sensitive: s.sensitive,
        resultKind: s.resultKind,
      })),
      startedAt: current.startedAt,
      outcome: current.outcome,
      failureReason: current.failureReason ? redactGoal(current.failureReason) : undefined,
    }
    writeFileSync(STATE_FILE, JSON.stringify(redacted), "utf8")
  } catch {
    // 落盘失败不阻断运行（与 Kestrel NoOpTraceSink 同语义）
  }
}

function loadPersisted(): RunState | null {
  try {
    if (!existsSync(STATE_FILE)) return null
    const raw = readFileSync(STATE_FILE, "utf8")
    const parsed = JSON.parse(raw) as RunState
    if (!parsed.goal || !Array.isArray(parsed.trace)) return null
    return parsed
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

/**
 * 最小脱敏（参照 Kestrel TraceRedaction）：抹长数字串（手机号/订单号/卡号）、
 * 邮箱、长 token（API key/base64），截断超长文本。短 UI 文案保留以便诊断。
 */
const EMAIL_RE = /[\w.+-]+@[\w-]+\.[\w.-]+/g
const TOKEN_RE = /[A-Za-z0-9_-]{20,}/g
const LONG_DIGITS_RE = /\d{7,}/g
const MAX_GOAL = 64

function redactGoal(s: string): string {
  if (!s) return s
  const masked = s
    .replace(EMAIL_RE, "[email]")
    .replace(TOKEN_RE, "[token]")
    .replace(LONG_DIGITS_RE, "[num]")
  return masked.length > MAX_GOAL ? masked.slice(0, MAX_GOAL) + "…" : masked
}
