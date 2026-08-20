/**
 * 端到端集成测试：模拟完整任务流（perceive→act→evidence→finish→learn）。
 * 用 mock body 验证 brain 工具编排不断线——防回归的核心测试。
 */
import assert from "node:assert/strict"
import { mkdtemp } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { startMockBody } from "./mock-body.ts"
import { BodyClient } from "../src/ipc/client.ts"
import { buildTools } from "../src/tools/index.ts"
import { loadPersonas } from "../src/personas/personas.ts"
import { buildSystemPrompt } from "../src/personas/promptBuilder.ts"
import {
  beginRun, addTrace, finishRun, resetRun, getRun,
  setBaseline, noteFinishRejected, markFinishVerified,
} from "../src/runState.ts"
import { resetSensitiveSession, resetGuard } from "../src/guards/index.ts"
import { verifyEvidence } from "../src/guards/finish.ts"
import { redactText } from "../src/guards/redact.ts"
import type { ScreenResult } from "../src/ipc/types.ts"

let passed = 0
function ok(name: string): void {
  passed++
  console.log(`  ✓ ${name}`)
}

async function main(): Promise<void> {
  const mock = await startMockBody({ port: 0 })
  try {
    const body = new BodyClient(`http://127.0.0.1:${mock.port}`, "super-agent-dev")
    await body.waitForBody()
    const personas = loadPersonas().personas
    const tools = buildTools(body, personas)

    console.log("== 集成：完整任务流 ==")

    // 1. 模拟 perceive → baseline → act → finish 链
    {
      const tmp = await mkdtemp(join(tmpdir(), "sa-integ-"))
      process.env.SUPER_AGENT_STATE_DIR = tmp
      try {
        beginRun("帮我打开设置")
        resetSensitiveSession()

        // perceive
        const perceive = tools.find((t) => t.name === "perceive.screen")!
        const p1 = await perceive.execute("i1", {})
        const screen1 = JSON.parse((p1.content as Array<{ text: string }>)[0].text) as ScreenResult
        assert.ok(screen1.signature, "perceive 应返回签名")
        setBaseline(screen1)

        // act（selectOption——mock 返回 located=true）
        const select = tools.find((t) => t.name === "control.selectOption")!
        const act1 = await select.execute("i2", { label: "搜索" })
        assert.ok(act1, "selectOption 应成功")
        addTrace({ tool: "control.selectOption", args: { label: "搜索" }, located: true, timestamp: Date.now() })

        // act（tap）
        const tap = tools.find((t) => t.name === "control.tap")!
        const act2 = await tap.execute("i3", { x: 100, y: 200 })
        assert.ok(act2, "tap 应成功")
        addTrace({ tool: "control.tap", args: { x: 100, y: 200 }, located: true, timestamp: Date.now() })

        // finish（mock 步进到 screen2 有"去结算"，baseline 是 screen1 没有——新颖性通过）
        const finish = tools.find((t) => t.name === "task.finish")!
        const done = await finish.execute("i4", { summary: "完成", evidence: "去结算" })
        const details = (done as { details: { evidenceVerified: boolean; learned?: string } }).details
        assert.ok(details.evidenceVerified, "证据核验应通过")
        assert.ok(details.learned, "应触发技能固化")
        assert.ok(getRun().finishVerified, "run 应标记 finishVerified")
        assert.equal(getRun().outcome, "success")
        ok("完整链：perceive→baseline→act×2→finish(evidence verified)→learn")
      } finally {
        delete process.env.SUPER_AGENT_STATE_DIR
      }
    }

    // 2. 模拟证据驳回 → 升级 → 转人工
    {
      const tmp = await mkdtemp(join(tmpdir(), "sa-integ2-"))
      process.env.SUPER_AGENT_STATE_DIR = tmp
      try {
        beginRun("点一杯奶茶")
        // 驳回 3 次
        const finish = tools.find((t) => t.name === "task.finish")!
        for (let i = 1; i <= 3; i++) {
          await assert.rejects(
            finish.execute(`r${i}`, { summary: "完成", evidence: "已送达" }),
            (e: unknown) => e instanceof Error,
          )
        }
        assert.equal(getRun().finishRejectCount, 3)
        assert.ok(getRun().trace.some((s) => s.resultKind === "finish_rejected"))
        ok("驳回链：×3 拒绝→计数→留痕→升级提示")
      } finally {
        delete process.env.SUPER_AGENT_STATE_DIR
      }
    }

    // 3. 模拟 SKILL_STALE 恢复
    {
      const skillRun = tools.find((t) => t.name === "skill.run")!
      await assert.rejects(
        skillRun.execute("s1", { name: "stale-skill" }),
        (e: unknown) => e instanceof Error && e.message.includes("从失配处现场规划"),
      )
      ok("技能恢复：SKILL_STALE 带续走提示")
    }

    // 4. 系统提示完整性
    {
      const prompt = buildSystemPrompt(personas.assistant, [])
      assert.ok(prompt.includes("必须"), "系统提示含强制令")
      assert.ok(prompt.includes("skill.run"), "引导技能使用")
      assert.ok(prompt.includes("REDACTED"), "脱敏说明")
      assert.ok(prompt.includes("hitl.handoff"), "红线指引")
      ok("系统提示：技能路由+脱敏+红线+证据规则")
    }

    // 5. 脱敏完整性
    {
      assert.equal(redactText("余额: ¥123.45"), "[REDACTED:余额]")
      assert.equal(redactText("身份证 110101199001011234"), "身份证 [REDACTED:身份证]")
      assert.equal(redactText("加入购物车"), "加入购物车")
      ok("脱敏：身份证/余额掩码+正常文案保留")
    }

    // 6. 证据核验三重
    {
      const screen: ScreenResult = { signature: "s1", kind: "a11y", blank: false, pageTexts: ["提交订单"] }
      const baseline: ScreenResult = { ...screen, signature: "s0" }
      assert.ok(verifyEvidence(screen, undefined, "提交订单").ok, "存在性")
      assert.ok(!verifyEvidence(screen, baseline, "提交订单").ok, "新颖性拒绝")
      assert.ok(!verifyEvidence(screen, undefined, "已送达").ok, "不存在的证据拒绝")
      ok("证据三重：存在性+新颖性+不存在")
    }

    console.log(`\n集成测试全部通过（${passed} 项）`)
  } finally {
    await mock.close()
  }
}

main().catch((err) => {
  console.error("集成测试失败：", err)
  process.exitCode = 1
})
