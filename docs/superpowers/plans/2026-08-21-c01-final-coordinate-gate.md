# C-01 Final Coordinate Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure both `control.selectOption` and `control.selectSpec` validate the selected mark's final coordinates with `ActionGate` before any tap, including overlapping parent/child nodes.

**Architecture:** Keep `ActionExecutor` as the public action authority while making `OptionSelector` a module-internal, testable coordinate boundary. Its production construction requires `SensitiveSessionTracker` and adapts perception, typed gate, and tap into narrow functions. `OptionSelector` returns either `Completed(ActionResult)` or the original typed `GateBlocked(ActionGate.Violation)`; `ActionExecutor` maps the latter to its own `GateBlocked`, preserving commit semantics and sensitive-session nonce through BodyCore. Both selector branches share one gate-before-tap path; label checks remain an early rejection only. The `ActionExecutor` constructor is internal so its internal selector dependency is not exposed as public API.

**Tech Stack:** Kotlin 2.x, Android library module, kotlinx.coroutines, JUnit 5, Gradle.

---

### Task 1: Claim the shared safety task

**Files:**
- Create: `docs/handoff/claims/S0-C01-final-coordinate-gate-GPT.md` (Git ignored)

- [ ] **Step 1: Record the claim**

Create the claim with owner GPT, reviewer GLM, priority P0, base SHA, exact files, and the acceptance commands from Task 4.

- [ ] **Step 2: Verify there is no conflicting claim**

Run:

```powershell
Get-ChildItem docs/handoff/claims -File | Select-Object Name
```

Expected: no other active claim names C-01 or `OptionSelector.kt`.

### Task 2: Add a regression-test seam and failing tests

**Files:**
- Modify: `body/core/src/main/kotlin/com/superagent/body/core/control/OptionSelector.kt`
- Create: `body/core/src/test/kotlin/com/superagent/body/core/control/OptionSelectorCoordinateGateTest.kt`

- [ ] **Step 1: Introduce narrow internal adapters without changing behavior**

Refactor `OptionSelector` to a private primary constructor:

```kotlin
class OptionSelector private constructor(
    private val perceive: (String) -> ScreenResult,
    private val coordinateGate: (Int, Int) -> ActionGate.Violation?,
    private val tap: suspend (Int, Int) -> ActionResult,
) {
    constructor(
        perceiver: ScreenPerceiver,
        controller: Controller,
        sensitive: SensitiveSessionTracker,
    ) : this(
        perceive = perceiver::perceive,
        coordinateGate = { x, y ->
            ActionGate.violatingLabel(perceiver, sensitive, x, y)
        },
        tap = controller::tap,
    )

    internal constructor(
        perceive: (String) -> ScreenResult,
        coordinateGate: (Int, Int) -> ActionGate.Violation?,
        tap: suspend (Int, Int) -> ActionResult,
        testSeam: Unit = Unit,
    ) : this(perceive, coordinateGate, tap)
}
```

Import `ScreenResult`, `ActionGate`, and `SensitiveSessionTracker`. The unused `testSeam` only disambiguates the JVM constructor; suppress its unused warning locally if required.

- [ ] **Step 2: Write tests that describe the security invariant**

Create `OptionSelectorCoordinateGateTest.kt` with a fixed mark at `(50,50)`, a tap counter, and these tests using `runBlocking`:

```kotlin
@Test
fun `selectOption final coordinate violation blocks before tap`() = runBlocking {
    var taps = 0
    val selector = selector(
        violation = ActionGate.Violation.Commit("提交订单"),
        onTap = { taps++ },
    )

    val result = selector.select("继续", verifySelected = false)
    val blocked = assertInstanceOf(OptionSelector.SelectionResult.GateBlocked::class.java, result)

    assertEquals("提交订单", blocked.violation.label)
    assertEquals(0, taps)
}

@Test
fun `selectSpec final coordinate violation blocks before tap`() = runBlocking {
    var taps = 0
    val selector = selector(
        violation = ActionGate.Violation.Commit("提交订单"),
        onTap = { taps++ },
    )

    val result = selector.select("继续", verifySelected = true)

    assertInstanceOf(OptionSelector.SelectionResult.GateBlocked::class.java, result)
    assertEquals(0, taps)
}

@Test
fun `allowed final coordinate taps exactly once`() = runBlocking {
    var taps = 0
    val selector = selector(violation = null, onTap = { taps++ })

    val result = selector.select("继续", verifySelected = false)
    val completed = assertInstanceOf(OptionSelector.SelectionResult.Completed::class.java, result)

    assertTrue(completed.actionResult.located)
    assertEquals(1, taps)
}
```

