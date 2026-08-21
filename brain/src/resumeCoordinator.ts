import type { ResumeClaimMode, RunState } from "./runState.ts"

export type ResumeResult = "started" | "unavailable" | "stopped" | "duplicate"

export interface ResumeCoordinatorDependencies {
  enqueue: (run: () => Promise<void>) => Promise<void>
  claim: (mode: ResumeClaimMode) => RunState | null
  isStopRequested: () => boolean
  clearPause: () => void
  prepare: (saved: RunState) => string | Promise<string>
  prompt: (input: string) => Promise<void>
  /** Atomically closes the persisted checkpoint before stopped feedback. */
  cancel?: (saved: RunState | null) => void | Promise<void>
  onUnavailable?: () => void | Promise<void>
  onStopped?: () => void | Promise<void>
}

/** Extracted legacy prompt-start policy; race tests tighten it before main uses it. */
export function allowPromptStart(
  resume: boolean,
  isStopRequested: () => boolean,
  clearStop: () => void,
): boolean {
  if (resume) return !isStopRequested()
  clearStop()
  return true
}

/** Settles a stop that has no active prompt producer (notably PAUSED). */
export async function finishStoppedCheckpoint(
  stopCheckpoint: () => boolean,
  reportFinish: () => Promise<void>,
): Promise<boolean> {
  if (!stopCheckpoint()) return false
  await reportFinish()
  return true
}

export class ResumeCoordinator {
  private pending = false

  constructor(private readonly dependencies: ResumeCoordinatorDependencies) {}

  request(mode: ResumeClaimMode): Promise<ResumeResult> {
    if (this.pending) return Promise.resolve("duplicate")
    this.pending = true
    let result: ResumeResult = "unavailable"
    return this.dependencies.enqueue(async () => {
      if (this.dependencies.isStopRequested()) {
        result = "stopped"
        await this.dependencies.cancel?.(null)
        await this.dependencies.onStopped?.()
        return
      }
      const saved = this.dependencies.claim(mode)
      if (!saved) {
        await this.dependencies.onUnavailable?.()
        return
      }
      const input = await this.dependencies.prepare(saved)
      if (this.dependencies.isStopRequested()) {
        result = "stopped"
        await this.dependencies.cancel?.(saved)
        await this.dependencies.onStopped?.()
        return
      }
      this.dependencies.clearPause()
      // Stop may arrive synchronously with pause clearing. Check again at the
      // last coordinator boundary before the resume prompt is delegated.
      if (this.dependencies.isStopRequested()) {
        result = "stopped"
        await this.dependencies.cancel?.(saved)
        await this.dependencies.onStopped?.()
        return
      }
      await this.dependencies.prompt(input)
      result = "started"
    }).then(() => result).finally(() => { this.pending = false })
  }
}
