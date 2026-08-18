import { createServer, type IncomingMessage, type ServerResponse } from "node:http"
import type { RpcRequest, RpcResponse, BodyEvent } from "../src/ipc/types.ts"

export interface MockBodyOptions {
  port: number
  token?: string
}

export function startMockBody(options: MockBodyOptions): Promise<{ port: number; close: () => Promise<void> }> {
  const token = options.token ?? "super-agent-dev"
  const bootId = `mock-${Date.now()}`
  let eventSeq = 0
  const events: BodyEvent[] = []
  let apps = ["com.example.shop"]
  let steps = 0

  function authOk(req: IncomingMessage): boolean {
    return req.headers.authorization === `Bearer ${token}`
  }

  function sendJson(res: ServerResponse, status: number, body: unknown): void {
    res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" })
    res.end(JSON.stringify(body))
  }

  async function readBody(req: IncomingMessage): Promise<string> {
    const chunks: Buffer[] = []
    for await (const chunk of req) chunks.push(chunk as Buffer)
    return Buffer.concat(chunks).toString("utf8")
  }

  function handleRpc(req: IncomingMessage, res: ServerResponse, raw: string): void {
    if (!authOk(req)) {
      sendJson(res, 401, { id: null, ok: false, error: { code: "UNAUTHORIZED", message: "token 无效" } })
      return
    }
    let request: RpcRequest
    try {
      request = JSON.parse(raw) as RpcRequest
    } catch {
      sendJson(res, 400, { id: null, ok: false, error: { code: "BAD_REQUEST", message: "JSON 解析失败" } })
      return
    }
    const reply = (result: unknown): RpcResponse => ({ id: request.id, ok: true, result })
    const fail = (code: string, message: string, reason?: string): RpcResponse => ({
      id: request.id,
      ok: false,
      error: { code, message, reason },
    })

    const { method, params } = request
    switch (method) {
      case "perceive.screen": {
        const p = (params as { mode?: string }) ?? {}
        steps++
        events.push({ seq: ++eventSeq, type: "state", payload: { screen: steps % 4 } })
        const base = {
          signature: `mock-sig-${steps}`,
          kind: "a11y" as const,
          blank: false,
          appPackage: "com.example.shop",
        }
        if (steps % 4 === 0) {
          return void sendJson(res, 200, reply({ ...base, pageTexts: ["购物车", "提交订单", "共 2 件商品"] }))
        }
        if (steps % 4 === 1) {
          return void sendJson(res, 200, reply({ ...base, pageTexts: ["搜索", "输入框", "奶茶", "立即购买"] }))
        }
        if (steps % 4 === 2) {
          return void sendJson(res, 200, reply({ ...base, pageTexts: ["搜索", "奶茶 1L", "加入购物车", "去结算"] }))
        }
        return void sendJson(res, 200, reply({ ...base, pageTexts: ["搜索", "奶茶 1L", "去结算", "收银台", "立即支付"] }))
      }
      case "control.tap":
        return void sendJson(res, 200, reply({ located: true, signature: `mock-sig-${steps + 1}` }))
      case "control.longPress":
      case "control.swipe":
      case "control.typeText":
      case "control.back":
      case "control.home":
        return void sendJson(res, 200, reply({ located: true, signature: `mock-sig-${steps + 1}` }))
      case "control.launch": {
        const p = params as { pkg?: string }
        const ok = p.pkg !== undefined && p.pkg.length > 0
        return void sendJson(res, 200, reply({ located: ok, signature: ok ? `mock-sig-${steps + 1}` : undefined }))
      }
      case "control.selectOption": {
        const p = params as { label?: string }
        const label = p.label ?? ""
        if (label.includes("支付") || label.includes("付款")) {
          return void sendJson(res, 200, fail("COMMIT_BOUNDARY", "提交边界拦截（躯体侧兜底）", "commit"))
        }
        return void sendJson(res, 200, reply({ located: true, signature: `mock-sig-${steps + 1}` }))
      }
      case "control.selectSpec":
        return void sendJson(res, 200, reply({ located: true, signature: `mock-sig-${steps + 1}` }))
      case "speech.asr":
        return void sendJson(res, 200, reply({ text: "帮我点一杯奶茶", confidence: 0.92, durationMs: 2400 }))
      case "speech.say":
        return void sendJson(res, 200, reply({ route: "speaker" }))
      case "speech.interrupt":
        return void sendJson(res, 200, reply({}))
      case "speech.voiceprintEnroll":
        return void sendJson(res, 200, reply({ speaker: (params as { name?: string })?.name ?? "user", samples: 3 }))
      case "speech.voiceprintIdentify":
        return void sendJson(res, 200, reply({ speaker: "user", confidence: 0.87 }))
      case "hardware.audioRoute":
        return void sendJson(res, 200, reply({ route: (params as { target?: string })?.target ?? "auto" }))
      case "hardware.vibrate":
        return void sendJson(res, 200, reply({}))
      case "hardware.sensor":
        return void sendJson(res, 200, reply({ type: (params as { type?: string })?.type ?? "motion", value: 1, timestamp: Date.now() }))
      case "hardware.headset":
        return void sendJson(res, 200, reply({ connected: false, type: "none" }))
      case "skill.list":
        return void sendJson(res, 200, reply({
          skills: [
            { name: "order-milk-tea", description: "在示例商城下单奶茶", appPackage: "com.example.shop", tags: ["购物", "奶茶"] },
            { name: "open-weather", description: "打开天气应用", appPackage: "com.example.weather", tags: ["天气"] },
          ],
        }))
      case "skill.run": {
        const p = params as { name?: string }
        if (p.name === "order-milk-tea") return void sendJson(res, 200, reply({ result: "success", completedSteps: 6 }))
        if (p.name === "open-weather") return void sendJson(res, 200, reply({ result: "success", completedSteps: 2 }))
        if (p.name === "stale-skill") return void sendJson(res, 200, fail("SKILL_STALE", "失配在第2步(完成1步)，工具=control.selectOption", "stale"))
        return void sendJson(res, 200, fail("SKILL_NOT_FOUND", `技能不存在: ${p.name}`))
      }
      case "skill.feedback": {
        return void sendJson(res, 200, reply({}))
      }
      case "skill.learn": {
        const p = params as { goal?: string; appPackage?: string }
        const slug = `skill-${(p.appPackage ?? "x")}-${(p.goal ?? "g").slice(0, 8)}`
        return void sendJson(res, 200, reply({ slug }))
      }
      case "hitl.confirm":
        return void sendJson(res, 200, reply({ approved: (params as { prompt?: string })?.prompt?.includes("敏感") ? false : true }))
      case "hitl.ask":
        return void sendJson(res, 200, reply({ answer: "要大杯，少糖" }))
      case "hitl.handoff":
        return void sendJson(res, 200, reply({ taken: true }))
      case "task.finish":
        return void sendJson(res, 200, reply({ ok: true }))
      case "apps":
        return void sendJson(res, 200, reply({ apps }))
      default:
        return void sendJson(res, 200, fail("UNKNOWN_METHOD", `未知方法: ${method}`))
    }
  }

  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`)
    if (req.method === "GET" && url.pathname === "/health") {
      return void sendJson(res, 200, { ok: true, bootId, protocolVersion: 2, uptimeMs: 1234, services: { a11y: true, speech: true } })
    }
    if (req.method === "GET" && url.pathname === "/events") {
      const since = Number.parseInt(url.searchParams.get("since") ?? "0", 10)
      return void sendJson(res, 200, events.filter((e) => e.seq > since))
    }
    if (req.method === "POST" && url.pathname === "/rpc") {
      const raw = await readBody(req)
      return void handleRpc(req, res, raw)
    }
    if (req.method === "GET" && url.pathname === "/apps") {
      return void sendJson(res, 200, { apps })
    }
    sendJson(res, 404, { id: null, ok: false, error: { code: "NOT_FOUND", message: url.pathname } })
  })

  return new Promise((resolve) => {
    server.listen(options.port, "127.0.0.1", () => {
      const port = (server.address() as { port: number }).port
      resolve({ port, close: () => new Promise((r) => server.close(() => r())) })
    })
  })
}