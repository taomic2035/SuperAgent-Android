# Vision Coordinate Risk Consent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保留 VLM 坐标执行能力，在首次视觉坐标动作前提供“允许所有 / 仅允许本次 / 不允许”三态授权，并保证授权不能绕过既有 ActionGate/HITL。

**Architecture:** Body 为授权与视觉来源的唯一权威：截图时签发短期 context token，动作入口验证 token 后调用可并发合并的 consent gate。Brain 只维护最近感知来源并自动附加 token，不把 token 暴露给模型；App 层负责悬浮三选一 UI、持久授权、拒绝后的服务退出与主动重开恢复。

**Tech Stack:** Kotlin/JVM + Android Service/SharedPreferences + kotlinx.coroutines/JUnit 5；TypeScript + TypeBox；JSON contract mirror；Mermaid 文档。

---

## 0. 文件结构与任务边界

| 文件 | 单一职责 |
|---|---|
| `body/common/.../Protocol.kt`、`contract.json`、`brain/src/ipc/types.ts` | `visionActionToken` 兼容协议真源 |
| `body/core/.../vision/VisionActionContextRegistry.kt` | 签发并验证短期视觉来源 token |
| `brain/src/guards/vision-action-context.ts` | 维护最近感知来源并为动作请求自动附加 token |
| `body/core/.../vision/VisionCoordinateConsentGate.kt` | 三态决策、并发询问合并、session 授权 |
| `body/app/.../VisionConsentPreferences.kt` | 仅持久化 `ALLOW_ALWAYS` 与非敏感审计元数据 |
| `body/core/.../ui/VisionConsentUiBridge.kt` | core 到悬浮 UI 的一次性异步请求桥 |
| `FloatingUiService.kt` | 三按钮授权面板与结果回传 |
| `BodyCore.kt` | token 校验、consent gate、ActionExecutor 的固定顺序接线 |
| `BodyService.kt`、`MainActivity.kt` | 拒绝后退出服务、主动重开恢复、设置撤销 |

公共契约由 GPT 负责；GLM 的 command journal/pump 文件不在本计划范围。每项提交都必须包含：

```text
Co-Authored-By: Codex GPT-5.6 Sol <noreply@openai.com>
```

### Task 1: 扩展兼容协议真源

**Files:**
- Modify: `body/common/src/main/kotlin/com/superagent/common/Protocol.kt`
- Modify: `body/common/src/main/resources/contract.json`
- Modify: `brain/src/ipc/types.ts`
- Modify: `body/common/src/test/kotlin/com/superagent/common/CommandAckCompatibilityTest.kt`
- Modify: `brain/test/contract.ts`

- [ ] **Step 1: 写失败测试：新 token 可选且旧 JSON 兼容**

在 `CommandAckCompatibilityTest.kt` 增加：

```kotlin
@Test
fun `ScreenResult visionActionToken is optional and round trips`() {
    val legacy = json.decodeFromString<ScreenResult>(
        """{"signature":"s","kind":"vision","blank":false}""",
    )
    assertNull(legacy.visionActionToken)

    val encoded = json.encodeToString(
        ScreenResult.serializer(),
        legacy.copy(visionActionToken = "vision-token"),
    )
    assertEquals(
        "vision-token",
        json.decodeFromString<ScreenResult>(encoded).visionActionToken,
    )
}
```

在 `brain/test/contract.ts` 的 `ScreenResult` 断言中要求 `visionActionToken` 存在且为 optional。

- [ ] **Step 2: 运行测试，确认 RED**

Run:

```powershell
cd body
.\gradlew.bat :common:test --tests "com.superagent.common.CommandAckCompatibilityTest.ScreenResult visionActionToken is optional and round trips" --rerun-tasks
cd ..\brain
npm run contract
```

Expected：Kotlin 编译提示 `visionActionToken` 不存在；contract 报 ScreenResult 字段漂移。

- [ ] **Step 3: 最小实现协议字段**

`Protocol.kt`：

```kotlin
val visionFallback: VisionFallback? = null,
/** Body 签发、Brain 工具层保管；不得进入模型可见文本。 */
val visionActionToken: String? = null,
```

