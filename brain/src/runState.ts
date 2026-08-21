import type { ScreenResult, TraceStep } from "./ipc/types.ts"
import { env } from "./env.ts"
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs"
import { join } from "node:path"

/**
 * 会话持久化与断点续跑（AD-05）。
 * 参照 Kestrel RunTraceBuilder：失败可见性留痕（成功/失败/崩溃都落全历史）、
 * trace 单调序号、脱敏后落盘。pi AgentHarness/Session 不用（见 AD-05）。
 */

export type RunOutcome = "success" | "failed" | "crashed" | "needs_human" | "closed" | "paused" | "stopped"

/**
 * closed（审计 P1-01）：模型口头收笔/对话型回合的终态——未经 task.finish 证据核验，
 * 不得记 success（审计：会污染成功率统计并失去续跑判断依据）；对话型也不该算 failed
 * （会触发可续跑提示）。closed = 明确结束、不可续跑、审计可辨。
 */

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
  /** 该 run 是否经 task.finish 证据核验成功收尾（区分"模型口头收笔"与"证据验收完成"，审计用）。 */
  finishVerified?: boolean
}

/** ME-3b 归档出口（docs/15 §3）：main 注册 → body run.archive（SQLite 全量）；本地环形照旧供断点续跑 */
export type ArchiveSink = (snapshot: RunState & { finishedAt: number }) => void

let archiveSink: ArchiveSink | undefined

export function setArchiveSink(fn: ArchiveSink): void {
  archiveSink = fn
}

function stateDir(): string {
  return env("SUPER_AGENT_STATE_DIR", join(process.env.HOME ?? process.cwd(), ".super-agent"))
}

function stateFile(): string {
  return join(stateDir(), "runstate.json")
}

let current: RunState | null = null
let resumeClaimed = false

export function beginRun(goal: string): void {
  resumeClaimed = false
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

/**
 * C-05（docs/16 §5）：resumable 显式策略表——终态可续性不再隐式推断。
 * closed=对话型收笔（正常结束）不可续；failed/crashed/needs_human 可断点续跑（TC-14）。
 */
const RESUMABLE: Record<RunOutcome, boolean> = {
  success: false,
  failed: true,
  crashed: true,
  needs_human: true,
  closed: false,
  paused: true, // C-06：暂停可续（resume_request 自动断点续跑）
  stopped: false, // C-06：用户停止是不可恢复终态，区别于一般 failed
}

/** 标记 run 结束（成功/失败/崩溃都调），落盘全历史。成功时清证据驳回计数。
 *  C-05：幂等门——已有终态的二次调用直接忽略（防双写/双归档；wrapper 是唯一写者）。 */
export function finishRun(outcome: RunOutcome, failureReason?: string): void {
  if (!current) return
  if (current.outcome) return // 已终态：不覆盖、不重归档
  current.outcome = outcome
  resumeClaimed = false
  current.failureReason = failureReason
  if (outcome === "success") current.finishRejectCount = 0
  persist()
  archiveRun()
}

/**
 * 终态 run 归档（Kestrel TraceRetention 语义，本地环形 30 条 + ME-3b SQLite 全量双写）：
 * 本地 runstate-history.json 仍只留 30 条（审计快捷入口），body SQLite 全量不丢（Iron Law）。
 * snapshot 为脱敏后盘上数据（persist 已过 redact），无 PII 出设备边界。
 */
function archiveRun(): void {
  try {
    const snapshot = loadPersisted()
    if (!snapshot) return
    if (archiveSink) {
      try {
        archiveSink({ ...snapshot, finishedAt: Date.now() })
      } catch {
        // SQLite 归档失败不阻断（本地环形仍是兜底）
      }
    }
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

/** task.finish 证据核验通过时调用（在 finishRun("success") 之前）。 */
export function markFinishVerified(): void {
  if (!current) return
  current.finishVerified = true
  persist()
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
  resumeClaimed = false
  clearPersisted()
}

/** 预览可续跑任务（不动 current）。可续性按 RESUMABLE 策略表（C-05）。 */
export function peekRun(): RunState | null {
  const saved = loadPersisted()
  return saved && saved.outcome ? (RESUMABLE[saved.outcome] ? saved : null) : saved
}

export function hasResumableRun(): boolean {
  return peekRun() !== null
}

export type ResumeClaimMode = "automatic" | "manual"

/**
 * Atomically claims one persisted lifecycle for resume inside this process.
 * Automatic UI/event resume is deliberately narrower than manual crash recovery:
 * it accepts only a settled paused run, while manual recovery may also reopen an
 * interrupted snapshot or an explicitly resumable failure outcome.
 */
export function resumeRun(mode: ResumeClaimMode = "automatic"): RunState | null {
  if (resumeClaimed) return null
  const saved = loadPersisted()
  if (!saved) return null
  const eligible = mode === "automatic"
    ? saved.outcome === "paused"
    : saved.outcome === undefined || RESUMABLE[saved.outcome]
  if (!eligible) return null

  resumeClaimed = true
  current = saved
  // C-05：续跑=该 run 的新生命周期。只有领取成功后才清旧终态字段，
  // 防止无资格/重复请求破坏盘上 checkpoint。
  current.outcome = undefined
  current.failureReason = undefined
  current.finishVerified = false
  persist()
  return current
}

/**
 * Atomically turns any persisted active/resumable checkpoint into one closed,
 * non-resumable terminal. This deliberately bypasses finishRun's terminal
 * idempotency guard so a settled paused checkpoint can be cancelled, while a
 * repeated stop against the resulting stopped checkpoint remains a no-op.
 */
export function stopPersistedRun(reason = "用户停止"): boolean {
  const saved = loadPersisted()
  if (!saved) return false
  if (saved.outcome && !RESUMABLE[saved.outcome]) return false

  current = saved
  current.outcome = "stopped"
  current.failureReason = reason
  current.finishVerified = false
  resumeClaimed = false
  persist()
  archiveRun()
  return true
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
      finishVerified: current.finishVerified,
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
