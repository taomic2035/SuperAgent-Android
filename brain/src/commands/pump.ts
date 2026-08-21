import type { BodyClient } from "../ipc/client.ts"

/**
 * S6-③ brain command pump（冻结设计 docs/superpowers/specs/2026-08-21-command-ack-design.md §4）：
 * 从 body 持久 journal 原子领取命令执行——EventBus 仅 wake 加速，本泵独立轮询兜底。
 *
 * 语义（冻结设计 §2）：
 * - claim 成功（ACCEPTED）才执行；同 commandId 只执行一次（body 侧原子保证）
 * - 执行成功 → RESOLVED；执行异常 → INTERRUPTED（副作用边界未知，禁自动重放，等人工核对）
 * - 启动 reconcile：ACCEPTED 但无终态的残留命令（上次 crash）→ INTERRUPTED
 * - 命令优先级（STOP>PAUSE>RESUME>NEW_TASK）由 hooks.onCommand 内的任务链裁决（C-06 既有语义）
 *
 * 接线说明：本模块独立可测；main.ts 一行接入（等 GPT P1 契合 + resume-race 避让解除）。
 * RPC 方法名按 GLM 提案（command.list/claim/mark）——P1 契合如有出入只改本文件适配层。
 */

export interface JournalCommand {
  id: number
  commandId: string
  kind: "text" | "pause" | "resume" | "stop" | string
  text: string
  status: string
  taskId?: string | null
  brainSession?: string | null
}

export interface PumpHooks {
  /** 命令执行回调（text 任务走 promptAgent；pause/resume/stop 走事件语义路由）。resolve 后标 RESOLVED。 */
  onCommand: (cmd: JournalCommand) => Promise<void>
  /** 本 brain boot 会话标识（同 bootSession 体系——跨重启残留识别） */
  session: () => string
  /** 轮询间隔（默认 2s；EventBus wake 可缩短但本泵自带节奏） */
  intervalMs?: number
}

const CONTROL_KINDS = new Set(["pause", "resume", "stop"])

export function buildCommandPump(body: BodyClient, hooks: PumpHooks): { start: () => void; reconcileOnce: () => Promise<number> } {
  let lastId = 0
  let running = false

  async function listCommands(sinceId: number): Promise<JournalCommand[]> {
    const r = await body.rpc<{ commands?: JournalCommand[] }>("command.list", { sinceId, limit: 20 })
    return r.commands ?? (r as unknown as JournalCommand[])
  }

  /** 残留 reconcile：上次 crash 留下的 ACCEPTED 无终态 → INTERRUPTED（人工核对）。返回处理数。 */
  async function reconcileOnce(): Promise<number> {
    let handled = 0
    try {
      const all = await listCommands(0)
      for (const c of all) {
        if (c.status === "ACCEPTED" && c.brainSession && c.brainSession !== hooks.session()) {
          await body.rpc("command.mark", { commandId: c.commandId, status: "INTERRUPTED" }).catch(() => undefined)
          console.log(`[pump] 残留命令标 INTERRUPTED（上次会话 crash，边界未知）：${c.commandId.slice(0, 8)}「${c.text.slice(0, 16)}」`)
          handled++
        }
        lastId = Math.max(lastId, c.id)
      }
    } catch (err) {
      console.warn(`[pump] reconcile 失败（下轮重试）：${err instanceof Error ? err.message : String(err)}`)
    }
    return handled
  }

  async function runOne(cmd: JournalCommand): Promise<void> {
    // 控制类命令由 hooks 路由到事件语义（pause/resume/stop 走既有 requestPause 等）；文本走任务链
    const claimRes = await body.rpc<{ accepted?: boolean; reason?: string }>("command.claim", {
      commandId: cmd.commandId,
      brainSession: hooks.session(),
    })
    if (!claimRes.accepted) {
      console.log(`[pump] claim 被拒（${claimRes.reason ?? "未知"}）：${cmd.commandId.slice(0, 8)}——跳过`)
      return
    }
    console.log(`[pump] 领取命令 ${cmd.commandId.slice(0, 8)} [${cmd.kind}]「${CONTROL_KINDS.has(cmd.kind) ? cmd.kind : cmd.text.slice(0, 20)}」`)
    try {
      await hooks.onCommand(cmd)
      await body.rpc("command.mark", { commandId: cmd.commandId, status: "RESOLVED" }).catch(() => undefined)
    } catch (err) {
      // 执行异常：副作用边界未知（工具可能已执行一半）——INTERRUPTED 禁自动重放
      await body.rpc("command.mark", { commandId: cmd.commandId, status: "INTERRUPTED" }).catch(() => undefined)
      console.warn(`[pump] 命令异常标 INTERRUPTED（禁自动重放）：${err instanceof Error ? err.message : String(err)}`)
    }
  }

  async function loop(): Promise<void> {
    await reconcileOnce()
    for (;;) {
      try {
        const cmds = await listCommands(lastId)
        for (const c of cmds) {
          lastId = Math.max(lastId, c.id)
          if (c.status === "QUEUED") await runOne(c)
        }
      } catch {
        // body 不可达：退避（waitForBody 场景由调用方处理，这里只兜轮询错）
      }
      await new Promise((r) => setTimeout(r, hooks.intervalMs ?? 2000))
    }
  }

  return {
    start() {
      if (running) return
      running = true
      void loop().catch(() => undefined)
    },
    reconcileOnce,
  }
}