`brain/src/ipc/types.ts`：

```ts
visionFallback?: VisionFallback
/** Opaque Body-issued provenance; strip before model-visible serialization. */
visionActionToken?: string
```

`contract.json` 的 `ScreenResult.fields` 与 `optional` 都加入 `visionActionToken`。

- [ ] **Step 4: 运行 common 与镜像测试，确认 GREEN**

Run:

```powershell
cd body
.\gradlew.bat :common:test --rerun-tasks
cd ..\brain
npm run contract
npm run typecheck
```

Expected：全部 exit 0；contract 仍报告 34 个共享类型一致。

- [ ] **Step 5: 提交 Task 1**

```powershell
git add -- body/common/src/main/kotlin/com/superagent/common/Protocol.kt body/common/src/main/resources/contract.json body/common/src/test/kotlin/com/superagent/common/CommandAckCompatibilityTest.kt brain/src/ipc/types.ts brain/test/contract.ts
git commit -m "feat(vision): add optional action provenance token" -m "Co-Authored-By: Codex GPT-5.6 Sol <noreply@openai.com>"
```

### Task 2: Body 签发与验证视觉来源

**Files:**
- Create: `body/core/src/main/kotlin/com/superagent/body/core/vision/VisionActionContextRegistry.kt`
- Create: `body/core/src/main/kotlin/com/superagent/body/core/vision/VisionActionContextBinder.kt`
- Create: `body/core/src/test/kotlin/com/superagent/body/core/vision/VisionActionContextRegistryTest.kt`
- Create: `body/core/src/test/kotlin/com/superagent/body/core/vision/VisionActionContextBinderTest.kt`
- Modify: `body/core/src/main/kotlin/com/superagent/body/core/BodyCore.kt`

- [ ] **Step 1: 写 registry 失败测试**

```kotlin
class VisionActionContextRegistryTest {
    private var now = 1_000L
    private var tokenIndex = 0
    private val registry = VisionActionContextRegistry(
        nowMs = { now },
        tokenFactory = { "token-${++tokenIndex}" },
        ttlMs = 120_000L,
    )

    @Test
    fun `valid token is bound to capture context and is reusable within ttl`() {
        val issued = registry.issue("shot.jpg", "pkg", "sig", 1080, 2400)
        assertEquals(
            VisionActionContextRegistry.Validation.Valid,
            registry.validate(issued, "pkg", "sig"),
        )
        assertEquals(
            VisionActionContextRegistry.Validation.Valid,
            registry.validate(issued, "pkg", "sig"),
        )
    }

    @Test
    fun `forged expired or foreground-mismatched token fails closed`() {
        val issued = registry.issue("shot.jpg", "pkg", "sig", 1080, 2400)
        assertEquals(VisionActionContextRegistry.Validation.Invalid, registry.validate("forged", "pkg", "sig"))
        assertEquals(VisionActionContextRegistry.Validation.Invalid, registry.validate(issued, "other", "sig"))
        now += 120_001L
        assertEquals(VisionActionContextRegistry.Validation.Invalid, registry.validate(issued, "pkg", "sig"))
    }
}
```

- [ ] **Step 2: 运行测试，确认 RED**

Run:

```powershell
cd body
.\gradlew.bat :core:testDebugUnitTest --tests "com.superagent.body.core.vision.VisionActionContextRegistryTest" --rerun-tasks
```

Expected：编译失败，`VisionActionContextRegistry` 尚不存在。

- [ ] **Step 3: 实现内存 registry**

