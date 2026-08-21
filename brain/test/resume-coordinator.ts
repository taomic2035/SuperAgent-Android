import assert from "node:assert/strict"
import { test } from "node:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import {
  beginRun,
  finishRun,
  getRun,
  resetRun,
  resumeRun,
  type RunState,
} from "../src/runState.ts"
import * as runState from "../src/runState.ts"
import { allowPromptStart, ResumeCoordinator } from "../src/resumeCoordinator.ts"
import type { BodyClient } from "../src/ipc/client.ts"
import {
  initBrainEvents,
  reportFinish,
  reportPromptStart,
  reportStoppedAfterPause,
} from "../src/ipc/brainEventReporter.ts"
import * as resumeCoordinatorModule from "../src/resumeCoordinator.ts"

async function withRunState(testBody: () => void | Promise<void>): Promise<void> {
  const dir = await mkdtemp(join(tmpdir(), "super-agent-resume-"))
  process.env.SUPER_AGENT_STATE_DIR = dir
  try {
    resetRun()
    await testBody()
  } finally {
    resetRun()
    delete process.env.SUPER_AGENT_STATE_DIR
    await rm(dir, { recursive: true, force: true })
  }
}

function savedRun(outcome: RunState["outcome"] = "paused"): RunState {
  return { goal: "paused task", trace: [], startedAt: 1, outcome }
}

function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve!: () => void
  const promise = new Promise<void>((done) => { resolve = done })
  return { promise, resolve }
}

function manualQueue(): {
  enqueue: (run: () => Promise<void>) => Promise<void>
  runNext: () => Promise<void>
} {
  const jobs: Array<{ run: () => Promise<void>; resolve: () => void; reject: (error: unknown) => void }> = []
  return {
    enqueue: (run) => new Promise<void>((resolve, reject) => { jobs.push({ run, resolve, reject }) }),
    runNext: async () => {
      const job = jobs.shift()
      assert.ok(job, "expected one queued resume job")
      try {
        await job.run()
        job.resolve()
      } catch (error) {
        job.reject(error)
      }
    },
  }
}

test("automatic claim rejects active, closed, and success snapshots", async () => {
  await withRunState(() => {
    beginRun("active task")
    assert.equal(resumeRun(), null, "active run must not be claimed as a settled resume")
  })

  for (const terminal of ["closed", "success"] as const) {
    await withRunState(() => {
      beginRun(`${terminal} task`)
      finishRun(terminal)
      assert.equal(resumeRun(), null, `${terminal} run must not be resumable`)
    })
  }
})

test("paused lifecycle is claimed exactly once", async () => {
  await withRunState(() => {
    beginRun("paused task")
    finishRun("paused", "用户暂停")

    const claimed = resumeRun()

    assert.equal(claimed?.goal, "paused task")
    assert.equal(claimed?.outcome, undefined, "successful claim starts a fresh lifecycle")
    assert.equal(resumeRun(), null, "the same paused lifecycle can only be claimed once")
  })
})

test("manual crash recovery remains available", async () => {
  await withRunState(() => {
    beginRun("crashed task")
    finishRun("crashed", "network")

    const claimed = resumeRun("manual")

    assert.equal(claimed?.goal, "crashed task")
    assert.equal(getRun().failureReason, undefined)
  })
})

test("early resume waits in the task queue before clearing pause", async () => {
  const queue = manualQueue()
  let clearPauseCount = 0
  let promptCount = 0
  const coordinator = new ResumeCoordinator({
    enqueue: queue.enqueue,
    claim: () => savedRun(),
    isStopRequested: () => false,
    clearPause: () => { clearPauseCount++ },
    prepare: () => "resume prompt",
    prompt: async () => { promptCount++ },
  })

  const requested = coordinator.request("automatic")

  assert.equal(clearPauseCount, 0, "PAUSING must settle before pause is cleared")
  assert.equal(promptCount, 0)
  await queue.runNext()
  assert.equal(await requested, "started")
  assert.equal(clearPauseCount, 1)
  assert.equal(promptCount, 1)
})

test("duplicate resume requests start exactly one prompt", async () => {
  let promptCount = 0
  const coordinator = new ResumeCoordinator({
    enqueue: async (run) => run(),
    claim: () => savedRun(),
    isStopRequested: () => false,
    clearPause: () => undefined,
    prepare: () => "resume prompt",
    prompt: async () => { promptCount++ },
  })

  const first = coordinator.request("automatic")
  const second = coordinator.request("automatic")

  assert.deepEqual(await Promise.all([first, second]), ["started", "duplicate"])
  assert.equal(promptCount, 1)
})

