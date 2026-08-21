# C-07/C-13 Offline and Control-State UX Plan

**Goal:** Stop falsely acknowledging commands while Brain is offline, and close the visible PAUSING/STOPPING state transitions without racing the still-open C-06 resume implementation.

**Design:** Use the existing `UiStateController` as the user-visible state authority and the only publisher for UI text/resume commands. `submitTextInput()` checks the authoritative state before EventBus publication: offline input emits no event, remains `OFFLINE`, and shows `未发送·大脑离线` in overlay/notification. `requestResume()` emits no event while C-06 is incomplete; `PAUSING` stays untouched and settled `PAUSED` honestly reports that automatic resume is unavailable. This prevents Brain from seeing commands the UI says were rejected. Both stop aliases (`aborted`, `stopped`) map to `STOPPED`.

## Task 1: Add behaviorally failing UI tests

- [x] Add OFFLINE text-input rejection, PAUSING early-resume, and `stopped` result tests.
- [x] Add green characterization tests for RUNNING → PAUSING → PAUSED and stop-request → STOPPING → STOPPED.
- [x] Run focused `UiStateControllerTest`; initial RED was 16 tests/3 failures, self-review RED was 17 tests/2 failures.

## Task 2: Implement the minimal state corrections

- [x] Route `CommandInputActivity` through `UiStateController.submitTextInput`; reject before EventBus publication while OFFLINE.
- [x] Route the Continue button through `UiStateController.requestResume`; emit no `resume_request` until C-06 implements a real resume turn.
- [x] Keep consumer-side OFFLINE/PAUSING checks as defense in depth, but do not claim they stop publication.
- [x] Map `finish(resultKind="stopped")` to `STOPPED` alongside `aborted`.
- [x] Run the focused UI suite green (20/20, including event-count assertions).

## Task 3: Verify, review, and document

- [x] Run all core UI tests and full body build (`:common:test :core:testDebugUnitTest :app:assembleDebug --rerun-tasks`, BUILD SUCCESSFUL).
- [x] Cross-review the user-visible semantics and confirm no event is described as accepted when it will be discarded (Approve).
- [x] Update C-07/C-13 status in docs/16 while keeping C-06 open.
- [ ] Commit and push with the required Codex co-author trailer.