```kotlin
class VisionActionContextRegistry(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val tokenFactory: () -> String = {
        val bytes = ByteArray(24).also(java.security.SecureRandom()::nextBytes)
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    },
    private val ttlMs: Long = 120_000L,
) {
    private data class Entry(
        val screenshotRef: String,
        val appPackage: String,
        val signature: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val expiresAt: Long,
    )

    enum class Validation { Valid, Invalid }
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun issue(ref: String, pkg: String, signature: String, width: Int, height: Int): String {
        val token = tokenFactory()
        entries[token] = Entry(ref, pkg, signature, width, height, nowMs() + ttlMs)
        return token
    }

    fun validate(token: String?, currentPackage: String?, currentSignature: String): Validation {
        val entry = token?.let(entries::get) ?: return Validation.Invalid
        if (nowMs() > entry.expiresAt) {
            entries.remove(token, entry)
            return Validation.Invalid
        }
        val signatureMatches = entry.signature.isBlank() || currentSignature.isBlank() || entry.signature == currentSignature
        return if (currentPackage == entry.appPackage && signatureMatches) Validation.Valid else Validation.Invalid
    }
}
```

新增可独立测试的 binder，并让 `BodyCore` 成功截图分支只调用它：

```kotlin
fun bindVisionActionContext(
    screen: ScreenResult,
    screenshotRef: String,
    screenWidth: Int,
    screenHeight: Int,
    registry: VisionActionContextRegistry,
): ScreenResult {
    val pkg = screen.appPackage ?: return screen.copy(visionActionToken = null)
    val token = registry.issue(screenshotRef, pkg, screen.signature, screenWidth, screenHeight)
    return screen.copy(visionActionToken = token)
}
```

BodyCore 在构造 vision `ScreenResult` 后调用 binder。包名为空时不签发 token，保持视觉只读结果。

- [ ] **Step 4: 增加 BodyCore token 签发回归并确认 GREEN**

在 `VisionActionContextBinderTest.kt` 精确断言：有 appPackage 的成功视觉捕获返回非空 token；缺 appPackage 时 token 为 null。`PerceptionRouteTest.kt` 保持敏感阻断与 a11y fallback 不进入 binder。

Run:

```powershell
.\gradlew.bat :core:testDebugUnitTest --tests "*VisionActionContextRegistryTest" --tests "*VisionActionContextBinderTest" --tests "*PerceptionRouteTest" --rerun-tasks
```

Expected：全部通过，0 failures。

- [ ] **Step 5: 提交 Task 2**

```powershell
git add -- body/core/src/main/kotlin/com/superagent/body/core/vision/VisionActionContextRegistry.kt body/core/src/main/kotlin/com/superagent/body/core/vision/VisionActionContextBinder.kt body/core/src/test/kotlin/com/superagent/body/core/vision/VisionActionContextRegistryTest.kt body/core/src/test/kotlin/com/superagent/body/core/vision/VisionActionContextBinderTest.kt body/core/src/main/kotlin/com/superagent/body/core/BodyCore.kt
git commit -m "feat(vision): issue short-lived action provenance" -m "Co-Authored-By: Codex GPT-5.6 Sol <noreply@openai.com>"
```

### Task 3: Brain 自动传播来源且不泄露 token

**Files:**
- Create: `brain/src/guards/vision-action-context.ts`
- Create: `brain/test/vision-action-context.ts`
- Modify: `brain/src/tools/index.ts`
- Modify: `brain/src/guards/redact.ts`
- Modify: `brain/package.json`

- [ ] **Step 1: 写 provenance RED 测试**

```ts
import assert from "node:assert/strict"
import { VisionActionProvenance } from "../src/guards/vision-action-context.ts"

const source = new VisionActionProvenance()
source.observe({ signature: "v", kind: "vision", blank: false, visionActionToken: "secret" }, 100)
assert.deepEqual(source.attach({ x: 10, y: 20 }, 100), { x: 10, y: 20, visionActionToken: "secret" })
assert.deepEqual(source.attach({ x: 10, y: 20 }, 101), { x: 10, y: 20 })

source.observe({ signature: "a", kind: "a11y", blank: false }, 101)
assert.deepEqual(source.attach({ x: 10, y: 20 }, 101), { x: 10, y: 20 })
```

同一测试再执行 `JSON.stringify(redactScreen(screen))`，断言结果不包含 `secret`。

- [ ] **Step 2: 运行测试，确认 RED**

Run:

```powershell
cd brain
npx tsx test/vision-action-context.ts
```

Expected：模块不存在。

- [ ] **Step 3: 实现 provenance 与模型侧剥离**

