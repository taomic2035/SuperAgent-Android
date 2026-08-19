# 变更记录

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。P0 阶段版本号 `0.x`。

## [Unreleased] / P0 实施中

### 已完成

**Termux 原型打通 + GLM 端到端验证**（2026-08-19）

- Termux（v0.118.3）装机：node v24.18.0 + npm 11.19.0；pi-agent-core + pi-ai + pi-telemetry 安装成功（纯 JS，arm64 兼容）
- 通信通道：发现 Android loopback 127.0.0.1 为 per-uid 网络命名空间隔离，body 改 bind `0.0.0.0`（AD-07，token 鉴权保护）；Termux→body 全链路验证（/health/perceive.screen/control.tap）
- brain 打包方案（AD-08）：esbuild ESM bundle，`@earendil-works/*`+`typebox` external（修复 esbuild 打包 pi-ai 的 `ModelsImpl is not a constructor`），banner 注入 createRequire polyfill，产物 `dist/brain.mjs` 约 45KB；部署到 Termux `~/brain-lite/`（node_modules 同目录解析）
- GLM 端到端验证：`live-glm.ts`（mock body）tool-call 往返通过；真机（adb forward 直连）"perceive the current screen"→ GLM 调 `perceive.screen` → 正确识别"超级AI助手"主界面
- 修复：管道模式 stdin EOF 报 `ERR_USE_AFTER_CLOSE`（readline EOF 退出循环）
- 文档：docs/09 AD-07/AD-08 + 原型验证更新；docs/08 验收记录 2.2；README 补打包/部署/运行说明

**AD-05 架构纠正 + R1/R2/R3 重构**（2026-08-19）

- 架构决策：pi 只用核心 Loop（`Agent` + `pi-ai` Provider 解耦），**不用** AgentHarness/Session/JsonlSessionRepo/loadSkills（经 agentos-android 研究文档论证不适合系统级 Agent；三参考项目无一使用）
- 周边在本项目重实现，参照 Kestrel（android-agent）成熟经验：
  - `runState.ts` 增强：失败可见性留痕（成功/失败/崩溃都落全历史）、trace 单调序号、脱敏后落盘（参照 RunTraceBuilder + TraceRedaction）
  - 新增 `guards/reactGuard.ts`：5 类止损（MaxSteps/NoProgress/Oscillation/Looping/Revisiting，参照 Kestrel ReActGuard）
  - `guards/finish.ts` 修正：新颖性核验算法改用 Kestrel FinishEvidence（行包含 evidence 或 evidence 包含行 + ≥4字 + 覆盖半数），原 lineHit 逐字符比对算法有误
- R1：brain `commitBoundary.ts` + 词表副本删除，提交边界/敏感判定权威单点在 body `Guard.kt`
- R2：`guards/index.ts` 接入 ReActGuard（beforeToolCall 止损 + afterToolCall 喂 guard）+ `main.ts`/`tools` 调 `finishRun` 留痕
- R3：brain `skills/index.ts` 删除，检索下沉 body `skill.search` RPC（委托 body/common `SkillIndex`，中文 2-gram TF-IDF）
- 协议 v2.2：skill.search 新增；TraceStep 新增 sensitive/resultKind 字段
- 文档：docs/09 架构决策记录（AD-01~05）；docs/05 §2.2/§2.6/功能树修订；docs/07 §2.5/变更记录 v2.2
- 验收：body :common:test 7 项 + brain typecheck + smoke 全绿 + :app:assembleDebug 绿

**WP1 提交边界确认**（2026-08-18）

- 词表同源治理：`body/common/src/main/resources/commit_boundaries.json` 为单一来源，含 commitPhrases/sensitiveNavPhrases/sensitiveSessionActionVerbs/sensitiveUrlPatterns/sensitiveAppPrefixes
- body `Guard.kt` 重写：从 JSON 资源加载词表，新增 `CommitBoundaries` data class + `isSensitiveSessionAction/isSensitiveUrl/isSensitiveApp/getBoundaries`
- body `SensitiveSession.kt`：委托 `CommitBoundaryGuard`，新增 `onHome()` 退出敏感会话
- body `ScreenPerceiver.kt`：WebView URL 检测（extras `url` 字段命中 sensitiveUrlPatterns → 全页标 sensitive）、感知传入 `inSensitiveSession` 参数
- body `Protocol.kt`：`ScreenResult` 新增 `sensitiveSession: Boolean` 字段
- body `BodyCore.kt`：`perceive.screen` 返回 sensitiveSession、`control.home` 调用 `onHome()` 退出敏感会话
- body `Hitl.kt`：采用 Kestrel `CompletableDeferred+CAS` 模式替代 `CompletableFuture`；confirm 通知聚合（连续确认合并为"全部同意/全部拒绝"）
- body `GuardTest.kt`：扩展至 7 项测试覆盖新方法
- brain `types.ts`：`ScreenResult.sensitiveSession` 同步
- brain `commitBoundary.ts`：从同源 JSON 读取词表，新增 `isSensitiveSessionAction/isSensitiveUrl/isSensitiveApp`
- 文档同步：docs/05 §2.2 词表治理+会话退出+URL检测；docs/06 BD-06/08.1/08.2 状态更新；docs/07 §2.1 ScreenResult+§5.2 词表+变更记录 v2.1

**WP0 安全闭合 + 协议 v2 + 阻塞修复**（2026-08-18）

- 随机 token 鉴权（256bit，filesDir 持久化，ADR-3）
- bootId 轮换 + protocolVersion 校验（/health v2）
- IPC 协议 v2：`PAYMENT_RED_LINE` → `COMMIT_BOUNDARY` 更名；`/blob` 端点预留
- 错误码表正式化（15 个，含 `A11Y_DISCONNECTED`/`SKILL_STALE`/`PROTOCOL_MISMATCH`）
- BodyServer Main 线程死锁修复（handler 调度改 Dispatchers.Default）
- a11y 断连返回 `A11Y_DISCONNECTED`（区分白屏）
- 阻塞编译修复：Speaker.kt 缺 OnlineStream、AudioRecorder WAV 编码、core 序列化 imports
- brain 侧协议对齐 v2（types.ts / client.ts / mock-body / smoke）
- 验收：brain typecheck + smoke 11 项全绿；body `:common:test` 6 项绿；`:app:assembleDebug` 绿

**脚手架期**（2026-08-18，GLM-5.3 整理）

- docs 01–04 分析报告（市场/可行性/选型/初版架构）
- docs 05–08 正式交付件（架构 v2 / 功能规格 / 接口规格 / NFR 与验收）
- brain：IPC client + 21 个 android.* 工具 + 守卫层 + 角色系统 + TF-IDF 技能检索 + mock-body 冒烟
- body：common（协议/守卫/技能索引 6 测试绿）+ core（RPC/感知/操控/语音/硬件/HITL/技能）+ app（无障碍服务 + 前台服务 + 主界面）
- third：sherpa-onnx v1.13.2 kotlin-api + jniLibs（.so 脚本获取）

### 待办（P0 剩余）

- WP2：技能生命周期状态机 + recovery mode
- WP3：会话持久化 + 语音环骨架
- WP5：fetch-models 脚本（锁 zh voices）+ 装机脚本 + GLM live tool-call 测试
- WP1 验收：M1 红线真机演示（TC-04/05/06 执行留痕）

## 版本说明

- **0.1.0**（P0 目标）：端到端最小闭环——语音→规划→操控→证据→固化→回放（docs/08 M0–M5 全绿）
