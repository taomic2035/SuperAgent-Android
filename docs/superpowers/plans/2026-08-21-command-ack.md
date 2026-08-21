# BR-10 / CT-06 命令受理闭环实施计划

> 对应设计：`docs/superpowers/specs/2026-08-21-command-ack-design.md`。每一阶段单独可审计、可回滚；不得提前宣称跨重启 exactly-once。

## 1. 分工与依赖

| 阶段 | 工作 | 主责 | 复核 | 依赖 |
|---|---|---|---|---|
| P0 | 封闭 PAUSING/STOPPING/PAUSED 新命令状态倒退 | GPT | GLM | 无 |
| P1 | 可选关联字段、有限枚举、契约镜像 | GPT | GLM | P0 |
| P2 | Body command journal 与原子状态迁移 | GLM | GPT | P1 |
| P3 | Brain command pump、持久 cursor、幂等 claim | GLM | GPT | P2 |
| P4 | 显式 task context、ack/resolved UI | GPT | GLM | P3 |
| P5 | 双进程重启、未知副作用、真机证据 | GPT + GLM | 交叉复核 | P4 |

公共文件（`Protocol.kt`、`contract.json`、`types.ts`、`main.ts`、docs 07/12/17）修改前必须先看 claim；发现未提交改动时只做不重叠工作或 handoff，不覆盖共享工作区。

```mermaid
flowchart LR
  P0[状态倒退回归] --> P1[协议字段]
  P1 --> P2[Body journal]
  P2 --> P3[Brain claim]
  P3 --> P4[真实 ack UI]
  P4 --> P5[重启与真机]
```

## 2. P0：控制优先级回归

- 在 `UiStateControllerTest` 先加入 PAUSING、STOPPING、PAUSED 提交测试，确认旧实现失败。
- `submitTextInput()` 在上述状态返回结构化拒绝、零 voice 事件、原状态与 checkpoint 不变。
- 保留 OFFLINE 和 IDLE 既有用例；本阶段不把易失发布改称 Brain accepted。

验证：

```powershell
cd body
.\gradlew.bat :core:testDebugUnitTest --tests "com.superagent.body.core.ui.UiStateControllerTest"
```

## 3. P1：共享协议（兼容扩展）

- 先改 `contract.json`，再同步 Kotlin `Protocol.kt` 与 TypeScript `types.ts`。
- `BodyEvent`、`BrainEvent` 新字段均为 optional/default null；添加 `sourceSessionId`、`commandId`。
- 为 BrainEvent 的 `state/requiresUser/resultKind` 引入有限枚举与入站校验；未知值 fail-closed。
- 增加旧 JSON 解码和三方镜像测试。

验证：contract、Body common/core tests、Brain typecheck。

## 4. P2：Body command journal

- 在现有 Body SQLite 边界新增 command repository 和迁移，不把持久化塞进 UiStateController。
- 实现 `reserve / claim / bindTask / terminalize / interrupt` 条件更新；commandId 唯一。
- EventBus 仅发送 wake hint；payload 顶层身份不可由任意 JSON 覆盖。
- 命令正文使用本机保护和 TTL；诊断日志禁止明文。
- repository 并发测试覆盖双 claim、lease 到期、重复 terminal、非法状态倒退。

## 5. P3：Brain command pump

- 从 `main.ts` 抽出可独立测试的 command pump；main 只接线。
- 持久记录 `(bodySessionId, cursor)`，session 变化时安全重建；环形 gap 触发 journal 查询。
- Brain 启动、Body 重连时必须立即 reconcile；另设有退避和上限的周期 reconcile，保证唯一 wake 丢失时 QUEUED 仍最终被发现。
- 原子 claim 成功后才排入既有串行 task queue。
- 同 commandId 的已接受/已解决 receipt 只回放状态，不再次 `beginRun/prompt`。
- 崩溃恢复：QUEUED 可重领；ACCEPTED 无终态默认 INTERRUPTED/unknown_side_effect，禁止自动重放设备动作。

## 6. P4：显式 task context 与 UI

- reporter API 从全局隐式 taskId 改为显式 `{brainSessionId, commandId, taskId}` context。
- `prompt_start` 建立 accepted 映射；finish/error 必须匹配同一 context。
- UI pending 水位改为 `(brainSessionId, taskId, seq)`；拒绝旧 session、未知 commandId、错配终态。
- `submitTextInput` 返回结构化结果而非 Boolean，让 Activity 显示精确拒绝并在失败时保留草稿。
- 文案严格区分“已排队”和“理解中”。

## 7. P5：证据与发布门禁

| 测试批次 | 必须覆盖 |
|---|---|
| 单元 | 状态迁移、重复 commandId、双 claim、终态幂等、旧 session 丢弃 |
| 集成 | Body/Brain 任一重启、非相邻重复、cursor gap、唯一 wake 丢失后的周期领取、ack/finish 错配 |
| 安全 | command 日志脱敏、ActionGate/HITL 不被 ack 路径绕过 |
| 真机 | 双击、断连、Brain kill、Body kill、动作中 kill、暂停后新命令 |

提交前依次运行现有 docs consistency、Brain typecheck/contract/smoke/integration/resume/vision、Body 单测和 APK 构建。每条完成结论记录 commit、命令、用例数和真机证据路径；无设备证据时只标“代码可证”。

## 8. 退出条件

- P0 可独立提交，但 BR-10/CT-06 仍保持“实现中”。
- P1-P4 全部通过后可标“代码闭环，待真机”。
- P5 重启与中断真机矩阵通过后才标“完成”。
