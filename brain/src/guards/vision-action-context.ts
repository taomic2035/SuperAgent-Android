import type { ScreenResult } from "../ipc/types.ts"

interface ObservedVisionAction {
  token: string
  runKey: number
}

/** Keeps the opaque body-issued vision capability outside model-visible state. */
export class VisionActionProvenance {
  private observed?: ObservedVisionAction

  observe(screen: ScreenResult, runKey: number): void {
    const token = screen.visionActionToken
    if (screen.kind === "vision" && typeof token === "string" && token.trim().length > 0) {
      this.observed = { token, runKey }
      return
    }
    this.clear()
  }

  attach<T extends Record<string, unknown>>(params: T, runKey: number): T & { visionActionToken?: string } {
    if (!this.observed || this.observed.runKey !== runKey) {
      this.clear()
      return { ...params }
    }
    return { ...params, visionActionToken: this.observed.token }
  }

  clear(): void {
    this.observed = undefined
  }
}