```ts
export class VisionActionProvenance {
  private token: string | undefined
  private runKey: number | undefined

  observe(screen: ScreenResult, runKey: number): void {
    this.token = screen.kind === "vision" ? screen.visionActionToken : undefined
    this.runKey = this.token ? runKey : undefined
  }

  attach<T extends Record<string, unknown>>(params: T, runKey: number): T & { visionActionToken?: string } {
    return this.token && this.runKey === runKey ? { ...params, visionActionToken: this.token } : { ...params }
  }

  clear(): void { this.token = undefined; this.runKey = undefined }
}
```

`redactScreen` 返回值必须显式写 `visionActionToken: undefined`。`buildTools()` 内创建一个 provenance；`perceive.screen` 在 `resolveVisionScreen` 完成后调用 `observe(enriched, getRun().startedAt)`；`control.tap/longPress/swipe/selectOption/selectSpec` 使用 `provenance.attach(params, getRun().startedAt)`。run key 不同会自动丢弃旧 token；`task.finish` 执行完成时再显式调用 `clear()`。

- [ ] **Step 4: 接线测试覆盖五个坐标工具**

使用记录请求参数的 fake `BodyClient`，依次执行五个工具，断言视觉感知后每个请求均含 token；成功 a11y 感知后均不含 token。不得把 token 放进 tool `content`。

`package.json` 增加：

```json
"vision-action-context": "tsx test/vision-action-context.ts"
```

Run:

```powershell
npm run vision-action-context
npm run vision-fallback
npm run typecheck
```

Expected：全部 exit 0，日志和 tool content 不出现 token。

- [ ] **Step 5: 提交 Task 3**

```powershell
git add -- brain/src/guards/vision-action-context.ts brain/src/guards/redact.ts brain/src/tools/index.ts brain/test/vision-action-context.ts brain/package.json
git commit -m "feat(vision): propagate opaque action provenance" -m "Co-Authored-By: Codex GPT-5.6 Sol <noreply@openai.com>"
```

### Task 4: 三态 consent gate 与持久存储

**Files:**
- Create: `body/core/src/main/kotlin/com/superagent/body/core/vision/VisionCoordinateConsentGate.kt`
- Create: `body/core/src/test/kotlin/com/superagent/body/core/vision/VisionCoordinateConsentGateTest.kt`
- Create: `body/app/src/main/kotlin/com/superagent/body/VisionConsentPreferences.kt`
- Create: `body/app/src/test/kotlin/com/superagent/body/VisionConsentPreferencesTest.kt`

- [ ] **Step 1: 写 gate RED 测试**

在同一测试类写五个具名用例：`allow always persists`、`allow session remains in memory only`、`decline is not persisted`、`missing presenter returns UiUnavailable`、`concurrent ensure coalesces one prompt`。最后一个用例启动 10 个 `async`，断言 prompt 仅调用一次。

核心测试形状：

```kotlin
val promptCalls = AtomicInteger(0)
val gate = VisionCoordinateConsentGate(
    store = FakeStore(false),
    prompt = {
        promptCalls.incrementAndGet()
        VisionConsentChoice.ALLOW_SESSION
    },
)
coroutineScope { List(10) { async { gate.ensure() } }.awaitAll() }
assertEquals(1, promptCalls.get())
assertEquals(VisionConsentResult.Allowed, gate.ensure())
```

- [ ] **Step 2: 运行测试，确认 RED**

Run:

```powershell
cd body
.\gradlew.bat :core:testDebugUnitTest --tests "*VisionCoordinateConsentGateTest" --rerun-tasks
```

Expected：类型不存在。

- [ ] **Step 3: 实现纯 gate**

