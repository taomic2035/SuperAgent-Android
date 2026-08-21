# C-06 Race-Safe Resume Plan

**Goal:** Resume a settled paused run exactly once without allowing early resume or queued work to override a later stop request.

**Architecture:** `runState` provides an atomic in-process claim over the persisted snapshot. A new pure/testable `ResumeCoordinator` serializes event and REPL requests through the existing task queue; it performs stop checks, claims only eligible state after the previous turn settles, then clears pause and starts a new prompt. Resume prompts never clear stop. The UI publishes resume only from settled `PAUSED`, transitions immediately to `THINKING/正在恢复`, and suppresses double taps.

## Task 1: Atomic resume claim

- [x] Add behavior tests showing active/closed/success cannot be auto-claimed and paused is claimed once.
- [x] Replace unconditional `resumeRun()` consumption with a claim API; preserve manual crash recovery without allowing two claims in one process.
- [x] Keep goal/trace, clear old terminal fields only after a successful claim.

## Task 2: Testable coordinator and stop priority

- [x] Add deterministic RED tests for early resume, duplicate resume, queued resume then stop, and stop during async preparation.
- [x] Implement a coordinator with injected queue, claim, stop, pause-clear, and prompt dependencies.
- [x] Remove event-branch pre-clear/pre-check; event and REPL paths call the same coordinator.
- [x] Resume prompt must not call unconditional `clearStop`; recheck stop immediately before `agent.prompt()`.

## Task 3: Re-enable truthful UI resume

- [x] Add RED tests: PAUSING request emits zero; PAUSED request emits one; repeated call emits one total.
- [x] Update `requestResume()` to transition PAUSED → THINKING/正在恢复 before emitting.
- [x] Keep unavailable/no-run outcome honest via Brain/UI terminal feedback.

## Task 4: Verify and close

- [x] Run dedicated race tests, typecheck, smoke, contract, integration, UI tests, and full body build.
- [x] Cross-review stop dominance and exactly-once resume.
- [x] Update docs/16 C-06 only after approval.
- [x] Commit/push with required Codex trailer and verify remote main.
