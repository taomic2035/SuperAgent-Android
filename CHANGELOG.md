# 变更记录

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。P0 阶段版本号 `0.x`。

## [Unreleased] / P0 实施中

### 已完成

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
