import assert from "node:assert/strict"
import { startMockBody } from "./mock-body.ts"
import { BodyClient, BodyRpcError, BodyUnavailableError } from "../src/ipc/client.ts"
import { verifyEvidence } from "../src/guards/finish.ts"
import { ReActGuard } from "../src/guards/reactGuard.ts"
import { afterToolCall, beforeToolCall, resetGuard } from "../src/guards/index.ts"
import type { BeforeToolCallContext } from "@earendil-works/pi-agent-core"
import type { AfterToolCallContext } from "@earendil-works/pi-agent-core"
import type { ScreenResult } from "../src/ipc/types.ts"

let passed = 0
function ok(name: string): void {
  passed++
  console.log(`  ✓ ${name}`)
}

async function main(): Promise<void> {
  // AD-01：提交边界/敏感会话/URL/App 词表判定已下沉 body（Guard.kt），
  // brain 不再持有词表，相关测试由 body 侧 GuardTest.kt 覆盖。

  console.log("== 2. 证据核验 ==")
  const screenA: ScreenResult = {
    signature: "s1", kind: "a11y", blank: false, appPackage: "com.example.shop",
    pageTexts: ["购物车", "提交订单", "共 2 件商品"],
  }
  const baseline: ScreenResult = { ...screenA, signature: "s0" }
  assert.equal(verifyEvidence(screenA, undefined, "提交订单").ok, true)
  assert.equal(verifyEvidence(screenA, undefined, "立即支付").ok, false)
  assert.equal(verifyEvidence(screenA, baseline, "提交订单").ok, false)
  assert.equal(verifyEvidence(screenA, undefined, "订").ok, false)
  ok("存在性/新颖性/过短校验正确")

  // AD-05 R3：技能检索（TF-IDF）已下沉 body skill.search RPC，brain 不再持有索引。
  // body 侧检索由 SkillStoreTest 覆盖。

  console.log("== 5. ReAct 止损（H1 回归） ==")
  {
    const guard = new ReActGuard()
    for (let i = 0; i < 31; i++) guard.record("control.tap", { x: i * 100, y: 0 }, `s${i}`, `s${i + 1}`)
    assert.equal(guard.shouldAbort(), "max_steps")
    guard.reset()
    assert.equal(guard.totalSteps, 0)
    assert.equal(guard.shouldAbort(), null)
    ok("max_steps 按 run 计：reset 全量清空，不跨任务累计")
  }
  {
    const afterCtx = (tool: string, args: Record<string, unknown>) =>
      ({ toolCall: { name: tool }, args, result: { content: [], details: { located: true } }, isError: false }) as unknown as AfterToolCallContext
    const beforeCtx = (tool: string) => ({ toolCall: { name: tool } }) as unknown as BeforeToolCallContext
    resetGuard()
    for (let i = 0; i < 31; i++) {
      await afterToolCall(afterCtx("control.tap", { x: i * 100, y: i * 100 }))
    }
    const blocked = await beforeToolCall(beforeCtx("control.tap"))
    assert.ok(blocked !== undefined && blocked.block === true)
    assert.equal(await beforeToolCall(beforeCtx("perceive.screen")), undefined)
    assert.equal(await beforeToolCall(beforeCtx("task.finish")), undefined)
    assert.equal(await beforeToolCall(beforeCtx("hitl.handoff")), undefined)
    resetGuard()
    assert.equal(await beforeToolCall(beforeCtx("control.tap")), undefined)
    ok("止损拦动作工具、豁免感知/收尾/转人工通道；reset 后恢复")
  }

  console.log("== 4. mock 躯体 IPC ==")
  const mock = await startMockBody({ port: 0 })
  try {
    const body = new BodyClient(`http://127.0.0.1:${mock.port}`, "super-agent-dev")

    await body.waitForBody()
    ok("waitForBody /health 通过")

    const s1 = await body.rpc<ScreenResult>("perceive.screen", {})
    assert.equal(s1.appPackage, "com.example.shop")
    ok("perceive.screen 往返")

    await assert.rejects(
      body.rpc("control.selectOption", { label: "立即支付" }),
      (err: unknown) => err instanceof BodyRpcError && err.code === "COMMIT_BOUNDARY",
    )
    ok("躯体侧提交边界拦截返回 COMMIT_BOUNDARY")

    const skills = await body.rpc<{ skills: { name: string }[] }>("skill.list", {})
    assert.equal(skills.skills.length, 2)
    ok("skill.list 往返")

    const learned = await body.rpc<{ slug: string }>("skill.learn", { goal: "下单奶茶", appPackage: "com.example.shop" })
    assert.ok(learned.slug.startsWith("skill-com.example.shop-"))
    ok("skill.learn 生成 slug")

    const ev1 = await body.events(0)
    assert.ok(ev1.length >= 1)
    const lastSeq = ev1[ev1.length - 1].seq
    const ev2 = await body.events(lastSeq)
    assert.ok(Array.isArray(ev2))
    ok("短轮询事件订阅工作")

    await assert.rejects(
      new BodyClient(`http://127.0.0.1:${mock.port}`, "wrong-token").rpc("apps", {}),
      (err: unknown) => err instanceof BodyRpcError && err.code === "UNAUTHORIZED",
    )
    ok("错误 token 被 401 拒绝")

    await assert.rejects(
      new BodyClient("http://127.0.0.1:1", "super-agent-dev").waitForBody(3, 100),
      (err: unknown) => err instanceof BodyUnavailableError,
    )
    ok("躯体不可达抛出 BodyUnavailableError")
  } finally {
    await mock.close()
  }

  console.log(`\n全部通过（${passed} 项）`)
}

main().catch((err) => {
  console.error("冒烟失败：", err)
  process.exitCode = 1
})
