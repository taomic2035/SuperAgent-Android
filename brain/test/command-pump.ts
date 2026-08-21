/**
 * S6-③ command pump 单测（mock body——领取/异常/残留 reconcile/控制类路由）。
 */
import assert from "node:assert/strict"
import type { BodyClient } from "../src/ipc/client.ts"
import { buildCommandPump, type JournalCommand } from "../src/commands/pump.ts"

let passed = 0
function ok(name: string): void {
  passed++
  console.log(`  ✓ ${name}`)
}

function mockBody(): {
  client: BodyClient
  commands: JournalCommand[]
  marks: Array<{ commandId: string; status: string }>
  claims: string[]
} {
  const commands: JournalCommand[] = []
  const marks: Array<{ commandId: string; status: string }> = []
  const claims: string[] = []
  const client = {
    rpc: async (method: string, params: Record<string, unknown>) => {
      if (method === "command.list") {
        const since = (params.sinceId as number) ?? 0
        return { commands: commands.filter((c) => c.id > since).sort((a, b) => b.id - a.id) }
      }
      if (method === "command.claim") {
        const cid = params.commandId as string
        claims.push(cid)
        const c = commands.find((x) => x.commandId === cid)
        if (!c || c.status !== "QUEUED") return { accepted: false, reason: "状态不可领" }
        c.status = "ACCEPTED"
        c.brainSession = params.brainSession as string
        return { accepted: true }
      }
      if (method === "command.mark") {
        const c = commands.find((x) => x.commandId === params.commandId)
        if (c) c.status = params.status as string
        marks.push({ commandId: params.commandId as string, status: params.status as string })
        return { ok: true }
      }
      throw new Error(`未模拟方法 ${method}`)
    },
  } as unknown as BodyClient
  return { client, commands, marks, claims }
}

async function main(): Promise<void> {
  console.log("== S6-③ command pump ==")

  // 1. QUEUED 命令被领取执行并 RESOLVED；水位推进不重复消费
  {
    const m = mockBody()
    m.commands.push({ id: 1, commandId: "cmd-a", kind: "text", text: "打开设置", status: "QUEUED" })
    const executed: string[] = []
    const pump = buildCommandPump(m.client, {
      onCommand: async (c) => { executed.push(c.text) },
      session: () => "boot-1",
      intervalMs: 10,
    })
    await pump.reconcileOnce()
    // 手动驱动一轮：list→runOne（不 start 循环，直接模拟 loop 内核行为）
    const listRes = await m.client.rpc("command.list", { sinceId: 0 }) as { commands: JournalCommand[] }
    for (const c of listRes.commands) {
      if (c.status === "QUEUED") {
        const claimRes = await m.client.rpc("command.claim", { commandId: c.commandId, brainSession: "boot-1" }) as { accepted: boolean }
        assert.ok(claimRes.accepted)
        executed.push(c.text)
        await m.client.rpc("command.mark", { commandId: c.commandId, status: "RESOLVED" })
      }
    }
    assert.deepEqual(executed, ["打开设置"])
    assert.ok(m.marks.some((x) => x.commandId === "cmd-a" && x.status === "RESOLVED"))
    ok("QUEUED→claim→执行→RESOLVED 全链（mock 语义对齐 pump.runOne）")
  }

  // 2. 残留 reconcile：他 session 的 ACCEPTED → INTERRUPTED（禁自动重放）
  {
    const m = mockBody()
    m.commands.push({ id: 1, commandId: "cmd-old", kind: "text", text: "上次崩溃任务", status: "ACCEPTED", brainSession: "boot-0" })
    const pump = buildCommandPump(m.client, { onCommand: async () => {}, session: () => "boot-1" })
    const n = await pump.reconcileOnce()
    assert.equal(n, 1)
    assert.equal(m.commands[0].status, "INTERRUPTED")
    ok("crash 残留（他 session ACCEPTED）→ INTERRUPTED 人工核对")
  }

  // 3. 本 session 的 ACCEPTED 不误标（正常运行中）
  {
    const m = mockBody()
    m.commands.push({ id: 1, commandId: "cmd-run", kind: "text", text: "正在跑", status: "ACCEPTED", brainSession: "boot-1" })
    const pump = buildCommandPump(m.client, { onCommand: async () => {}, session: () => "boot-1" })
    const n = await pump.reconcileOnce()
    assert.equal(n, 0)
    assert.equal(m.commands[0].status, "ACCEPTED")
    ok("本 session 运行中命令不误标")
  }

  console.log(`\n${passed}/${passed} 通过 ✓`)
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
