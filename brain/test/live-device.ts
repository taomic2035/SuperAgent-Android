import { BodyClient, BodyRpcError } from "../src/ipc/client.ts"
import type { ScreenResult } from "../src/ipc/types.ts"

const BODY_URL = process.env.BODY_URL ?? "http://127.0.0.1:8765"
const BODY_TOKEN = process.env.BODY_TOKEN ?? ""
const EXPECT_TOKEN = process.env.EXPECT_TOKEN ?? ""

let passed = 0
function ok(name: string, detail = ""): void {
  passed++
  console.log(`  ✓ ${name}${detail ? ` (${detail})` : ""}`)
}

async function main(): Promise<void> {
  if (!BODY_TOKEN) {
    console.error("需要 BODY_TOKEN 环境变量（真机 token，adb run-as 读取）")
    process.exit(1)
  }
  console.log(`连接真机躯体 ${BODY_URL}`)

  const body = new BodyClient(BODY_URL, BODY_TOKEN)

  const health = await body.health()
  if (!health.ok) throw new Error("health 异常")
  if (health.protocolVersion !== 2) throw new Error(`协议版本异常: ${health.protocolVersion}`)
  ok("health 协议 v2", `bootId=${health.bootId.slice(0, 12)} uptime=${health.uptimeMs}ms`)
  ok("services 全开", Object.entries(health.services).filter(([, v]) => !v).length === 0
    ? "skill/control/speech/perceive/hitl/hardware"
    : JSON.stringify(health.services))

  const screen = await body.rpc<ScreenResult>("perceive.screen", {})
  if (screen.blank) throw new Error("屏幕无内容")
  ok("perceive.screen 往返", `${screen.kind} ${screen.appPackage} nodes=${screen.nodes?.length ?? 0} signature=${screen.signature}`)

  try {
    await body.rpc("control.selectOption", { label: "立即支付" })
    throw new Error("边界词未被拦截")
  } catch (e) {
    if (!(e instanceof BodyRpcError) || e.code !== "COMMIT_BOUNDARY") throw e
    ok("COMMIT_BOUNDARY 拦截")
  }

  const skills = await body.rpc<{ skills: unknown[] }>("skill.list", {})
  ok("skill.list 往返", `${skills.skills.length} 技能`)

  try {
    await body.rpc("skill.feedback", { name: "definitely-not-exists", result: true })
    throw new Error("应为 SKILL_NOT_FOUND")
  } catch (e) {
    if (!(e instanceof BodyRpcError) || e.code !== "SKILL_NOT_FOUND") throw e
    ok("SKILL_NOT_FOUND 语义")
  }

  const events = await body.events(0)
  ok("events 短轮询", `${events.length} 条`)

  if (EXPECT_TOKEN) {
    try {
      await new BodyClient(BODY_URL, "wrong-token").health()
      throw new Error("错误 token 未被拒绝")
    } catch {
      ok("错误 token 401 拒绝")
    }
  }

  console.log(`\n真机验收通过（${passed} 项）`)
}

main().catch((err) => {
  console.error("真机验收失败：", err)
  process.exitCode = 1
})
