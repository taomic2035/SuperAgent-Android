# C-02 Sensitive Vision Preflight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to execute this plan with review checkpoints.

**Goal:** Prevent every visual-perception request from capturing or exporting a screenshot until a fresh accessibility scan has synchronized the foreground package into the sensitive-session tracker.

**Architecture:** Keep privacy enforcement local to Body and independent from any vision provider or model. `ScreenPerceiver` gains an explicit cache-bypass option for security preflight. A small pure `PerceptionRoute` policy maps the requested mode, fresh accessibility result, structured WebView signal, and synchronized sensitive state to `UseA11y`, `UseVision`, or `VisionBlocked`. `BodyCore` owns the irreversible ordering. Auto routing starts from a fresh scan; every actual capture performs a second fresh scan after overlay settling, then synchronizes foreground and checks the gate immediately before capture. An unknown foreground fails closed. Provider/model configuration is downstream and cannot weaken this gate; `qwen3.7-plus` may appear only as a deployment example.

**Security invariant:** `capture()` is unreachable until a fresh foreground scan immediately preceding it has completed, identified a known package, and synchronized the session as non-sensitive. A cached pre-switch package is not sufficient evidence, and the overlay delay must not create a switch-to-sensitive TOCTOU window.

## Task 1: Characterize routing with a behaviorally failing test

**Files:**
- Create: `body/core/src/main/kotlin/com/superagent/body/core/perception/PerceptionRoute.kt`
- Create: `body/core/src/test/kotlin/com/superagent/body/core/perception/PerceptionRouteTest.kt`

- [x] Extract the current auto routing into a pure policy without changing behavior; initially preserve the explicit-vision bypass.
- [x] Add tests for explicit vision in a synchronized sensitive session, auto sensitive, healthy auto accessibility, and degraded/WebView auto fallback.
- [x] Run the focused test and record a behavioral RED: explicit vision incorrectly returns `UseVision` instead of `VisionBlocked` (5 tests, exactly 1 failure).

## Task 2: Add a cache-bypassing security scan

**Files:**
- Modify: `body/core/src/main/kotlin/com/superagent/body/core/perception/ScreenPerceiver.kt`
- Modify: `body/core/src/test/kotlin/com/superagent/body/core/perception/PerceptionRouteTest.kt`

- [x] Add `forceRefresh: Boolean = false` to `perceive`; skip cache reads when true while retaining normal cache writes.
- [x] Keep existing callers source-compatible and document that only security/foreground preflight should bypass the cache.
- [x] Cover the cache decision through an extracted internal predicate if Android node construction makes a direct JVM test impractical.

## Task 3: Enforce scan-sync-decide-capture ordering

**Files:**
- Modify: `body/core/src/main/kotlin/com/superagent/body/core/BodyCore.kt`
- Modify: `body/core/src/main/kotlin/com/superagent/body/core/perception/PerceptionRoute.kt`
- Modify: `body/core/src/test/kotlin/com/superagent/body/core/perception/PerceptionRouteTest.kt`

- [x] For `vision` and `auto`, perform `perceive("a11y", ..., forceRefresh = true)` before checking sensitive state.
- [x] Synchronize `SensitiveSessionTracker` with the fresh `appPackage`, then route using the synchronized state and `lastScanHasWebView`; unknown package fails closed for visual capture.
- [x] Return the same fresh a11y result for `UseA11y`; return typed `VISION_BLOCKED` for `VisionBlocked`; enter overlay/capture only for `UseVision`.
- [x] After overlay settling and immediately before capture, perform a second forced foreground refresh to close the switch-during-delay TOCTOU window. Centralize all screenshot calls behind this capture gate.
- [x] Do not couple routing to a provider or model name.
- [x] Confirm sensitive and unknown-foreground tests assert zero capture/export side effects through the narrowest practical seam, including null/blank packages and a prior-sensitive tracker.

## Task 4: Verify, cross-review, document, and ship

- [x] Run focused perception tests (15/15).
- [x] Run `:core:testDebugUnitTest --tests "com.superagent.body.core.perception.*" --tests "com.superagent.body.core.security.*"` (26/26).
- [x] Run `:common:test :core:testDebugUnitTest :app:assembleDebug --rerun-tasks` (`BUILD SUCCESSFUL`).
- [x] Request cross-review of the shared perception architecture and GPT safety invariant; final result: Approve.
- [x] Mark C-02 fixed in `docs/16-当前架构代码审计-2026-08-21.md` after review and verification.
- [ ] Commit with the required `Co-Authored-By: Codex GPT-5.6 Sol <noreply@openai.com>` trailer and push after checking remote state.
