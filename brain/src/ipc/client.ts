import type { BodyEvent, HealthStatus, RpcRequest, RpcResponse } from "./types.ts"

let nextId = 1

/** 与 body `BodyContext.PROTOCOL_VERSION` 同步（docs/07 契约；CT-05 镜像测试互验）。 */
export const EXPECTED_PROTOCOL_VERSION = 2

export class BodyUnavailableError extends Error {
  constructor(message: string) {
    super(message)
    this.name = "BodyUnavailableError"
  }
}

export class BodyRpcError extends Error {
  readonly code: string
  readonly reason?: string
  /** AD-10：敏感动作被拒时的一次性 nonce（hitl.confirm 回传用） */
  readonly nonce?: string
  constructor(code: string, message: string, reason?: string, nonce?: string) {
    super(message)
    this.name = "BodyRpcError"
    this.code = code
    this.reason = reason
    this.nonce = nonce
  }
}

export class BodyClient {
  constructor(
    private readonly baseUrl: string,
    private readonly token: string,
    /** 默认 35s > body 默认 handler 超时 30s（BodyServer.DEFAULT_HANDLER_TIMEOUT_MS）。 */
    private readonly timeoutMs = 35_000,
  ) {}

  private async rawRpc(request: RpcRequest, timeoutMs: number): Promise<RpcResponse> {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
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

  /**
   * @param timeoutMs 客户端超时必须 > body 侧对应 handler 的 RPC 超时，
   * 否则 body 还在等（如 HITL 等用户）brain 已判死。长耗时调用按工具显式传大值。
   */
  async rpc<T>(method: string, params: unknown, idempotencyKey?: string, timeoutMs = this.timeoutMs): Promise<T> {
    const response = await this.rawRpc({ id: nextId++, method, params, idempotencyKey }, timeoutMs)
    if (!response.ok) {
      throw new BodyRpcError(response.error.code, response.error.message, response.error.reason, (response.error as { nonce?: string }).nonce)
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

  /** 取截图 blob（视觉感知 L1），返回原始字节。 */
  async blob(ref: string): Promise<Buffer> {
    const res = await fetch(`${this.baseUrl}/blob/${encodeURIComponent(ref)}`, {
      headers: { Authorization: `Bearer ${this.token}` },
      signal: AbortSignal.timeout(10_000),
    })
    if (!res.ok) throw new BodyUnavailableError(`blob ${res.status}`)
    return Buffer.from(await res.arrayBuffer())
  }

  async waitForBody(attempts = 60, intervalMs = 2000): Promise<void> {
    for (let i = 0; i < attempts; i++) {
      const h = await this.health()
      if (h.ok) {
        if (h.protocolVersion !== EXPECTED_PROTOCOL_VERSION) {
          // PROTOCOL_MISMATCH：fail fast，不重试（重试无意义，需升级对端）
          throw new BodyRpcError(
            "PROTOCOL_MISMATCH",
            `body protocolVersion=${h.protocolVersion}，brain 期望 ${EXPECTED_PROTOCOL_VERSION}（docs/07 契约），请升级 body APK`,
          )
        }
        return
      }
      await new Promise((r) => setTimeout(r, intervalMs))
    }
    throw new BodyUnavailableError("body 服务未就绪（检查 Android 侧服务是否启动）")
  }
}