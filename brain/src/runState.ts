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
  /** task.finish 连续证据驳回次数（成功清零；TC-08：≥3 次)-(建议转人工）。 */
  finishRejectCount?: number
}

function stateDir(): string {
  return env("SUPER_AGENT_STATE_DIR", join(process.env.HOME ?? process.cwd(), ".super-agent"))
}

function stateFile(): string {
  return join(stateDir(), "runstate.json")
}

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

/** 标记 run 结束（成功/失败/崩溃都调），落盘全历史。成功时清证据驳回计数。 */
export function finishRun(outcome: RunOutcome, failureReason?: string): void {
  if (!current) return
  current.outcome = outcome
  current.failureReason = failureReason
  if (outcome === "success") current.finishRejectCount = 0
  persist()
  archiveRun()
}

/**
 * 终态 run 归档（Kestrel TraceRetention 语义，保留最近 30 条）：审计/复盘"模型在哪些任务上
 * 谎报/失败"必需。主文件 runstate.json 仍只存最近一次（断点续跑用），历史独立文件。
 */
function archiveRun(): void {
  try {
    const snapshot = loadPersisted()
    if (!snapshot) return
    const historyFile = join(stateDir(), "runstate-history.json")
    const history = readHistory(historyFile)
    history.push(snapshot)
    while (history.length > 30) history.shift()
    writeFileSync(historyFile, JSON.stringify(history), "utf8")
  } catch {
    // 归档失败不阻断
  }
}

function readHistory(file: string): RunState[] {
  try {
    if (!existsSync(file)) return []
    return JSON.parse(readFileSync(file, "utf8")) as RunState[]
  } catch {
    return []
  }
}

/** task.finish 证据驳回计数（TC-08：连续 ≥3 次应转人工）。 */
export function noteFinishRejected(): number {
  if (!current) return 0
  current.finishRejectCount = (current.finishRejectCount ?? 0) + 1
  persist()
  return current.finishRejectCount
}

export function resetRun(): void {
  current = null
  clearPersisted()
}

/** 预览可续跑任务（不动 current）。成功终态的任务不再可续。 */
export function peekRun(): RunState | null {
  const saved = loadPersisted()
  return saved && saved.outcome !== "success" ? saved : null
}

export function hasResumableRun(): boolean {
  return peekRun() !== null
}

export function resumeRun(): RunState | null {
  const saved = loadPersisted()
  if (saved) current = saved
  return saved
}

/**
 * 构造续跑上下文（注入 user prompt）。盘上 trace 已脱敏（args 清空），
 * 只给工具名+成败，让模型靠 perceive.screen 重建现场——不信任旧记录的界面状态。
 */
export function buildResumeContext(saved: RunState): string {
  const steps = saved.trace
    .slice(-30)
    .map((s, i) => {
      const args = s.args as Record<string, unknown> | undefined
      const detail = args?.label ?? args?.pkg
      return `${i + 1}. ${s.tool}${typeof detail === "string" && detail ? `（${detail}）` : ""} ${s.located ? "✓" : "✗"}`
    })
    .join("\n")
  const outcome = saved.outcome
    ? `，上次结果：${saved.outcome}${saved.failureReason ? `（${saved.failureReason}）` : ""}`
    : "，会话中断（无终态）"
  return [
    `【断点续跑】你在执行任务「${saved.goal}」时被中断${outcome}。`,
    `已执行 ${saved.trace.length} 步（脱敏记录，仅工具名与成败）：`,
    steps || "（无记录）",
    "",
    "请先 perceive.screen 感知当前屏幕评估任务进展，再继续完成目标；",
    "若已无法继续，task.finish 声明失败或 hitl.handoff 转人工。",
  ].join("\n")
}

/** 落盘保留的无 PII 参数键（坐标/可见文字标签/包名/技能名/驳回复盘字段）；typeText.text 等载荷值仍丢弃。 */
const SAFE_ARG_KEYS = new Set(["x", "y", "fromX", "fromY", "toX", "toY", "durationMs", "label", "pkg", "name", "evidence", "reason"])

function persist(): void {
  if (!current) return
  try {
    mkdirSync(stateDir(), { recursive: true })
    // 落盘前脱敏：goal/failureReason 抹 PII，trace 的 args 只留无 PII 键（坐标/label 可复盘，文本载荷丢弃）
    const redacted: RunState = {
      goal: redactGoal(current.goal),
      baselineScreen: undefined, // baseline 含完整屏幕文本，不入盘
      trace: current.trace.map((s) => ({
        tool: s.tool,
        args: Object.fromEntries(Object.entries(s.args ?? {}).filter(([k]) => SAFE_ARG_KEYS.has(k))),
        located: s.located,
        signature: s.signature,
        timestamp: s.timestamp,
        sensitive: s.sensitive,
        resultKind: s.resultKind,
      })),
      startedAt: current.startedAt,
      outcome: current.outcome,
      failureReason: current.failureReason ? redactGoal(current.failureReason) : undefined,
      finishRejectCount: current.finishRejectCount,
    }
    writeFileSync(stateFile(), JSON.stringify(redacted), "utf8")
  } catch {
    // 落盘失败不阻断运行（与 Kestrel NoOpTraceSink 同语义）
  }
}

function loadPersisted(): RunState | null {
  try {
    if (!existsSync(stateFile())) return null
    const raw = readFileSync(stateFile(), "utf8")
    const parsed = JSON.parse(raw) as RunState
    if (!parsed.goal || !Array.isArray(parsed.trace)) return null
    return parsed
  } catch {
    return null
  }
}

function clearPersisted(): void {
  try {
    if (existsSync(stateFile())) writeFileSync(stateFile(), "", "utf8")
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
