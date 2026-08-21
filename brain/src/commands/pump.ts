import type { BodyClient } from "../ipc/client.ts"

/**
 * S6-③ brain command pump（冻结设计 command-ack-design §4 + GPT 裁决版状态机）：
 * 从 body 持久 journal 领取命令执行——EventBus 仅 wake 加速，本泵独立轮询兜底。
 *
 * 裁决语义（CLAIMED≠ACCEPTED）：
 * - claimNext（QUEUED→CLAIMED，写租约）拿到 envelope（含受保护文本）→ unprotect
 * - bindTask（CLAIMED→ACCEPTED）：onCommand 产出 taskId 后绑定——prompt_start 才用户可见
 * - settle：成功 RESOLVED；异常 INTERRUPTED（副作用边界未知，禁自动重放，人工核对）
 * - 启动 reconcile：sweepExpired 委托 body（D2）+ 本地视角跳过非 QUEUED
 *
 * 接线说明：独立可测；main.ts 一行接入（等 P1 契合 SHA + resume-race 避让解除）。
 * RPC 面按裁决最小面（reserve/claimNext/accept/settle/list）——P1 契约如有出入只改本文件。
 */

export interface JournalCommand {
  id: number
  commandId: string
  kind: "text" | "pause" | "resume" | "stop" | string
  /** 受保护文本（P0 Base64——与 body 侧 CommandStore.protect 同构） */
  protectedText?: string
  status: string
  taskId?: string | null
  brainSession?: string | null
}

export interface PumpHooks {
  /** 命令执行回调：text 走任务链（返回 taskId）；pause/resume/stop 走事件语义路由。 */
  onCommand: (cmd: JournalCommand, text: string) => Promise<string | void>
  /** 本 brain boot 会话标识 */
  session: () => string
  intervalMs?: number
}

const CONTROL_KINDS = new Set(["pause", "resume", "stop"])

function unprotect(protectedText: string): string {
  try {
    return Buffer.from(protectedText, "base64").toString("utf8")
  } catch {
    return ""
  }
}

export function buildCommandPump(
  body: BodyClient,
  hooks: PumpHooks,
): { start: () => void; drainOnce: () => Promise<number> } {
  let lastId = 0
  let running = false

  async function runOne(cmd: JournalCommand): Promise<boolean> {
    const claimRes = await body.rpc<{ claimed?: boolean; protectedText?: string; reason?: string }>(
      "command.claimNext",
      { commandId: cmd.commandId, brainSessionId: hooks.session(), leaseMs: 60_000 },
    )
    if (!claimRes.claimed) {
      console.log(`[pump] claimNext 被拒（${claimRes.reason ?? "未知"}）：${cmd.commandId.slice(0, 8)}`)
      return false
    }
    const text = unprotect(claimRes.protectedText ?? cmd.protectedText ?? "")
    console.log(`[pump] 领取 ${cmd.commandId.slice(0, 8)} [${cmd.kind}]${CONTROL_KINDS.has(cmd.kind) ? "" : `「${text.slice(0, 20)}」`}`)
    try {
      const taskId = await hooks.onCommand(cmd, text)
      // bindTask：有 taskId 才 CLAIMED→ACCEPTED；纯控制命令（无 taskId）直接 settle
      if (typeof taskId === "string" && taskId) {
        const bound = await body.rpc<{ ok?: boolean }>("command.accept", {
          commandId: cmd.commandId, brainSessionId: hooks.session(), taskId,
        })
        if (!bound.ok) console.warn(`[pump] bindTask 未确认（继续 settle）`)
      }
      await body.rpc("command.settle", {
        commandId: cmd.commandId, brainSessionId: hooks.session(),
        taskId: taskId ?? null, status: "RESOLVED",
      }).catch(() => undefined)
      return true
    } catch (err) {
      // 执行异常：副作用边界未知——INTERRUPTED 禁自动重放
      await body.rpc("command.settle", {
        commandId: cmd.commandId, brainSessionId: hooks.session(),
        taskId: null, status: "INTERRUPTED",
      }).catch(() => undefined)
      console.warn(`[pump] 命令异常标 INTERRUPTED（禁自动重放）：${err instanceof Error ? err.message : String(err)}`)
      return true
    }
  }

  /** 单轮排水（测试可驱动）：list 新命令 → 逐条 claim/execute/settle。返回处理数。 */
  async function drainOnce(): Promise<number> {
    let handled = 0
    try {
      // D2：list 入口触发 body 惰性清扫（过期 QUEUED→REJECTED / CLAIMED→INTERRUPTED）
      const res = await body.rpc<{ commands?: JournalCommand[] }>("command.list", { afterRowId: lastId, limit: 20 })
      for (const c of res.commands ?? []) {
        lastId = Math.max(lastId, c.id)
        if (c.status === "QUEUED" && (await runOne(c))) handled++
      }
    } catch (err) {
      console.warn(`[pump] drain 失败（下轮重试）：${err instanceof Error ? err.message : String(err)}`)
    }
    return handled
  }

  async function loop(): Promise<void> {
    for (;;) {
      await drainOnce()
      await new Promise((r) => setTimeout(r, hooks.intervalMs ?? 2000))
    }
  }

  return {
    start() {
      if (running) return
      running = true
      void loop().catch(() => undefined)
    },
    drainOnce,
  }
}
