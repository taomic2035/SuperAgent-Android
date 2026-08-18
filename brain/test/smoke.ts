import assert from "node:assert/strict"
import { startMockBody } from "./mock-body.ts"
import { BodyClient, BodyRpcError, BodyUnavailableError } from "../src/ipc/client.ts"
import { isCommitBoundary, isSensitiveContext } from "../src/guards/commitBoundary.ts"
import { verifyEvidence } from "../src/guards/finish.ts"
import { SkillIndex } from "../src/skills/index.ts"
import type { ScreenResult } from "../src/ipc/types.ts"

let passed = 0
function ok(name: string): void {
  passed++
  console.log(`  ✓ ${name}`)
}

async function main(): Promise<void> {
  console.log("== 1. 提交边界 ==")
  assert.equal(isCommitBoundary("立即支付"), true)
  assert.equal(isCommitBoundary("提交订单"), true)
  assert.equal(isCommitBoundary("收银台"), false)
  assert.equal(isSensitiveContext("收银台"), true)
  assert.equal(isCommitBoundary("加入购物车"), false)
  assert.equal(isCommitBoundary("立即购买"), false)
  assert.equal(isCommitBoundary("支付宝"), false)
  ok("isCommitBoundary 命中/放行/误伤防护正确")

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

  console.log("== 3. 技能索引 TF-IDF ==")
  const index = new SkillIndex()
  index.rebuild([
    { name: "order-milk-tea", description: "在示例商城下单奶茶", appPackage: "com.example.shop", tags: ["购物", "奶茶"] },
    { name: "open-weather", description: "打开天气应用查看天气", appPackage: "com.example.weather", tags: ["天气"] },
    { name: "set-alarm", description: "设置闹钟提醒", appPackage: "com.example.clock", tags: ["闹钟"] },
  ])
  const hits = index.retrieve("帮我点一杯奶茶")
  assert.ok(hits.length > 0, "应命中至少一个技能")
  assert.equal(hits[0].skill.name, "order-milk-tea")
  const hitsWeather = index.retrieve("今天天气怎么样")
  assert.equal(hitsWeather[0]?.skill.name, "open-weather")
  const noHits = index.retrieve("背诵一首古诗")
  assert.equal(noHits.length, 0)
  ok("中文 2-gram TF-IDF 检索命中 top1 正确、无关查询为空")

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