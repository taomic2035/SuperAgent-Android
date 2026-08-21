/**
 * S6-③ command pump 单测（裁决版语义：claimNext/accept/settle + 保护文本 + INTERRUPTED）。
 */
import assert from "node:assert/strict"
import type { BodyClient } from "../src/ipc/client.ts"
import { buildCommandPump, type JournalCommand } from "../src/commands/pump.ts"

let passed = 0
function ok(name: string): void {
  passed++
  console.log(`  ✓ ${name}`)
}

function protect(text: string): string {
  return Buffer.from(text, "base64").toString("base64") === text ? text : Buffer.from(text, "utf8").toString("base64")
}

function mockBody(): {
  client: BodyClient
  commands: JournalCommand[]
  settles: Array<{ commandId: string; status: string; taskId: string | null }>
  binds: string[]
} {
  const commands: JournalCommand[] = []
  const settles: Array<{ commandId: string; status: string; taskId: string | null }> = []
  const binds: string[] = []
  const client = {
    rpc: async (method: string, params: Record<string, unknown>) => {
      if (method === "command.list") {
        const after = (params.afterRowId as number) ?? 0
        return { commands: commands.filter((c) => c.id > after).sort((a, b) => b.id - a.id) }
      }
      if (method === "command.claimNext") {
        const cid = params.commandId as string
        const c = commands.find((x) => x.commandId === cid)
        if (!c || c.status !== "QUEUED") return { claimed: false, reason: "状态不可领" }
        c.status = "CLAIMED"
        c.brainSession = params.brainSessionId as string
        return { claimed: true, protectedText: c.protectedText }
      }
      if (method === "command.accept") {
        binds.push(params.commandId as string)
        const c = commands.find((x) => x.commandId === params.commandId)
        if (c) { c.status = "ACCEPTED"; c.taskId = params.taskId as string }
        return { ok: true }
      }
      if (method === "command.settle") {
        const c = commands.find((x) => x.commandId === params.commandId)
        if (c) c.status = params.status as string
        settles.push({ commandId: params.commandId as string, status: params.status as string, taskId: (params.taskId as string | null) ?? null })
        return { ok: true }
      }
      throw new Error(`未模拟方法 ${method}`)
    },
  } as unknown as BodyClient
  return { client, commands, settles, binds }
}

async function main(): Promise<void> {
  console.log("== S6-③ command pump（裁决版）==")

  // 1. text 命令全链：claimNext→onCommand(返回 taskId)→accept→settle RESOLVED；保护文本解出
  {
    const m = mockBody()
    m.commands.push({ id: 1, commandId: "cmd-a", kind: "text", protectedText: protect("打开设置"), status: "QUEUED" })
    const seenText: string[] = []
    const pump = buildCommandPump(m.client, {
      onCommand: async (_c, text) => { seenText.push(text); return "task-1" },
      session: () => "boot-1",
    })
    const n = await pump.drainOnce()
    assert.equal(n, 1)
    assert.deepEqual(seenText, ["打开设置"], "保护文本在 brain 侧解出原文")
    assert.deepEqual(m.binds, ["cmd-a"], "taskId 经 accept 绑定")
    assert.equal(m.settles[0].status, "RESOLVED")
    assert.equal(m.settles[0].taskId, "task-1")
    ok("text 命令：claimNext→解保护→onCommand→accept(taskId)→settle RESOLVED")
  }

  // 2. 执行异常 → INTERRUPTED（禁自动重放），无 accept
  {
    const m = mockBody()
    m.commands.push({ id: 1, commandId: "cmd-err", kind: "text", protectedText: protect("会失败"), status: "QUEUED" })
    const pump = buildCommandPump(m.client, {
      onCommand: async () => { throw new Error("执行中断") },
      session: () => "boot-1",
    })
    await pump.drainOnce()
    assert.equal(m.settles[0].status, "INTERRUPTED")
    assert.deepEqual(m.binds, [], "异常路径不 bindTask")
    ok("执行异常 → settle INTERRUPTED（副作用边界未知）")
  }

  // 3. 控制命令（stop）：无 taskId，直接 RESOLVED；水位推进后不重复消费
  {
    const m = mockBody()
    m.commands.push({ id: 1, commandId: "cmd-stop", kind: "stop", protectedText: protect("停止"), status: "QUEUED" })
    const kinds: string[] = []
    const pump = buildCommandPump(m.client, {
      onCommand: async (c) => { kinds.push(c.kind) },
      session: () => "boot-1",
    })
    await pump.drainOnce()
    assert.deepEqual(kinds, ["stop"])
    assert.equal(m.settles[0].status, "RESOLVED")
    assert.equal(m.settles[0].taskId, null)
    // 再 drain：水位已过，不重复
    const n2 = await pump.drainOnce()
    assert.equal(n2, 0)
    ok("控制命令直接 RESOLVED（无 taskId）；水位推进不重复消费")
  }

  // 4. claimNext 被拒（如租约他方）→ 不执行不 settle
  {
    const m = mockBody()
    m.commands.push({ id: 1, commandId: "cmd-x", kind: "text", protectedText: protect("x"), status: "CLAIMED", brainSession: "other" })
    const pump = buildCommandPump(m.client, { onCommand: async () => "t", session: () => "boot-1" })
    // status=CLAIMED 非 QUEUED → drain 跳过（list 侧不进 runOne）
    const n = await pump.drainOnce()
    assert.equal(n, 0)
    ok("非 QUEUED 状态跳过（CLAIMED 他方/终态不重复执行）")
  }

  console.log(`\n${passed}/${passed} 通过 ✓`)
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
