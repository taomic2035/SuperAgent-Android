/**
 * ME-8 CLI：记忆备份/恢复手动入口。
 * 用法：npm run memory-backup（立即备份）| npm run memory-restore（交互恢复）
 */
import { BodyClient } from "../ipc/client.ts"
import { env } from "../env.ts"
import { backupNow, restoreInteractive } from "./backup.ts"

const mode = process.argv[2] ?? "backup"
const body = new BodyClient(env("BODY_URL", "http://127.0.0.1:8765"), env("BODY_TOKEN", "super-agent-dev"))
await body.waitForBody()

if (mode === "restore") {
  await restoreInteractive(body)
} else {
  const n = await backupNow(body)
  console.log(`备份完成：${n} 条 → 快照文件`)
  process.exit(0)
}
