/**
 * ME-7 进化度量报表（docs/15 §8.4——"越用越聪明"必须可证）。
 * 用法：npm run evolve-report
 * 数据源：run.list（runs 表全量 outcome+埋点）、memory.export（hits/confidence/kind）、skill.list。
 * 输出：成功率趋势 / 注入 A/B / 记忆库健康 / lesson 复用 / 技能复用。
 */
import type { BodyClient } from "../ipc/client.ts"
import type { MemoryEntry, RunRecord } from "../ipc/types.ts"

interface SkillSummary {
  skills: Array<{ name: string; appPackage?: string }>
}

function pct(n: number): string {
  return `${(n * 100).toFixed(0)}%`
}

function successRate(runs: RunRecord[]): number {
  if (runs.length === 0) return 0
  return runs.filter((r) => r.outcome === "success").length / runs.length
}

export async function printEvolveReport(body: BodyClient): Promise<void> {
  const runs = (await body.rpc<{ runs: RunRecord[] }>("run.list", { limit: 100 })).runs
  const entries = await body.rpc<MemoryEntry[]>("memory.export", {})
  const active = entries.filter((e) => !e.revoked)
  const now = Date.now()

  console.log("═".repeat(56))
  console.log("  自进化报表（ME-7）")
  console.log(`  ${new Date().toLocaleString()} · 数据源 runs×${runs.length} memories×${active.length}(active)`)
  console.log("═".repeat(56))

  // 1. 成功率趋势：近 7/30 天 vs 更早
  const week = runs.filter((r) => now - r.archivedAt < 7 * 864e5)
  const month = runs.filter((r) => now - r.archivedAt < 30 * 864e5)
  const earlier = runs.filter((r) => now - r.archivedAt >= 30 * 864e5)
  console.log("\n▍任务成功率")
  console.log(`  近 7 天：${pct(successRate(week))}（${week.length} 次）`)
  console.log(`  近 30 天：${pct(successRate(month))}（${month.length} 次）`)
  if (earlier.length >= 5) console.log(`  30 天前：${pct(successRate(earlier))}（${earlier.length} 次）← 对比看进化`)
  const outcomeDist = new Map<string, number>()
  for (const r of runs) outcomeDist.set(r.outcome, (outcomeDist.get(r.outcome) ?? 0) + 1)
  console.log(`  终态分布：${[...outcomeDist].map(([k, v]) => `${k}×${v}`).join("  ")}`)

  // 2. 注入 A/B（ME-7 埋点 memoriesInjected；仅统计有埋点记录以来的 run）
  const withProbe = runs.filter((r) => r.memoriesInjected !== undefined)
  if (withProbe.length >= 8) {
    const injected = withProbe.filter((r) => (r.memoriesInjected ?? 0) > 0)
    const plain = withProbe.filter((r) => (r.memoriesInjected ?? 0) === 0)
    if (injected.length >= 3 && plain.length >= 3) {
      console.log("\n▍记忆注入 A/B（成功率）")
      console.log(`  注入组：${pct(successRate(injected))}（${injected.length} 次）`)
      console.log(`  无注入：${pct(successRate(plain))}（${plain.length} 次）`)
      console.log("  （样本 <30 仅作参考；差距持续为负需排查注入质量）")
    }
  } else {
    console.log("\n▍记忆注入 A/B：埋点样本不足（需 ≥8 条含埋点 run）")
  }

  // 3. 记忆库健康
  const byKind = new Map<string, MemoryEntry[]>()
  for (const e of active) byKind.set(e.kind, [...(byKind.get(e.kind) ?? []), e])
  console.log("\n▍记忆库")
  console.log(`  active ${active.length} / revoked 留痕 ${entries.length - active.length}（容量线 500）`)
  for (const [kind, list] of byKind) {
    const avgConf = list.reduce((s, e) => s + e.confidence, 0) / list.length
    const zeroHit = list.filter((e) => (e.hits ?? 0) === 0).length
    console.log(`  ${kind.padEnd(10)} ${String(list.length).padStart(3)} 条 · 均值信度 ${avgConf.toFixed(2)} · 零命中 ${zeroHit}`)
  }

  // 4. lesson 复用（hits = 被检索命中次数）
  const lessons = active.filter((e) => e.kind === "lesson")
  if (lessons.length > 0) {
    const reused = lessons.filter((e) => (e.hits ?? 0) > 0)
    console.log("\n▍教训复用")
    console.log(`  ${reused.length}/${lessons.length} 条 lesson 被命中过；Top：`)
    for (const e of [...lessons].sort((a, b) => (b.hits ?? 0) - (a.hits ?? 0)).slice(0, 3)) {
      console.log(`    [hits ${e.hits ?? 0}] ${e.topic}：${e.content.slice(0, 40)}`)
    }
  }

  // 5. 技能复用（skill.list 数量 + runs trace 中 skill.run 出现）
  try {
    const skills = (await body.rpc<SkillSummary>("skill.list", {})).skills
    const skillRuns = runs.filter((r) => (r.trace ?? []).some((s) => (s as { tool?: string }).tool === "skill.run"))
    const skillRunOk = skillRuns.filter((r) => r.outcome === "success").length
    console.log("\n▍技能飞轮")
    console.log(`  已固化技能 ${skills.length} 个；近 100 run 中 skill.run ${skillRuns.length} 次（成功 ${skillRunOk}）`)
  } catch { /* skill.list 不可用不影响报表 */ }

  console.log("\n" + "═".repeat(56))
}

// CLI 直跑
if (process.argv[1]?.includes("evolve-report")) {
  const { BodyClient } = await import("../ipc/client.ts")
  const { env } = await import("../env.ts")
  const body = new BodyClient(env("BODY_URL", "http://127.0.0.1:8765"), env("BODY_TOKEN", "super-agent-dev"))
  await body.waitForBody()
  await printEvolveReport(body)
  process.exit(0)
}