```kotlin
enum class VisionConsentChoice { ALLOW_ALWAYS, ALLOW_SESSION, DECLINED }
sealed interface VisionConsentResult {
    data object Allowed : VisionConsentResult
    data object Declined : VisionConsentResult
    data object UiUnavailable : VisionConsentResult
}
interface VisionConsentStore {
    fun isAlwaysAllowed(): Boolean
    fun allowAlways(acceptedAt: Long, appVersion: String)
    fun revoke(revokedAt: Long)
}
class VisionCoordinateConsentGate(
    private val store: VisionConsentStore,
    private val prompt: suspend () -> VisionConsentChoice?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val appVersion: String,
) {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var sessionAllowed = false

    suspend fun ensure(): VisionConsentResult {
        if (store.isAlwaysAllowed() || sessionAllowed) return VisionConsentResult.Allowed
        return mutex.withLock {
            if (store.isAlwaysAllowed() || sessionAllowed) return@withLock VisionConsentResult.Allowed
            when (prompt()) {
                VisionConsentChoice.ALLOW_ALWAYS -> {
                    store.allowAlways(nowMs(), appVersion)
                    VisionConsentResult.Allowed
                }
                VisionConsentChoice.ALLOW_SESSION -> {
                    sessionAllowed = true
                    VisionConsentResult.Allowed
                }
                VisionConsentChoice.DECLINED -> VisionConsentResult.Declined
                null -> VisionConsentResult.UiUnavailable
            }
        }
    }
}
```

- [ ] **Step 4: 实现并测试 SharedPreferences store**

`VisionConsentPreferences` 接受一个可测试的精简键值接口，并提供 Android 构造函数：

```kotlin
interface VisionConsentKeyValue {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun put(values: Map<String, Any>)
    fun remove(key: String)
}

class VisionConsentPreferences(
    private val values: VisionConsentKeyValue,
) : VisionConsentStore {
    constructor(context: Context) : this(SharedPreferencesVisionConsentKeyValue(context))
    // isAlwaysAllowed / allowAlways / revoke implement the four fixed keys below
}
```

只允许键：`allow_always`、`accepted_at`、`app_version`、`revoked_at`。`VisionConsentPreferencesTest` 使用 map fake，断言实际写入键集合与这四项完全相等，并断言不存在 screenshot、coordinate、package 或 model 字段。

Run:

```powershell
.\gradlew.bat :core:testDebugUnitTest --tests "*VisionCoordinateConsentGateTest" :app:testDebugUnitTest --tests "*VisionConsentPreferencesTest" --rerun-tasks
```

Expected：三态、并发、持久字段全部通过。

- [ ] **Step 5: 提交 Task 4**

```powershell
git add -- body/core/src/main/kotlin/com/superagent/body/core/vision/VisionCoordinateConsentGate.kt body/core/src/test/kotlin/com/superagent/body/core/vision/VisionCoordinateConsentGateTest.kt body/app/src/main/kotlin/com/superagent/body/VisionConsentPreferences.kt body/app/src/test/kotlin/com/superagent/body/VisionConsentPreferencesTest.kt
git commit -m "feat(vision): add three-state consent gate" -m "Co-Authored-By: Codex GPT-5.6 Sol <noreply@openai.com>"
```

### Task 5: 悬浮三选一 UI、撤销与可恢复退出

**Files:**
- Create: `body/core/src/main/kotlin/com/superagent/body/core/ui/VisionConsentUiBridge.kt`
- Create: `body/core/src/test/kotlin/com/superagent/body/core/ui/VisionConsentUiBridgeTest.kt`
- Modify: `body/app/src/main/kotlin/com/superagent/body/FloatingUiService.kt`
- Modify: `body/app/src/main/kotlin/com/superagent/body/BodyService.kt`
- Create: `body/app/src/main/kotlin/com/superagent/body/BodyServiceStartPolicy.kt`
- Create: `body/app/src/test/kotlin/com/superagent/body/BodyServiceStartPolicyTest.kt`
- Modify: `body/app/src/main/kotlin/com/superagent/body/MainActivity.kt`
- Modify: `body/app/src/main/res/layout/activity_main.xml`
- Modify: `body/app/src/test/kotlin/com/superagent/body/FloatingUiPolicyTest.kt`

- [ ] **Step 1: 写 UI bridge 与选择策略 RED 测试**

`VisionConsentUiBridgeTest` 证明：无 presenter 立即返回 null；一个 pending request 只 resolve 一次；presenter 注销时 pending 返回 null。`FloatingUiPolicyTest` 增加三按钮映射，返回键/关闭不产生允许选择。