test("stop queued before resume claim wins without claiming", async () => {
  const queue = manualQueue()
  let stopped = false
  let claimCount = 0
  let promptCount = 0
  const coordinator = new ResumeCoordinator({
    enqueue: queue.enqueue,
    claim: () => { claimCount++; return savedRun() },
    isStopRequested: () => stopped,
    clearPause: () => undefined,
    prepare: () => "resume prompt",
    prompt: async () => { promptCount++ },
  })

  const requested = coordinator.request("automatic")
  stopped = true
  await queue.runNext()

  assert.equal(await requested, "stopped")
  assert.equal(claimCount, 0)
  assert.equal(promptCount, 0)
})

test("stop during async resume preparation wins before prompt", async () => {
  const preparing = deferred()
  let stopped = false
  let promptCount = 0
  const coordinator = new ResumeCoordinator({
    enqueue: async (run) => run(),
    claim: () => savedRun(),
    isStopRequested: () => stopped,
    clearPause: () => undefined,
    prepare: async () => { await preparing.promise; return "resume prompt" },
    prompt: async () => { promptCount++ },
  })

  const requested = coordinator.request("automatic")
  stopped = true
  preparing.resolve()

  assert.equal(await requested, "stopped")
  assert.equal(promptCount, 0)
})

test("resume prompt preserves stop and refuses to start after stop", () => {
  let stopped = true
  let clearStopCount = 0
  const clearStop = () => { clearStopCount++; stopped = false }

  const allowed = allowPromptStart(true, () => stopped, clearStop)

  assert.equal(allowed, false)
  assert.equal(clearStopCount, 0, "resume prompt must never clear a later stop")
})

test("paused lifecycle emits exactly one stopped terminal on direct stop", async () => {
  const events: Array<{ taskId: string; seq: number; state: string; resultKind?: string }> = []
  const body = {
    rpc: async (_method: string, event: { taskId: string; seq: number; state: string; resultKind?: string }) => {
      events.push(event)
      return undefined
    },
  } as unknown as BodyClient
  initBrainEvents(body)
  await reportPromptStart("paused task")
  await reportFinish("paused", "已暂停")

  assert.equal(await reportStoppedAfterPause("用户已停止"), true)
  assert.equal(await reportStoppedAfterPause("用户已停止"), false)

  const finishes = events.filter((event) => event.state === "finish")
  assert.deepEqual(finishes.map((event) => event.resultKind), ["paused", "stopped"])
  assert.equal(finishes[0].taskId, finishes[1].taskId)
  assert.ok(finishes[1].seq > finishes[0].seq)
})

test("unavailable terminal hook completes before the queued request is released", async () => {
  const terminal = deferred()
  const order: string[] = []
  const coordinator = new ResumeCoordinator({
    enqueue: async (run) => { await run(); order.push("job released") },
    claim: () => null,
    isStopRequested: () => false,
    clearPause: () => undefined,
    prepare: () => "unused",
    prompt: async () => undefined,
    onUnavailable: async () => {
      order.push("terminal started")
      await terminal.promise
      order.push("terminal finished")
    },
  })

  const requested = coordinator.request("automatic")
  await Promise.resolve()
  assert.deepEqual(order, ["terminal started"])
  terminal.resolve()

  assert.equal(await requested, "unavailable")
  assert.deepEqual(order, ["terminal started", "terminal finished", "job released"])
})

test("every stopped branch awaits cancellation and terminal hooks", async () => {
  for (const phase of ["before-claim", "during-prepare"] as const) {
    const cancellation = deferred()
    const cancellationEntered = deferred()
    const terminal = deferred()
    const preparing = deferred()
    const order: string[] = []
    let stopped = phase === "before-claim"
    const dependencies = {
      enqueue: async (run: () => Promise<void>) => { await run(); order.push("job released") },
      claim: () => savedRun(),
      isStopRequested: () => stopped,
      clearPause: () => undefined,
      prepare: async () => {
        if (phase === "during-prepare") {
          stopped = true
          preparing.resolve()
        }
        await preparing.promise
        return "unused"
      },
      prompt: async () => assert.fail("stopped resume must not prompt"),
      cancel: async () => {
        order.push("cancel started")
        cancellationEntered.resolve()
        await cancellation.promise
        order.push("cancel finished")
      },
      onStopped: async () => {
        order.push("terminal started")
        await terminal.promise
        order.push("terminal finished")
      },
    }
    const coordinator = new ResumeCoordinator(dependencies as ConstructorParameters<typeof ResumeCoordinator>[0])

    const requested = coordinator.request("automatic")
    await cancellationEntered.promise
    assert.deepEqual(order, ["cancel started"], `${phase} must enter cancellation before release`)
    cancellation.resolve()
    await Promise.resolve()
    await Promise.resolve()
    assert.deepEqual(order, ["cancel started", "cancel finished", "terminal started"])
    terminal.resolve()

    assert.equal(await requested, "stopped")
    assert.equal(order.at(-1), "job released")
  }
})

