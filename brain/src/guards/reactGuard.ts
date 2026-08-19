/**
 * ReAct 止损闸门（AD-05，参照 Kestrel ReActGuard）。
 * 检测 5 类退化：MaxSteps / NoProgress / Oscillation / Looping / Revisiting。
 * brain 侧 beforeToolCall 查询 shouldAbort()，命中则强制 finish(false) 或 NeedsHuman。
 *
 * 与 Kestrel 的差异：Kestrel 在 Orchestrator 内部循环里同步判；
 * 本项目在 pi Agent 的 beforeToolCall 钩子里异步判——pi Loop 每轮调工具前问一次。
 * 签名用工具名+参数粗粒度聚合（无坐标，用 label/selectOption 的 label 代替坐标桶）。
 */

export type AbortReason =
  | "max_steps"
  | "no_progress"
  | "oscillation"
  | "looping"
  | "revisiting"

export interface ReActGuardConfig {
  maxSteps: number
  /** 连续无进展（动作后页面签名不变）阈值。 */
  noProgressThreshold: number
  /** 连续卡在同一签名阈值。 */
  stuckThreshold: number
}

const DEFAULTS: ReActGuardConfig = {
  maxSteps: 30,
  noProgressThreshold: 3,
  stuckThreshold: 3,
}

interface StepRecord {
  tool: string
  coarseKey: string
  sigBefore: string
  sigAfter: string
}

export class ReActGuard {
  private readonly cfg: ReActGuardConfig
  private readonly steps: StepRecord[] = []
  private readonly sigs: string[] = []
  private readonly coarseKeys: string[] = []
  private consecutiveNoProgress = 0
  private totalRecorded = 0

  constructor(cfg: Partial<ReActGuardConfig> = {}) {
    this.cfg = { ...DEFAULTS, ...cfg }
  }

  /** 记录一步：sigBefore/sigAfter 为执行前后页面签名。相等 → 无进展累加。 */
  record(tool: string, args: Record<string, unknown>, sigBefore: string, sigAfter: string): void {
    const coarseKey = this.coarseKey(tool, args)
    this.steps.push({ tool, coarseKey, sigBefore, sigAfter })
    this.sigs.push(sigAfter)
    this.coarseKeys.push(coarseKey)
    this.totalRecorded++
    this.consecutiveNoProgress = sigBefore === sigAfter ? this.consecutiveNoProgress + 1 : 0
  }

  /** 记录恢复动作（只增全局步数，不进卡死/绕圈检测）。 */
  recordRecovery(): void {
    this.totalRecorded++
  }

  /** 清空卡死/振荡/无进展状态（人工协助后），不清 totalRecorded（MaxSteps 全局上限）。 */
  reset(): void {
    this.steps.length = 0
    this.sigs.length = 0
    this.coarseKeys.length = 0
    this.consecutiveNoProgress = 0
  }

  shouldAbort(): AbortReason | null {
    if (this.totalRecorded > this.cfg.maxSteps) return "max_steps"
    if (this.isNoProgress()) return "no_progress"
    if (this.isStuck()) return "oscillation" // Kestrel Stuck 归到 oscillation 语义
    if (this.isOscillating()) return "oscillation"
    if (this.isLooping() || this.isRevisiting()) return "looping"
    return null
  }

  get totalSteps(): number {
    return this.totalRecorded
  }

  private isNoProgress(): boolean {
    return this.cfg.noProgressThreshold > 0 && this.consecutiveNoProgress >= this.cfg.noProgressThreshold
  }

  private isStuck(): boolean {
    if (this.sigs.length < this.cfg.stuckThreshold) return false
    const last = this.sigs.slice(-this.cfg.stuckThreshold)
    return last.every((s) => s === last[0])
  }

  private isOscillating(): boolean {
    if (this.steps.length < 4) return false
    const last = this.steps.slice(-4)
    return last[0].tool === last[2].tool && last[1].tool === last[3].tool && last[0].tool !== last[1].tool
  }

  /** 绕圈：最近 LOOP_WINDOW 个定点动作里只在很少几个落点打转，或某落点被反复点。 */
  private isLooping(): boolean {
    const fixed = this.coarseKeys.filter((k) => !k.startsWith("swipe:"))
    if (fixed.length < LOOP_WINDOW) return false
    const window = fixed.slice(-LOOP_WINDOW)
    const counts = new Map<string, number>()
    for (const k of window) counts.set(k, (counts.get(k) ?? 0) + 1)
    const distinct = counts.size
    const maxRepeat = Math.max(...counts.values())
    return distinct <= LOOP_DIVERSITY_FLOOR || maxRepeat >= LOOP_REPEAT
  }

  /** 屏幕环路：最近 REVISIT_WINDOW 步的页面签名里同一签名重现 ≥ REVISIT_REPEAT 次。 */
  private isRevisiting(): boolean {
    if (this.sigs.length < REVISIT_MIN) return false
    const window = this.sigs.slice(-REVISIT_WINDOW)
    const counts = new Map<string, number>()
    for (const s of window) counts.set(s, (counts.get(s) ?? 0) + 1)
    const maxRevisit = Math.max(...counts.values())
    return maxRevisit >= REVISIT_REPEAT
  }

  /** 粗粒度签名：用工具名 + label/坐标聚合，使"差几十像素的反复点"归为同一落点。 */
  private coarseKey(tool: string, args: Record<string, unknown>): string {
    const a = args as Record<string, unknown>
    switch (tool) {
      case "control.tap": {
        const x = num(a.x), y = num(a.y)
        return `tap:${Math.floor(x / COORD_BUCKET)}:${Math.floor(y / COORD_BUCKET)}`
      }
      case "control.longPress": {
        const x = num(a.x), y = num(a.y)
        return `longpress:${Math.floor(x / COORD_BUCKET)}:${Math.floor(y / COORD_BUCKET)}`
      }
      case "control.swipe": {
        const fx = num((a.from as Record<string, unknown>)?.x), fy = num((a.from as Record<string, unknown>)?.y)
        const tx = num((a.to as Record<string, unknown>)?.x), ty = num((a.to as Record<string, unknown>)?.y)
        return `swipe:${Math.floor(fx / COORD_BUCKET)}:${Math.floor(fy / COORD_BUCKET)}>${Math.floor(tx / COORD_BUCKET)}:${Math.floor(ty / COORD_BUCKET)}`
      }
      case "control.selectOption":
        return `sel:${str(a.label)}`
      case "control.selectSpec":
        return `spec:${str(a.label)}`
      case "control.typeText":
        return "type"
      case "control.launch":
        return `launch:${str(a.pkg)}`
      case "control.back":
        return "back"
      case "control.home":
        return "home"
      default:
        return tool
    }
  }
}

function num(v: unknown): number {
  return typeof v === "number" ? v : 0
}
function str(v: unknown): string {
  return typeof v === "string" ? v : ""
}

const LOOP_WINDOW = 8
const LOOP_DIVERSITY_FLOOR = 2
const LOOP_REPEAT = 4
const COORD_BUCKET = 40
const REVISIT_MIN = 6
const REVISIT_WINDOW = 12
const REVISIT_REPEAT = 4