- [ ] **Step 2: 运行 RED**

```powershell
cd body
.\gradlew.bat :core:testDebugUnitTest --tests "*VisionConsentUiBridgeTest" :app:testDebugUnitTest --tests "*FloatingUiPolicyTest" --rerun-tasks
```

Expected：bridge 和三态策略尚不存在。

- [ ] **Step 3: 实现异步 UI bridge**

```kotlin
object VisionConsentUiBridge {
    fun interface Presenter { fun present(requestId: Long): Boolean }
    @Volatile var presenter: Presenter? = null
    private val ids = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<VisionConsentChoice?>>()

    suspend fun request(): VisionConsentChoice? {
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<VisionConsentChoice?>()
        pending[id] = deferred
        if (presenter?.present(id) != true) {
            pending.remove(id)
            return null
        }
        return kotlinx.coroutines.withTimeoutOrNull(60_000L) { deferred.await() }
            .also { pending.remove(id) }
    }

    fun resolve(id: Long, choice: VisionConsentChoice) { pending[id]?.complete(choice) }
    fun unregister(p: Presenter) {
        if (presenter === p) presenter = null
        pending.values.forEach { it.complete(null) }
    }
}
```

- [ ] **Step 4: 实现悬浮授权面板**

`FloatingUiService` 注册 presenter，在 main looper 显示不可与普通 panel 混淆的授权面板：

```text
视觉坐标可能存在偏移并点击错误位置。
支付、授权等高风险操作仍会再次确认。

[允许所有] [仅允许本次] [不允许]
```

三个按钮分别 resolve 对应 enum。不得预选；返回/服务销毁调用 `unregister`，产生 UI unavailable 而不是允许。

超时分层固定为：UI 等待 60s < Body 五个坐标 handler 75s < Brain 客户端 90s。超时视为 UI unavailable，bridge 必须删除 pending request；迟到按钮点击不得改变授权。

当 presenter 不可用时，`BodyService` 发布高优先级通知“打开应用选择视觉坐标权限”，content intent 指向 `MainActivity`；通知不能包含坐标、页面或 App 名称。通知只引导打开 UI，不自行写授权。

- [ ] **Step 5: 实现拒绝退出与主动重开恢复**

`BodyService` 提供实例回调：设置 `declinedThisRun=true`、停止 `FloatingUiService` 后 `stopSelf()`；发送 package-scoped `ACTION_VISION_DECLINED`。`onStartCommand` 在该标志为 true 时返回 `START_NOT_STICKY`，阻止同一服务实例后台恢复。`MainActivity` 用 `RECEIVER_NOT_EXPORTED` 注册 receiver 并 `finish()`。拒绝不写 SharedPreferences，所以下次用户主动打开并点击“启动躯体服务”会创建新的 coordinator 与 session gate。

在 app 测试中用提取出的 `BodyServiceStartPolicy` 证明：普通启动为 sticky、用户拒绝后为 not-sticky、用户新建服务实例后恢复普通启动策略。

- [ ] **Step 6: 增加设置撤销入口**

`activity_main.xml` 增加 `btn_vision_consent`，文案按状态显示“视觉坐标：已允许所有（点此撤销）”或“视觉坐标：首次使用时选择”。点击已授权状态时调用：

```kotlin
VisionConsentPreferences(this).revoke(System.currentTimeMillis())
refreshStatus()
```

未授权时只展示说明，不提前弹窗。

- [ ] **Step 7: 运行 app/core UI 测试并提交**

```powershell
.\gradlew.bat :core:testDebugUnitTest --tests "*VisionConsentUiBridgeTest" :app:testDebugUnitTest --rerun-tasks
git add -- body/core/src/main/kotlin/com/superagent/body/core/ui/VisionConsentUiBridge.kt body/core/src/test/kotlin/com/superagent/body/core/ui/VisionConsentUiBridgeTest.kt body/app/src/main/kotlin/com/superagent/body/FloatingUiService.kt body/app/src/main/kotlin/com/superagent/body/BodyService.kt body/app/src/main/kotlin/com/superagent/body/BodyServiceStartPolicy.kt body/app/src/main/kotlin/com/superagent/body/MainActivity.kt body/app/src/main/res/layout/activity_main.xml body/app/src/test/kotlin/com/superagent/body/BodyServiceStartPolicyTest.kt body/app/src/test/kotlin/com/superagent/body/FloatingUiPolicyTest.kt
git commit -m "feat(vision): add recoverable consent UI" -m "Co-Authored-By: Codex GPT-5.6 Sol <noreply@openai.com>"
```