The helper must return `ScreenResult(signature="before", kind="a11y", blank=false, marks=listOf(Mark(1,"继续",Point(50,50))))`; the tap lambda returns `ActionResult(true)`. Record the coordinates passed to `coordinateGate` and assert `(50,50)`. Add a sensitive-session case using `ActionGate.Violation.SensitiveSession("发送", "nonce-1")` and assert the exact violation, including `nonce`, survives selector and ActionExecutor mapping.

- [ ] **Step 3: Run the focused test and confirm the regression fails**

Run:

```powershell
cd body
.\gradlew.bat :core:testDebugUnitTest --tests "*OptionSelectorCoordinateGateTest*"
```

Expected before the production fix: the `selectOption` test fails behaviorally because the tap counter becomes 1 or because the result is `Completed` instead of typed `GateBlocked`; compilation errors do not satisfy RED.

### Task 3: Enforce gate-before-tap for every selector branch

**Files:**
- Modify: `body/core/src/main/kotlin/com/superagent/body/core/control/OptionSelector.kt`

- [ ] **Step 1: Move the coordinate gate ahead of branch selection**

Immediately after choosing `target`, add the single shared typed gate:

```kotlin
coordinateGate(target.center.x, target.center.y)?.let { violation ->
    return SelectionResult.GateBlocked(violation)
}
```

Define `SelectionResult.Completed(ActionResult)` and `SelectionResult.GateBlocked(ActionGate.Violation)`. Return `Completed` for normal success/failure paths. Remove the existing nullable gate nested inside `verifySelected`, replace both `controller.tap(...)` calls with `tap(...)`, and make the production tracker mandatory.

- [ ] **Step 2: Preserve the typed violation through ActionExecutor**

In both Select branches, map `SelectionResult.GateBlocked` directly to `ActionExecutor.Result.GateBlocked` and unwrap `Completed.actionResult`. Never parse a `GATE_BLOCKED:` note string. This preserves `SensitiveSession.nonce` for `BodyCore.gateFailure`.

- [ ] **Step 3: Run the focused regression test**

Run the Task 2 focused command.

Expected: all three tests pass; both blocked cases report zero taps and the allowed case reports one.

- [ ] **Step 4: Run all control/security tests**

Run:

```powershell
cd body
.\gradlew.bat :core:testDebugUnitTest --tests "com.superagent.body.core.control.*" --tests "com.superagent.body.core.security.*"
```

Expected: all selected tests pass.

### Task 4: Verify, review, and close C-01

**Files:**
- Modify: `docs/16-当前架构代码审计-2026-08-21.md`
- Modify: `docs/handoff/claims/S0-C01-final-coordinate-gate-GPT.md` (Git ignored)

- [ ] **Step 1: Run the full body verification**

```powershell
cd body
.\gradlew.bat :common:test :core:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, zero failing JVM tests, Debug APK assembled.

- [ ] **Step 2: Request GLM cross-review**

Set the claim to `review` and include the diff, focused/full test output, and the invariant: the final selected coordinate is checked regardless of the caller-provided label and regardless of `verifySelected`.

- [ ] **Step 3: Update the audit only after review passes**

Mark C-01 as fixed in the current status while preserving its original finding and evidence. Record the commit SHA and test command; do not close C-02.

- [ ] **Step 4: Commit and push the isolated task**

```powershell
git add body/core/src/main/kotlin/com/superagent/body/core/control/OptionSelector.kt body/core/src/test/kotlin/com/superagent/body/core/control/OptionSelectorCoordinateGateTest.kt docs/16-当前架构代码审计-2026-08-21.md
git commit -m "fix(S0-C01): gate final selector coordinates"
git push origin main
```

Expected: remote `main` resolves to the new local HEAD. The ignored claim is set to `closed` with the pushed SHA.
