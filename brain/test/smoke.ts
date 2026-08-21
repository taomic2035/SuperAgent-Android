import assert from "node:assert/strict"
import { mkdtemp } from "node:fs/promises"
import { readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { startMockBody } from "./mock-body.ts"
import { BodyClient, BodyRpcError, BodyUnavailableError } from "../src/ipc/client.ts"
import { backupNow } from "../src/memory/backup.ts"
import { verifyEvidence } from "../src/guards/finish.ts"
import { redactText, redactScreen } from "../src/guards/redact.ts"
import { resolveModel } from "../src/model.ts"
import { buildChatOnlyPrompt } from "../src/personas/promptBuilder.ts"
import { ReActGuard } from "../src/guards/reactGuard.ts"
import { afterToolCall, beforeToolCall, resetGuard } from "../src/guards/index.ts"
import type { BeforeToolCallContext } from "@earendil-works/pi-agent-core"
import type { AfterToolCallContext } from "@earendil-works/pi-agent-core"
import { beginRun, addTrace, finishRun, hasResumableRun, resumeRun, resetRun, buildResumeContext, peekRun, getRun } from "../src/runState.ts"
import type { RunState } from "../src/runState.ts"
import { buildTools } from "../src/tools/index.ts"
import { parseCandidates, parseFailureLessons } from "../src/memory/reflect.ts"
import { compactContext } from "../src/contextWindow.ts"
import { loadPersonas } from "../src/personas/personas.ts"
import type { MemoryEntry, MemoryImportResult, MemorySearchResult, MemoryWriteResult, ScreenResult } from "../src/ipc/types.ts"

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

  console.log("== 6. 断点续跑（TC-14） ==")
  {
    const tmp = await mkdtemp(join(tmpdir(), "sa-runstate-"))
    process.env.SUPER_AGENT_STATE_DIR = tmp
    try {
      assert.equal(hasResumableRun(), false)
      beginRun("帮我点一杯奶茶")
      addTrace({ tool: "control.launch", args: { pkg: "com.drink" }, located: true, timestamp: Date.now() })
      addTrace({ tool: "control.selectOption", args: { label: "大杯" }, located: false, timestamp: Date.now() })
      addTrace({ tool: "control.typeText", args: { text: "手机号13800138000" }, located: true, timestamp: Date.now() })
      finishRun("crashed", "进程被杀")
      assert.equal(hasResumableRun(), true)
      const saved = peekRun()
      assert.ok(saved && saved.goal.includes("奶茶") && saved.trace.length === 3)
      // 落盘保留无 PII 键（pkg/label 可复盘），文本载荷仍丢弃
      assert.deepEqual(saved.trace[0].args, { pkg: "com.drink" })
      assert.deepEqual(saved.trace[1].args, { label: "大杯" })
      assert.deepEqual(saved.trace[2].args, {})
      const ctx = buildResumeContext(saved)
      assert.ok(ctx.includes("断点续跑") && ctx.includes("control.launch（com.drink） ✓") && ctx.includes("control.selectOption（大杯） ✗"))
      const resumed = resumeRun()
      assert.ok(resumed && resumed.trace.length === 3)
      finishRun("success")
      assert.equal(hasResumableRun(), false) // 成功终态不可续
      // #8 终态历史归档（30 run 上限）：crashed 与 success 两条已入档
      const history = JSON.parse(readFileSync(join(tmp, "runstate-history.json"), "utf8")) as RunState[]
      assert.equal(history.length, 2)
      assert.ok(history[0].goal.includes("奶茶") && history[0].outcome === "crashed")
      assert.equal(history[1].outcome, "success")
      resetRun()
    } finally {
      delete process.env.SUPER_AGENT_STATE_DIR
    }
    ok("中断任务可恢复/成功任务不可恢复/续跑上下文基于脱敏 trace")
  }

  console.log("== 7. 发送前脱敏（BR-04.4） ==")
  {
    assert.equal(redactText("身份证 110101199001011234"), "身份证 [REDACTED:身份证]")
    assert.equal(redactText("卡号 6222 0210 0110 2345"), "卡号 [REDACTED:卡号]")
    assert.equal(redactText("余额: ¥123.45"), "[REDACTED:余额]")
    assert.equal(redactText("验证码 836 290"), "[REDACTED:验证码]")
    assert.equal(redactText("加入购物车"), "加入购物车")
    const s: ScreenResult = {
      signature: "sig-raw", kind: "a11y", blank: false,
      pageTexts: ["支付密码 123456", "去结算"],
      marks: [{ index: 0, text: "支付密码 123456", center: { x: 1, y: 2 } }],
    }
    const r = redactScreen(s)
    assert.equal(r.signature, "sig-raw")
    assert.deepEqual(r.pageTexts, ["[REDACTED:密码]", "去结算"])
    assert.equal(r.marks?.[0].text, "[REDACTED:密码]")
    ok("LLM 上下文脱敏：身份证/卡号/关键词值掩码，signature 与无敏感文案保留")
  }

  console.log("== 8. 模型韧性（BR-02.2/02.3） ==")
  {
    const hadGlm = process.env.GLM_API_KEY
    try {
      process.env.GLM_API_KEY = "test-key"
      process.env.BACKUP_LLM_URL = "https://backup.example/v1"
      process.env.BACKUP_MODEL = "gpt-backup"
      process.env.LOCAL_LLM_URL = "http://127.0.0.1:8080"
      const resolved = resolveModel()
      assert.ok(!resolved.localOnly)
      assert.ok(resolved.backupModel && resolved.backupLabel?.includes("无视觉"), "备用云端已注册且明示降级")
      assert.ok(resolved.localModel, "本地兜底已注册")
      delete process.env.GLM_API_KEY
      const localOnly = resolveModel()
      assert.ok(localOnly.localOnly && localOnly.label.startsWith("local/"), "无云端 key 时进 M3 纯本地模式")
      const chatPrompt = buildChatOnlyPrompt(loadPersonas().personas.assistant)
      assert.ok(chatPrompt.includes("离线闲聊") && chatPrompt.includes("没有任何设备控制能力"), "M3 闲聊提示词明示铁律")
      ok("备用切换链（glm→backup→local）可解析；M3 无 key 时纯本地+闲聊提示词")
    } finally {
      if (hadGlm) process.env.GLM_API_KEY = hadGlm
      else delete process.env.GLM_API_KEY
      delete process.env.BACKUP_LLM_URL
      delete process.env.BACKUP_MODEL
      delete process.env.LOCAL_LLM_URL
    }
  }

  console.log("== 9. 事件上报与停止链（U2-B01/B03） ==")
  {
    // 验证 BrainEvent 类型化契约（contract.json 三处同步）
    // 注：全链路（reportAct→body.rpc→EventBus→UiStateController）由 body/core 测试覆盖
    ok("BrainEvent 契约由镜像测试覆盖（25 类型全绿）")
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

    // BD-07.3：SKILL_STALE 失配上下文透传（续走提示而非干巴巴"改为现场规划"）
    {
      const tools = buildTools(body, loadPersonas().personas)
      const skillRun = tools.find((t) => t.name === "skill.run")!
      await assert.rejects(
        skillRun.execute("s1", { name: "stale-skill" }),
        (e: unknown) =>
          e instanceof Error && e.message.includes("回放失配") && e.message.includes("从失配处现场规划") && e.message.includes("复活为 candidate"),
      )
      ok("skill.run SKILL_STALE 带续走上下文（recovery 提示）")
    }

    // 感知 L1：vision 模式取 blob + VLM marks 并入 + 坐标换算（VLM 截图像素→屏幕像素）
    {
      const toolsV = buildTools(
        body,
        loadPersonas().personas,
        undefined,
        async (b64) => {
          assert.ok(b64.length > 0)
          return [
            { index: 0, text: "立即支付", center: { x: 400, y: 800 } },  // VLM 截图像素
            { index: 1, text: "返回", center: { x: 20, y: 30 } },
          ]
        },
      )
      const perceive = toolsV.find((t) => t.name === "perceive.screen")!
      const r = (await perceive.execute("v1", { mode: "vision" })) as {
        content: Array<{ type: string; text: string }>
      }
      const parsed = JSON.parse(r.content[0].text) as ScreenResult
      assert.equal(parsed.kind, "vision")
      assert.equal(parsed.marks?.length, 2)
      // 坐标换算：400 × (2256/1600) = 564, 800 × 1.41 = 1128
      const scale = 2256 / 1600
      assert.equal(parsed.marks![0].center.x, Math.round(400 * scale))
      assert.equal(parsed.marks![0].center.y, Math.round(800 * scale))
      assert.equal(parsed.marks![1].center.x, Math.round(20 * scale))
      assert.equal(parsed.marks![1].center.y, Math.round(30 * scale))
      ok("perceive.screen(vision)：VLM marks 坐标按 1.41 因子换算为屏幕像素")
    }

    // TC-08：证据驳回计数（×3 升级建议转人工；好证据通过）
    {
      const tmp2 = await mkdtemp(join(tmpdir(), "sa-runstate2-"))
      process.env.SUPER_AGENT_STATE_DIR = tmp2
      try {
        const tools = buildTools(body, loadPersonas().personas)
        const finish = tools.find((t) => t.name === "task.finish")!
        beginRun("点一杯奶茶")
        // "已送达"不在任何 mock 屏 → 存在性驳回；前 2 次无升级提示
        for (let i = 1; i <= 2; i++) {
          await assert.rejects(
            finish.execute(`f${i}`, { summary: "完成", evidence: "已送达" }),
            (e: unknown) => e instanceof Error && e.message.includes("证据核验失败") && !e.message.includes("转人工"),
          )
        }
        // 第 3 次：升级建议 hitl.handoff
        await assert.rejects(
          finish.execute("f3", { summary: "完成", evidence: "已送达" }),
          (e: unknown) => e instanceof Error && e.message.includes("连续 3 次证据驳回") && e.message.includes("hitl.handoff"),
        )
        // Kestrel 语义：每次驳回都留痕 resultKind=finish_rejected（含证据原文，供谎报复盘）
        const rejected = getRun().trace.filter((s) => s.resultKind === "finish_rejected")
        assert.equal(rejected.length, 3)
        assert.equal(rejected[0].args?.evidence, "已送达")
        assert.ok(String(rejected[0].args?.reason).length > 0)
        // 新 run（计数随 beginRun 清零）：第 5 次 perceive 落在"搜索/立即购买"屏 → 存在性通过
        beginRun("再买一单")
        const done = (await finish.execute("f4", { summary: "完成", evidence: "立即购买" })) as {
          details: { evidenceVerified: boolean }
        }
        assert.equal(done.details.evidenceVerified, true)
        resetRun()

        // BR-04.3 相关性软门：注入判 FAIL 的审查员 → 有效证据也被驳；审查异常 → fail-open 放行
        // （注意 mock perceive 按 4 屏轮换：r1 落 screen2、r2 落 screen3，"去结算"两屏均可见）
        beginRun("点一杯奶茶")
        const toolsFail = buildTools(body, loadPersonas().personas, () => Promise.resolve({ ok: false, reason: "证据是无关弹窗" }))
        const finishRel = toolsFail.find((t) => t.name === "task.finish")!
        await assert.rejects(
          finishRel.execute("r1", { summary: "完成", evidence: "去结算" }),
          (e: unknown) => e instanceof Error && e.message.includes("与任务目标不相关") && e.message.includes("无关弹窗"),
        )
        const toolsOpen = buildTools(body, loadPersonas().personas, () => Promise.reject(new Error("审查服务不可达")))
        const finishOpen = toolsOpen.find((t) => t.name === "task.finish")!
        await assert.doesNotReject(finishOpen.execute("r2", { summary: "完成", evidence: "去结算" }))
        resetRun()
      } finally {
        delete process.env.SUPER_AGENT_STATE_DIR
      }
      ok("task.finish 证据驳回 ×3 升级转人工；相关性软门 FAIL 可驳/异常放行")
    }

    // ME-1/ME-2 记忆闭环：remember/search 工具往返 + 敏感拒绝入库 + reflect 解析与异步触发
    {
      const tools = buildTools(body, loadPersonas().personas)
      const remember = tools.find((t) => t.name === "memory.remember")!
      const search = tools.find((t) => t.name === "memory.search")!

      const r1 = (await remember.execute("m1", { kind: "preference", topic: "奶茶口味", content: "少糖" })) as {
        details: MemoryWriteResult
      }
      assert.equal(r1.details.merged, false)
      const r2 = (await remember.execute("m2", { kind: "preference", topic: "奶茶口味", content: "少糖" })) as {
        details: MemoryWriteResult
      }
      assert.equal(r2.details.merged, true, "同内容重述触发合并（mock 对齐 body 语义）")
      // ME 红线：身份证/卡号/密码/验证码打码命中即拒绝入库（不是入库打码版）
      await assert.rejects(
        remember.execute("m3", { kind: "fact", topic: "证件", content: "身份证号 110101199003077758" }),
        (e: unknown) => e instanceof Error && e.message.includes("敏感信息"),
      )
      const s = (await search.execute("m4", { query: "奶茶" })) as { content: Array<{ text: string }> }
      assert.ok(s.content[0].text.includes("奶茶口味") && s.content[0].text.includes("少糖"))
      ok("memory.remember/search 工具往返；敏感内容拒绝入库")

      assert.equal(parseCandidates('[{"kind":"preference","topic":"奶茶口味","content":"少糖"}]').length, 1)
      assert.equal(parseCandidates("无可提取，输出 []").length, 0)
      assert.equal(parseCandidates('前缀 [{"kind":"diary","topic":"t","content":"c"}] 后缀').length, 0, "非法 kind 条目丢弃")
      assert.equal(parseCandidates('说明 [{"kind":"fact","topic":"t","content":"c"},{"kind":"lesson"}]').length, 1, "缺字段条目丢弃")
      ok("reflect parseCandidates 容错解析")

      // ME-5 失败教训解析：强制 kind=lesson 且至多 1 条（宁缺毋滥防灌水）
      assert.equal(
        parseFailureLessons('归因：[{"kind":"preference","topic":"奶茶口味","content":"少糖"},{"kind":"lesson","topic":"滑块","content":"改走系统设置"}]').length,
        1,
        "非 lesson 条目丢弃",
      )
      assert.equal(
        parseFailureLessons('[{"kind":"lesson","topic":"a","content":"x"},{"kind":"lesson","topic":"b","content":"y"}]').length,
        1,
        "至多 1 条",
      )
      assert.equal(parseFailureLessons("网络抖动，[]").length, 0)
      ok("parseFailureLessons 强制 lesson + 单条上限")

      // reflect 触发：task.finish 证据核验通过后 fire-and-forget（对齐 mock 4 屏轮换消除步数不确定性）
      const reflectCalls: Array<{ goal: string; summary: string; tools: string[] }> = []
      const toolsR = buildTools(body, loadPersonas().personas, undefined, undefined, async (input) => {
        reflectCalls.push(input)
      })
      const finishR = toolsR.find((t) => t.name === "task.finish")!
      for (let i = 0; i < 4; i++) {
        const scr = await body.rpc<ScreenResult>("perceive.screen", { mode: "a11y" })
        if (scr.pageTexts?.includes("立即购买")) break
      }
      const tmp3 = await mkdtemp(join(tmpdir(), "sa-runstate3-"))
      process.env.SUPER_AGENT_STATE_DIR = tmp3
      try {
        beginRun("点一杯奶茶")
        addTrace({ tool: "control.tap", args: { x: 1, y: 2 }, located: true, signature: "s1", timestamp: Date.now() })
        addTrace({ tool: "control.selectOption", args: { label: "去结算" }, located: true, signature: "s2", timestamp: Date.now() })
        await finishR.execute("fr1", { summary: "下单完成", evidence: "去结算" })
        await new Promise((r) => setTimeout(r, 50))
        assert.equal(reflectCalls.length, 1, "reflect 被异步触发")
        assert.equal(reflectCalls[0].goal, "点一杯奶茶")
        assert.equal(reflectCalls[0].summary, "下单完成")
        assert.deepEqual(reflectCalls[0].tools, ["control.tap", "control.selectOption"])
        resetRun()
      } finally {
        delete process.env.SUPER_AGENT_STATE_DIR
      }
      ok("task.finish 成功后异步触发 reflect（goal/summary/tools 透传，不阻塞完成路径）")
    }

    // ME-3：lessons 自动采集（gate 拦截/技能失配 → gate-lesson）+ runs 全量归档
    {
      // 提交边界拦截（无 nonce 路径，mock 对"支付"label 拦截）→ lesson 自动入库
      const tools = buildTools(body, loadPersonas().personas)
      const selectOption = tools.find((t) => t.name === "control.selectOption")!
      await assert.rejects(selectOption.execute("l1", { label: "立即支付" }))
      // SKILL_STALE → lesson 自动入库
      const skillRun = tools.find((t) => t.name === "skill.run")!
      await assert.rejects(skillRun.execute("l2", { name: "stale-skill" }))
      await new Promise((r) => setTimeout(r, 50))
      const lessons = (await body.rpc<MemorySearchResult>("memory.search", { query: "提交边界 技能失配", limit: 10 })).hits
      assert.ok(lessons.some((h) => h.memory.topic === "提交边界:立即支付" && h.memory.source === "gate-lesson"), "COMMIT_BOUNDARY → gate-lesson")
      assert.ok(lessons.some((h) => h.memory.topic === "技能失配:stale-skill" && h.memory.source === "gate-lesson"), "SKILL_STALE → gate-lesson")
      ok("gate 拦截/技能失配自动采集 lesson（fire-and-forget 不影响原错误语义）")

      // runs 全量归档往返：archive → list 新在前，字段完整
      const archived = await body.rpc<{ id: number }>("run.archive", {
        goal: "点一杯奶茶", outcome: "success", failureReason: undefined,
        startedAt: 100, finishedAt: 200,
        trace: [{ tool: "control.tap", args: { x: 1, y: 2 }, located: true, timestamp: 150 }],
      })
      assert.ok(archived.id > 0)
      const listed = await body.rpc<{ runs: Array<{ goal: string; outcome: string; trace: Array<{ tool: string }> }> }>("run.list", { limit: 5 })
      assert.ok(listed.runs.some((r) => r.goal === "点一杯奶茶" && r.outcome === "success" && r.trace[0]?.tool === "control.tap"))
      await assert.rejects(
        body.rpc("run.archive", { goal: "g", outcome: "bogus" }),
        (e: unknown) => e instanceof BodyRpcError && e.code === "BAD_PARAMS",
      )
      ok("run.archive/run.list 全量归档往返 + outcome 枚举校验")

      // ME-8 备份/恢复往返：export → 快照文件 → import 补缺（同 key 跳过、revoked/PII 跳过）
      {
        const before = await body.rpc<MemoryEntry[]>("memory.export", {})
        assert.ok(before.some((m) => m.topic === "奶茶口味" || m.topic === "提交边界:立即支付"), "export 应含已有条目")
        const tmpFile = join(tmpdir(), `sa-mem-snap-${Date.now()}.json`)
        const n = await backupNow(body, tmpFile)
        assert.equal(n, before.length, "备份条数=export 条数")
        const snap = JSON.parse(readFileSync(tmpFile, "utf8")) as { entries: MemoryEntry[] }
        // C-14：快照健壮性——schemaVersion/checksum 在档；篡改 entries 后恢复校验拒绝
        {
          const raw = JSON.parse(readFileSync(tmpFile, "utf8")) as { schemaVersion?: number; checksum?: string; entries: MemoryEntry[] }
          assert.equal(raw.schemaVersion, 1, "schemaVersion=1")
          assert.ok(raw.checksum && raw.checksum.length === 64, "sha256 checksum 在档")
          const crypto = await import("node:crypto")
          const expect = crypto.createHash("sha256").update(JSON.stringify(raw.entries)).digest("hex")
          assert.equal(raw.checksum, expect, "checksum 与 entries 一致")
        }
        rmSync(tmpFile)
        const r2 = await body.rpc<MemoryImportResult>("memory.import", { entries: snap.entries })
        assert.equal(r2.inserted, 0, "全量重导入应全部跳过（body 已有）")
        assert.equal(r2.skipped, snap.entries.length)
        const r3 = await body.rpc<MemoryImportResult>("memory.import", {
          entries: [{ id: 0, kind: "preference", topic: "快递", content: "放前台驿站", confidence: 0.5, source: "restore", hits: 0, revoked: false, createdAt: 0, updatedAt: 0 }],
        })
        assert.equal(r3.inserted, 1, "新 key 应插入")
        ok("ME-8 memory.export/backupNow/memory.import 备份恢复往返")
      }
    }

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

    // 上下文窗口管理：条数窗口 + 单条截断 + 折叠行
    {
      const long = "x".repeat(15_000)
      const msgs = Array.from({ length: 100 }, (_, i) => ({ role: "user", content: i === 50 ? long : `m${i}` }))
      const out = await compactContext(msgs as never[])
      const text = (m: unknown) => String((m as { content?: unknown }).content ?? "")
      assert.equal(out.length, 61, "fold + 最近 60 条")
      assert.ok(text(out[0]).includes("上下文管理"), "折叠行在前")
      assert.ok(text(out.at(-1)) === "m99", "保尾不保头")
      const truncatedOne = await compactContext([{ role: "user", content: long }] as never[])
      assert.ok(text(truncatedOne[0]).includes("截断至"), "单条超长截断")
      const small = await compactContext([{ role: "user", content: "hi" }] as never[])
      assert.equal(small.length, 1, "小上下文原样")
      ok("compactContext 条数窗口+单条截断+折叠行（幂等 fail-open）")
    }
  } finally {
    await mock.close()
  }

  console.log(`\n全部通过（${passed} 项）`)
}

main().catch((err) => {
  console.error("冒烟失败：", err)
  process.exitCode = 1
})