### Task 6: 动作入口固定为 context → consent → ActionGate

**Files:**
- Modify: `body/core/src/main/kotlin/com/superagent/body/core/BodyCore.kt`
- Modify: `body/app/src/main/kotlin/com/superagent/body/BodyService.kt`
- Modify: `brain/src/tools/index.ts`
- Modify: `brain/test/vision-action-context.ts`
- Create: `body/core/src/test/kotlin/com/superagent/body/core/vision/VisionConsentActionIngressTest.kt`

- [ ] **Step 1: 写动作入口 RED 测试**

用 fake registry、gate、executor 记录调用顺序，覆盖：

```kotlin
assertEquals(listOf("validate", "consent", "actionGate", "execute"), calls)
```

在同一测试类增加四个独立用例：invalid token → `VISION_CONTEXT_INVALID` + executor 0 次；declined → `VISION_COORDINATE_DECLINED` + stop callback 1 次；UI unavailable → `VISION_CONSENT_UI_UNAVAILABLE` + stop callback 0 次；无 token 的普通 a11y 调用直接走现有 ActionExecutor。

- [ ] **Step 2: 运行 RED**

```powershell
cd body
.\gradlew.bat :core:testDebugUnitTest --tests "*VisionConsentActionIngressTest" --rerun-tasks
```

Expected：BodyCore 尚未注入 registry/gate，测试失败。

- [ ] **Step 3: 实现统一 helper 并接五个动作**

```kotlin
private suspend fun execCoordinateAction(
    req: RpcRequest,
    action: ActionExecutor.Action,
): RpcResponse {
    val token = params(req).string("visionActionToken")
    if (token == null) return execAction(req, action)

    val fresh = perceiver.perceive("a11y", sensitiveSession.inSensitiveSession, forceRefresh = true)
    if (visionContexts.validate(token, fresh.appPackage, fresh.signature) != Validation.Valid) {
        return bad(req, "VISION_CONTEXT_INVALID", "视觉来源已过期或页面已变化")
    }
    return when (visionConsent.ensure()) {
        VisionConsentResult.Allowed -> execAction(req, action)
        VisionConsentResult.Declined -> {
            events.emit("voice", buildJsonObject { put("kind", "stop_request") })
            onVisionDeclined()
            bad(req, "VISION_COORDINATE_DECLINED", "用户未允许视觉坐标操作")
        }
        VisionConsentResult.UiUnavailable ->
            bad(req, "VISION_CONSENT_UI_UNAVAILABLE", "请打开应用选择视觉坐标权限")
    }
}
```

`control.tap/longPress/swipe/selectOption/selectSpec` 全部改用此 helper；ActionExecutor 内部逻辑不改。BodyService 构造并注入 preferences、UI bridge prompt 与 decline callback。

BodyCore 用 `server.rpc(method, 75_000L)` 注册这五个 handler。Brain `tools/index.ts` 定义 `VISION_CONSENT_RPC_TIMEOUT_MS = 90_000`，五个对应 `body.rpc` 调用都显式传入该超时；测试断言客户端超时严格大于 Body handler，Body handler严格大于 UI 等待。

- [ ] **Step 4: 证明授权不能绕过提交边界**

新增测试：gate 返回 Allowed 后，`selectOption("支付")` 仍返回 `COMMIT_BOUNDARY`；敏感会话仍返回 nonce；executor 调用顺序中 consent 之后必有 ActionGate。

- [ ] **Step 5: GREEN、全 core/app 与提交**