test("stopping a persisted paused or claimed checkpoint makes it permanently non-resumable", async () => {
  const stopPersistedRun = (runState as unknown as {
    stopPersistedRun?: (reason?: string) => boolean
  }).stopPersistedRun
  assert.equal(typeof stopPersistedRun, "function", "runState must expose an atomic stop checkpoint API")

  for (const claimFirst of [false, true]) {
    await withRunState(() => {
      let archives = 0
      runState.setArchiveSink(() => { archives++ })
      beginRun(claimFirst ? "claimed task" : "paused task")
      finishRun("paused", "用户暂停")
      archives = 0
      if (claimFirst) assert.ok(resumeRun(), "setup must claim the paused lifecycle")

      assert.equal(stopPersistedRun?.("用户停止"), true)
      assert.equal(runState.peekRun(), null)
      assert.equal(resumeRun("automatic"), null)
      assert.equal(resumeRun("manual"), null)
      assert.equal(archives, 1)
      assert.equal(stopPersistedRun?.("重复停止"), false)
      assert.equal(archives, 1, "stopped checkpoint must archive exactly once")
    })
  }
  runState.setArchiveSink(() => undefined)
})

test("legacy failed user-stop is converted to a non-resumable stopped checkpoint", async () => {
  await withRunState(() => {
    let archives = 0
    runState.setArchiveSink(() => { archives++ })
    beginRun("active task")
    finishRun("failed", "用户停止")
    assert.equal(archives, 1)

    const stopPersistedRun = (runState as unknown as {
      stopPersistedRun: (reason?: string) => boolean
    }).stopPersistedRun
    assert.equal(stopPersistedRun("用户停止"), true)
    assert.equal(runState.peekRun(), null)
    assert.equal(resumeRun("manual"), null, "a user-stopped failure must not revive manually")
    assert.equal(archives, 2, "legacy failed terminal plus its one stopped conversion are each archived once")
    assert.equal(stopPersistedRun("用户停止"), false)
    assert.equal(archives, 2, "repeated stopped settlement must not archive again")
  })
  runState.setArchiveSink(() => undefined)
})

test("active prompt writes stopped directly so queued settlement is idempotent", async () => {
  await withRunState(() => {
    let archives = 0
    runState.setArchiveSink(() => { archives++ })
    beginRun("active stop")
    finishRun("stopped", "用户停止")
    assert.equal(runState.peekRun(), null)
    assert.equal(runState.stopPersistedRun("用户停止"), false)
    assert.equal(archives, 1)
  })
  runState.setArchiveSink(() => undefined)
})

test("settled PAUSED stop waits for finish reporting and skips duplicate finish", async () => {
  const finishStoppedCheckpoint = (resumeCoordinatorModule as unknown as {
    finishStoppedCheckpoint?: (
      stopCheckpoint: () => boolean,
      reportFinish: () => Promise<void>,
    ) => Promise<boolean>
  }).finishStoppedCheckpoint
  assert.equal(typeof finishStoppedCheckpoint, "function", "event stop needs a shared awaited settlement helper")

  await withRunState(async () => {
    beginRun("paused direct stop")
    finishRun("paused", "用户暂停")
    const reported = deferred()
    const order: string[] = []
    const first = finishStoppedCheckpoint?.(
      () => { order.push("checkpoint"); return runState.stopPersistedRun("用户停止") },
      async () => { order.push("finish started"); await reported.promise; order.push("finish done") },
    )
    await Promise.resolve()
    assert.equal(runState.peekRun(), null, "PAUSED stop must become non-resumable before finish reporting")
    assert.deepEqual(order, ["checkpoint", "finish started"])
    reported.resolve()
    assert.equal(await first, true)
    assert.deepEqual(order, ["checkpoint", "finish started", "finish done"])

    let duplicateReports = 0
    assert.equal(await finishStoppedCheckpoint?.(
      () => runState.stopPersistedRun("用户停止"),
      async () => { duplicateReports++ },
    ), false)
    assert.equal(duplicateReports, 0)
  })
})
