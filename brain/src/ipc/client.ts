import type { BodyEvent, HealthStatus, RpcRequest, RpcResponse } from "./types.ts"

let nextId = 1

export class BodyUnavailableError extends Error {
  constructor(message: string) {
    super(message)
    this.name = "BodyUnavailableError"
  }
}

export class BodyRpcError extends Error {
  readonly code: string
  readonly reason?: string
  constructor(code: string, message: string, reason?: string) {
    super(message)
    this.name = "BodyRpcError"
    this.code = code
    this.reason = reason
  }
}

export class BodyClient {
  constructor(
    private readonly baseUrl: string,
    private readonly token: string,
    private readonly timeoutMs = 15_000,
  ) {}

  private async rawRpc(request: RpcRequest): Promise<RpcResponse> {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), this.timeoutMs)
    try {
      const res = await fetch(`${this.baseUrl}/rpc`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${this.token}`,
        },
        body: JSON.stringify(request),
        signal: controller.signal,
      })
      if (!res.ok) {
        try {
          const body = (await res.json()) as RpcResponse
          if (!body.ok && body.error?.code) {
            throw new BodyRpcError(body.error.code, body.error.message, body.error.reason)
          }
        } catch (err) {
          if (err instanceof BodyRpcError) throw err
        }
        throw new BodyUnavailableError(`body http ${res.status}`)
      }
      return (await res.json()) as RpcResponse
    } catch (err) {
      if (err instanceof BodyUnavailableError || err instanceof BodyRpcError) throw err
      throw new BodyUnavailableError(`body unreachable: ${err instanceof Error ? err.message : String(err)}`)
    } finally {
      clearTimeout(timer)
    }
  }

  async rpc<T>(method: string, params: unknown, idempotencyKey?: string): Promise<T> {
    const response = await this.rawRpc({ id: nextId++, method, params, idempotencyKey })
    if (!response.ok) {
      throw new BodyRpcError(response.error.code, response.error.message, response.error.reason)
    }
    return response.result as T
  }

  async health(): Promise<HealthStatus> {
    try {
      const res = await fetch(`${this.baseUrl}/health`, {
        headers: { Authorization: `Bearer ${this.token}` },
        signal: AbortSignal.timeout(3_000),
      })
      if (!res.ok) return { ok: false, bootId: "", protocolVersion: 0, uptimeMs: 0, services: {} }
      const raw = (await res.json()) as HealthStatus & { result?: HealthStatus }
      const h = raw.result ?? raw
      return h
    } catch {
      return { ok: false, bootId: "", protocolVersion: 0, uptimeMs: 0, services: {} }
    }
  }

  async events(since: number): Promise<BodyEvent[]> {
    try {
      const res = await fetch(`${this.baseUrl}/events?since=${since}`, {
        headers: { Authorization: `Bearer ${this.token}` },
        signal: AbortSignal.timeout(5_000),
      })
      if (!res.ok) return []
      const raw = (await res.json()) as { result?: BodyEvent[]; ok: boolean }
      return Array.isArray(raw) ? raw : raw.result ?? []
    } catch {
      return []
    }
  }

  async waitForBody(attempts = 60, intervalMs = 2000): Promise<void> {
    for (let i = 0; i < attempts; i++) {
      const h = await this.health()
      if (h.ok) return
      await new Promise((r) => setTimeout(r, intervalMs))
    }
    throw new BodyUnavailableError("body 服务未就绪（检查 Android 侧服务是否启动）")
  }
}