```powershell
.\gradlew.bat :core:testDebugUnitTest --tests "*VisionConsentActionIngressTest" --tests "*ActionExecutor*Test" :app:testDebugUnitTest --rerun-tasks
git add -- body/core/src/main/kotlin/com/superagent/body/core/BodyCore.kt body/app/src/main/kotlin/com/superagent/body/BodyService.kt body/core/src/test/kotlin/com/superagent/body/core/vision/VisionConsentActionIngressTest.kt brain/src/tools/index.ts brain/test/vision-action-context.ts
git commit -m "feat(vision): gate visual coordinates on user consent" -m "Co-Authored-By: Codex GPT-5.6 Sol <noreply@openai.com>"
```

### Task 7: 文档、审计与发布门禁

**Files:**
- Modify: `README.md`
- Modify: `docs/07-接口规格说明书.md`
- Modify: `docs/09-安全与隐私设计.md`
- Modify: `docs/12-UI交互状态与视觉规范.md`
- Modify: `docs/16-当前架构代码审计-2026-08-21.md`
- Modify: `docs/17-当前方案设计.md`
- Modify: `brain/test/docs-consistency.ts`

- [ ] **Step 1: 先扩展 docs consistency RED**

新增规则要求六个当前事实源包含：`允许所有`、`仅允许本次`、`VISION_COORDINATE_DECLINED`、`视觉坐标仍未标定`；禁止出现“授权后视觉坐标即可信”或绑定具体 provider/model 的表述。

Run：

```powershell
cd brain
npm run docs-consistency
```

Expected：当前文档缺少三态授权事实而失败。

- [ ] **Step 2: 用图表同步当前实现边界**

README 与 docs/17 加入时序图；docs/12 加三态选择表与恢复状态图；docs/07 增加 optional token、三个 typed error 和五个受控动作；docs/09 明确授权不绕过 ActionGate/HITL；docs/16 记录代码证据与真机未验项。

所有文档必须同时表达：

```text
风险授权已实现 ≠ 视觉坐标语义已标定
```

- [ ] **Step 3: 运行完整验证**

Brain：

```powershell
cd brain
npm run docs-consistency
npm run typecheck
npm run contract
npm run vision-action-context
npm run vision-fallback
npm run smoke
npm run integration
npm run resume-coordinator
```

Body：

```powershell
cd ..\body
.\gradlew.bat :common:test :core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --rerun-tasks
```

Expected：所有命令 exit 0；XML 汇总 failures/errors 均为 0；Debug APK 生成成功。

- [ ] **Step 4: 独立双重复核**

GPT 安全/UX reviewer 检查三态、拒绝恢复、隐私和文案；GLM 架构 reviewer 检查 token 生命周期、公共契约与 Body/Brain 接线。任何 Important 必须修复并重跑对应测试。

- [ ] **Step 5: 提交文档并推送验证**

```powershell
git add -- README.md docs/07-接口规格说明书.md docs/09-安全与隐私设计.md docs/12-UI交互状态与视觉规范.md docs/16-当前架构代码审计-2026-08-21.md docs/17-当前方案设计.md brain/test/docs-consistency.ts
git commit -m "docs(vision): document three-state coordinate consent" -m "Co-Authored-By: Codex GPT-5.6 Sol <noreply@openai.com>"
git -c http.proxy=http://127.0.0.1:7890 push origin main
git -c http.proxy=http://127.0.0.1:7890 ls-remote origin refs/heads/main
```

Expected：远端 `main` SHA 与本地 `git rev-parse HEAD` 完全一致。

## 最终退出条件

- [ ] 三态选择、并发合并、持久/本次/拒绝恢复均有 RED→GREEN 证据。
- [ ] 视觉 token 不进入模型可见 JSON、日志或持久存储。
- [ ] visual context 无效时 fail-closed，普通 a11y 动作兼容。
- [ ] 允许后仍经过 ActionGate/HITL；拒绝零设备动作并停止本次服务。
- [ ] 文档图表明确“已授权风险”与“坐标未标定”的区别。
- [ ] 单元、集成、契约、类型、文档和 APK 全部 fresh 通过。
- [ ] 无真机证据时状态只写“代码闭环，待设备验收”。
