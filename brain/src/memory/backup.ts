import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs"
import { join } from "node:path"
import { createInterface } from "node:readline/promises"
import { stdin, stdout } from "node:process"
import type { BodyClient } from "../ipc/client.ts"
import type { MemoryEntry, MemoryImportResult } from "../ipc/types.ts"

/**
 * ME-8 记忆备份导出/恢复（docs/15 §7——"不丢记忆"承诺的兑现项）：
 * body SQLite 是权威存储，但身处 app 私有沙箱（pm clear/卸载/换机即失）——
 * brain 定期全量拉取落 Termux 快照；恢复为手动命令（不自动——尊重用户清空记忆的意愿）。
 */

const BACKUP_INTERVAL_MS = 6 * 60 * 60 * 1000 // 6h：个人助手记忆日增量小，高频无意义

function snapshotPath(): string {
  const dir = process.env.SUPER_AGENT_STATE_DIR ?? join(process.env.HOME ?? process.cwd(), ".super-agent")
  return join(dir, "memory-snapshot.json")
}

interface SnapshotFile {
  ts: number
  count: number
  entries: MemoryEntry[]
}

/** 立即备份：memory.export（全量含 revoked）→ Termux 快照文件。返回条目数。 */
export async function backupNow(body: BodyClient, file = snapshotPath()): Promise<number> {
  const entries = await body.rpc<MemoryEntry[]>("memory.export", {})
  const dir = join(file, "..")
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true })
  const snapshot: SnapshotFile = { ts: Date.now(), count: entries.length, entries }
  writeFileSync(file, JSON.stringify(snapshot), "utf8")
  return entries.length
}

/** 定时备份循环：启动即备一次 + 6h 间隔（fire-and-forget，永不抛——G2-05 防御纪律）。 */
export function scheduleBackup(body: BodyClient): void {
  const tick = async (): Promise<void> => {
    for (;;) {
      try {
        const n = await backupNow(body)
        console.log(`[brain] ME-8 记忆备份：${n} 条 → ${snapshotPath()}`)
      } catch (err) {
        console.warn(`[brain] ME-8 记忆备份失败（下轮重试）：${err instanceof Error ? err.message : String(err)}`)
      }
      await new Promise((r) => setTimeout(r, BACKUP_INTERVAL_MS))
    }
  }
  void tick().catch(() => undefined)
}

/**
 * 启动提示（不自动恢复——用户清空 body 记忆可能是刻意为之）：
 * body 记忆为空且快照非空时打一行恢复指引。
 */
export async function checkRestoreHint(body: BodyClient): Promise<void> {
  try {
    const entries = await body.rpc<MemoryEntry[]>("memory.export", {})
    if (entries.length > 0) return
    const file = snapshotPath()
    if (!existsSync(file)) return
    const snap = JSON.parse(readFileSync(file, "utf8")) as SnapshotFile
    const active = snap.entries.filter((e) => !e.revoked)
    if (active.length > 0) {
      console.log(`[brain] ME-8 检测到 body 记忆为空但快照有 ${active.length} 条——如需找回：cd brain && npm run memory-restore`)
    }
  } catch {
    /* 提示失败无害 */
  }
}

/** 手动恢复（CLI）：展示快照 active 条目 → y/n 确认 → memory.import 补缺。 */
export async function restoreInteractive(body: BodyClient, file = snapshotPath()): Promise<void> {
  if (!existsSync(file)) {
    console.log(`无快照文件：${file}`)
    process.exit(1)
  }
  const snap = JSON.parse(readFileSync(file, "utf8")) as SnapshotFile
  const active = snap.entries.filter((e) => !e.revoked)
  console.log(`快照（${new Date(snap.ts).toLocaleString()}）：共 ${snap.count} 条，active ${active.length} 条\n`)
  for (const e of active) {
    console.log(`  [${e.kind}] ${e.topic}：${e.content.slice(0, 60)}（conf ${e.confidence.toFixed(1)}）`)
  }
  if (active.length === 0) {
    console.log("快照无 active 条目，无需恢复")
    process.exit(0)
  }
  const rl = createInterface({ input: stdin, output: stdout })
  const answer = (await rl.question(`\n恢复以上 ${active.length} 条到 body？（y/N）`)).trim().toLowerCase()
  rl.close()
  if (answer !== "y" && answer !== "yes") {
    console.log("已取消")
    process.exit(0)
  }
  const result = await body.rpc<MemoryImportResult>("memory.import", { entries: snap.entries })
  console.log(`恢复完成：新插入 ${result.inserted} 条，跳过 ${result.skipped} 条（body 已有/revoked/含 PII）`)
  process.exit(0)
}
