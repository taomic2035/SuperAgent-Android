import assert from "node:assert/strict"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { VisionActionProvenance } from "../src/guards/vision-action-context.ts"
import { redactScreen } from "../src/guards/redact.ts"
import { buildTools } from "../src/tools/index.ts"
import type { BodyClient } from "../src/ipc/client.ts"
import type { ScreenResult } from "../src/ipc/types.ts"
import { beginRun, getRun, resetRun } from "../src/runState.ts"

let passed = 0
function ok(name: string): void {
  passed++
  console.log(`  ✓ ${name}`)
}

function screen(kind: ScreenResult["kind"], visionActionToken?: string): ScreenResult {
  return {
    signature: `${kind}-screen`,
    kind,
    blank: false,
    pageTexts: ["验证码 123456", "普通文字"],
    marks: [{ index: 0, text: "余额 888", center: { x: 10, y: 20 } }],
    nodes: [{
      label: "卡号 6222021001112222",
      clickable: true,
      bounds: { left: 0, top: 0, right: 20, bottom: 40 },
    }],
    visionActionToken,
  }
}

async function main(): Promise<void> {
  {
    const provenance = new VisionActionProvenance()
    const original = { x: 1, y: 2 }

    provenance.observe(screen("vision", "opaque-token"), 101)
    assert.deepEqual(provenance.attach(original, 101), {
      x: 1,
      y: 2,
      visionActionToken: "opaque-token",
    })
    assert.deepEqual(original, { x: 1, y: 2 })

    provenance.observe(screen("a11y", "must-not-survive"), 101)
    assert.deepEqual(provenance.attach(original, 101), original)
    provenance.observe(screen("vision"), 101)
    assert.deepEqual(provenance.attach(original, 101), original)
    provenance.observe(screen("vision", "   "), 101)
    assert.deepEqual(provenance.attach(original, 101), original)

    provenance.observe(screen("vision", "next-token"), 101)
    assert.deepEqual(provenance.attach(original, 202), original)
    assert.deepEqual(provenance.attach(original, 101), original)

    provenance.observe(screen("vision", "clear-token"), 303)
    provenance.clear()
    assert.deepEqual(provenance.attach(original, 303), original)
    ok("provenance only attaches a non-empty vision token to the same run without mutating params")
  }

  {
    const token = "opaque-secret-token"
    const serialized = JSON.stringify(redactScreen(screen("vision", token)))
    assert.equal(serialized.includes(token), false)
    assert.equal(serialized.includes("visionActionToken"), false)
    assert.equal(serialized.includes("123456"), false)
    assert.equal(serialized.includes("888"), false)
    assert.equal(serialized.includes("6222021001112222"), false)
    assert.equal(serialized.includes("vision-screen"), true)
    assert.equal(serialized.includes("普通文字"), true)
    ok("redactScreen removes action provenance while preserving existing redaction and public fields")
  }

  {
    const stateDir = await mkdtemp(join(tmpdir(), "sa-vision-action-"))
    const previousStateDir = process.env.SUPER_AGENT_STATE_DIR
    process.env.SUPER_AGENT_STATE_DIR = stateDir
    try {
      const token = "tool-opaque-token"
      let nextScreen = screen("vision", token)
      const rpcCalls: Array<{ method: string; params: Record<string, unknown> }> = []
      const fakeBody = {
        async rpc<T>(method: string, params: unknown): Promise<T> {
          const objectParams = params as Record<string, unknown>
          rpcCalls.push({ method, params: objectParams })
          if (method === "perceive.screen") return nextScreen as T
          return { located: true, visionActionToken: objectParams.visionActionToken } as T
        },
        async blob(): Promise<Buffer> {
          throw new Error("unexpected blob call")
        },
      } as unknown as BodyClient

      const tools = buildTools(fakeBody, {})
      const perceive = tools.find((tool) => tool.name === "perceive.screen")!
      const actionNames = [
        "control.tap",
        "control.longPress",
        "control.swipe",
        "control.selectOption",
        "control.selectSpec",
      ] as const
      const actionParams: Record<(typeof actionNames)[number], Record<string, unknown>> = {
        "control.tap": { x: 10, y: 20 },
        "control.longPress": { x: 10, y: 20, durationMs: 400 },
        "control.swipe": { fromX: 10, fromY: 20, toX: 30, toY: 40, durationMs: 500 },
        "control.selectOption": { label: "立即支付", near: { x: 10, y: 20 } },
        "control.selectSpec": { label: "大杯", near: { x: 10, y: 20 } },
      }

      beginRun("vision provenance integration")
      getRun().startedAt = 1001
      const perceiveResult = await perceive.execute("perceive-vision", { mode: "vision" })
      assert.equal(JSON.stringify(perceiveResult).includes(token), false)

      for (const name of actionNames) {
        const tool = tools.find((candidate) => candidate.name === name)!
        assert.equal(JSON.stringify(tool.parameters).includes("visionActionToken"), false)
        const result = await tool.execute(`${name}-vision`, actionParams[name])
        const call = rpcCalls.at(-1)!
        assert.equal(call.method, name)
        assert.equal(call.params.visionActionToken, token)
        assert.equal(Object.hasOwn(actionParams[name], "visionActionToken"), false)
        assert.equal(JSON.stringify(result).includes(token), false)
        assert.equal(JSON.stringify(result).includes("visionActionToken"), false)
      }

      nextScreen = screen("a11y", "a11y-token-must-be-ignored")
      await perceive.execute("perceive-a11y", { mode: "a11y" })
      for (const name of actionNames) {
        const tool = tools.find((candidate) => candidate.name === name)!
        await tool.execute(`${name}-a11y`, actionParams[name])
        assert.equal(Object.hasOwn(rpcCalls.at(-1)!.params, "visionActionToken"), false)
      }

      const finish = tools.find((candidate) => candidate.name === "task.finish")!
      nextScreen = screen("vision", token)
      await perceive.execute("perceive-before-failed-finish", { mode: "vision" })
      nextScreen = { ...screen("a11y"), pageTexts: ["仍在进行"] }
      await assert.rejects(finish.execute("finish-failed", { summary: "未完成", evidence: "不存在的证据" }))
      await tools.find((candidate) => candidate.name === "control.tap")!.execute("tap-after-failed-finish", actionParams["control.tap"])
      assert.equal(Object.hasOwn(rpcCalls.at(-1)!.params, "visionActionToken"), false)

      nextScreen = screen("vision", token)
      await perceive.execute("perceive-before-successful-finish", { mode: "vision" })
      nextScreen = { ...screen("a11y"), pageTexts: ["已完成"] }
      await finish.execute("finish-success", { summary: "完成", evidence: "已完成" })
      await tools.find((candidate) => candidate.name === "control.tap")!.execute("tap-after-successful-finish", actionParams["control.tap"])
      assert.equal(Object.hasOwn(rpcCalls.at(-1)!.params, "visionActionToken"), false)

      nextScreen = screen("vision", token)
      getRun().startedAt = 1002
      await perceive.execute("perceive-next-run", { mode: "vision" })
      getRun().startedAt = 1003
      for (const name of actionNames) {
        const tool = tools.find((candidate) => candidate.name === name)!
        await tool.execute(`${name}-changed-run`, actionParams[name])
        assert.equal(Object.hasOwn(rpcCalls.at(-1)!.params, "visionActionToken"), false)
      }
      ok("buildTools propagates provenance to five actions and hides it from schemas and tool results")
    } finally {
      resetRun()
      if (previousStateDir === undefined) delete process.env.SUPER_AGENT_STATE_DIR
      else process.env.SUPER_AGENT_STATE_DIR = previousStateDir
      await rm(stateDir, { recursive: true, force: true })
    }
  }

  console.log(`\nvision action context: ${passed} passed`)
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
