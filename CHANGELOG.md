# 变更记录

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。P0 阶段版本号 `0.x`。

## [Unreleased] / P0 实施中

### 已完成

**GPT 5.6 强制审计消化 + vits Plan B + 视觉双端（2026-08-19 深夜第五批）**

- **审计修复 7 项**（docs/11 报告入库，475dccb）：P0-01 敏感会话绑真实前台（perceive 同步）/ P0-02+03 ActionGate 统一坐标闸门（全节点检查封父容器绕过+敏感会话判定）/ P0-04 过渡（回放 tap 走同闸）/ P0-05 批准单次消费 / P1-02 /blob/{id} 路由修复（mock 盲区：真 body 恒 NOT_FOUND）/ P1-05 签名口径统一 / P2-01 Image 句柄 finally。立项待做：ActionAuthorization 完整收口、HITL nonce、REPL 终态、幂等并发、请求上限、视觉隐私
- **G2 飞轮绿达成（M3，提前 12 天）**：TC-09 回放 4/4 步 0.25s + 无关页 stale@0 零变化不盲走；TC-10 状态机 verified 实测；历经 P0-16/17/21/22/18 五连真机修复（两层手势自死锁/两种盲走形态/签名闸落地）
- **TTS Plan B（G3 门解法）**：vits-zh-hf-fanchen 优先加载（lexicon+jieba 独立路径绕开 Kokoro/espeak 问题簇；jieba 惰性落盘+前置校验）
- **视觉 L1 双端打通**：BodyClient.blob + GLM-4.6V marks（fail-open）+ /blob 端点 + MediaProjection 截图 + screenshotRef 契约三处同步；坐标换算待真机标定
- **brain 强化**：#19a/#20 提示词 / #7 finishVerified / 降级链脑裂修复 / #12 驳回复盘字段 / #8 30-run 历史归档 / #13 slug 防碰撞 / #24 settled 签名 / #25 匹配收紧
- **工程**：P0 验收报告 v0.9（docs/08 §2.3，M0-M3 四绿）/ docs/10 主线支线池 + 24h 值班巡检 / docs/00 新 agent 导读 / 人评材料包 / TL-01~03 ✅

**G1 闭环绿达成（M2 文本模式，2026-08-19）**——P0 冲刺首个里程碑，提前 7 天

- 三判据留痕：证据核验真实工作（真机美团驳回+换证通过）/ skill.learn 事件（设置任务 4 步 → candidate + 设备落盘）/ trace resultKind（finish_rejected 多轮）
- 复验链：:core 序列化插件修复（R1 直证 slug 返回）→ R2 四门全绿宣告；tap 落点红线 5/5；TC-08/TC-14 真机 PASS；launch 诚实化确认
- 遗留移交：#14 int8 TTS（死点更早+零内存压力→模型文件/config 方向）、#12 驳回复盘、#13 learn 门槛与 slug、G2 飞轮验证排队

**G1 复验轮修复（P0×2 + P1×1）**（2026-08-19）

**P1 复测轮成果 + 三 P0 修复（G1 判据 2/3 达成）**（2026-08-19）

- 真机复测（美团 15 步全 located）：**task.finish 证据核验真实驳回 + trace finish_rejected 留痕**（G1 判据 ①②达成）；ASR 烟测 PASS（SenseVoice mmap 链路健康，佐证 noCompress 正确、问题收敛到 espeak）；TTS 根因翻案定论 native exit(1)（espeak 守卫 bug，非华为杀）；P5 部署链 sha256 一致
- **修复 1（P0 安全）：control.tap/longPress 落点提交边界校验**——真机实测模型用坐标点击绕过 selectOption 红线（tap"提交订单"零拦截）；现 tap 前感知落点节点，命中词表一律 COMMIT_BOUNDARY（感知失败则放行，不误伤）
- **修复 2（P0 功能）：manifest 补 `<queries>`**——Android 11+ 包可见性缺失致 control.launch 对第三方 App 恒"包不存在"
- **修复 3：deploy-brain.sh MSYS2_ARG_CONV_EXCL 防护**（Git Bash 设备路径转换坑，真机实测）
- 新 APK（676,804,829 B）已构建；G1 收口轮任务（TTS 门 + 成功终态任务补 skill.learn 判据 + 红线/launch 复验）入 deepseek 队首
- 观测留档：华为会拦截 crashed service 自动重启（TC-14 不得依赖 START_STICKY）；上轮"华为周期清后台"结论整体作废；runstate trace args 恒空（脱敏代价，可复盘性 P1 再议）

**闭环强化批次（G1.2/G2.3/CT-05/BR-04.4 + 语音根因修复）**（2026-08-19）

- **TTS 根因两次更正**：① noCompress——APK 模型资产 DEFLATE 导致 sherpa mmap 失效（app/build.gradle.kts `androidResources.noCompress`，新 APK 资产 STORED 核验）；② 真凶 espeak 守卫 bug——`assets.open()` 探测目录恒 false → espeak-ng-data 拷贝被跳过 → native exit(1) 整进程死（Zygote 证据推翻"华为内存杀"误诊）；修复 `hasAssetDir`（assets.list）+ tts() 前置 `lang/` 预检（坏 dataDir 抛 SpeechUnavailable 不拖死进程）；speech.say 失败 emit say_failed 事件（不静默）
- **G1.2 Kestrel 差距回补**：finish 证据驳回留痕（resultKind=finish_rejected+计入止损）、noProgress 阈值 3→6（生产教训）、崩溃兜底（uncaughtException→finishRun(crashed)）
- **G2.3 Recovery mode（BD-07.3）**：SKILL_STALE 失配上下文透传（从失配处续走）；skill.learn 同 slug 覆盖=复活语义 + skill.revive 事件
- **CT-05 契约镜像（提前至 P0）**：contract.json 真源 + 双侧镜像测试（body ContractMirrorTest 27 类型 / brain contract.ts AST 24 类型），任一侧字段漂移即测试失败
- **PROTOCOL_MISMATCH 补实现**：brain waitForBody 对 health protocolVersion fail-fast
- **BR-04.4 隐私脱敏（brain 侧）**：redact.ts 发送前打码（身份证/卡号/密码/验证码/余额→[REDACTED:类型]），raw 仅留进程内；系统提示补 [REDACTED] 不可点/不可作证据
- **promptBuilder 规则对齐**（文档审计 T5 采纳）：hitl.confirm action 放行流程、证据驳回 3 次强制转人工、SKILL_STALE 续走指引
- **BR-02.2/02.3 模型韧性（brain 侧，P1 提前）**：备用云端注册（BACKUP_LLM_URL/BACKUP_MODEL，无视觉降级）+ promptAgent 连续失败 ≥3 次自动切换并重试当次输入；M3 本地铁律（无云端 key→localOnly 空工具+离线闲聊提示词，云端兜底链终点也是 local 闲聊）
- **BR-04.3 证据相关性软门（brain 侧，P1 提前）**：独立判官 Agent 单轮判定（PASS / FAIL:原因），fail-open 设计（审查不可达放行；硬门仍是存在性+新颖性）；驳回计入 finishRejectCount 升级链；EVIDENCE_RELEVANCE=0 可关
- **trace 落盘保留无 PII 参数**（修 A 观测"GLM 操作不可复盘"）：坐标/label/pkg/name 保留（SAFE_ARG_KEYS 白名单），文本载荷仍丢弃；断点续跑上下文带细节（`control.selectOption（大杯） ✗`）
- 工程脚本：rpc-concurrency-test.sh（BD-01.1）；brain npm contract 入流水线
- 流水线现状：brain typecheck+contract+smoke 16 项；body :common:test 18 项
- 新约定：docs/handoff/ 多 agent 协作区（本地）+ deepseek 取件队列；红线=commit/push 编排专属、禁删除（移动 d:\tmp 代替）、禁批量刷改

**TC-08 证据防谎报（brain 侧）**（2026-08-19）

- `noteFinishRejected()`：task.finish 证据驳回计数入 RunState（落盘、无 PII）；连续 ≥3 次驳回时错误信息升级为强制 `hitl.handoff` 建议（原实现可无限次驳回无升级）；成功 finish 或新 beginRun 清零
- smoke 第 4 节新增回归：×3 升级提示 + 有效证据通过（13 项全绿）

**TC-14 断点续跑（brain 侧）**（2026-08-19）

- 启动改为 `peekRun()` 预览未完成任务（不再提前灌入 current，避免旧状态污染新任务）；REPL 输入「继续」/`continue` 才真正 `resumeRun()`
- `buildResumeContext()`：基于脱敏 trace（工具名+成败）构造续跑上下文注入 user prompt，指示模型先 perceive.screen 重建现场再续走——不信任旧记录的界面状态；恢复时 ReAct 止损预算重置
- `success` 终态任务不再可续；输入新任务 = 明确放弃旧任务；无可续任务时输「继续」提示而非误当新任务执行
- runState 状态目录改惰性求值（测试可用 SUPER_AGENT_STATE_DIR 隔离）；smoke 新增第 6 节回归（12 项全绿）

**GLM-5.3 审计修复（5 问题 + 清理）**（2026-08-19）

- **H1 ReAct 止损跨任务失效**：`reset()` 全量清空（含 `totalRecorded`），步数预算按 run 计（Kestrel 语义）——原实现 30 步进程终身配额，长驻 brain 跑几个任务后被永久 `max_steps` 拦死；另增止损豁免通道（perceive.screen/task.finish/hitl.*/speech.say 不拦），否则止损提示让模型"重新感知/声明失败"而这些调用本身被拦，死锁出不去。smoke 新增 2 项回归用例
- **H3 hitl.ask 回复失效（Android 12+）**：RemoteInput 的 PendingIntent 必须 `FLAG_MUTABLE`（S+ 上 IMMUTABLE 回复 PI 的 `getResultsFromIntent` 恒 null，回答变空串）；仅 ask 的回复 PI 放开 mutable，其余保持 IMMUTABLE
- **H2 三层超时对齐**：HITL 等待 60s < BodyServer handler 75s < brain 客户端 90s（原先 30s/15s，用户 30~60s 点确认 brain 已判死；声纹注册 3 段录音必撞 30s 线）；BodyServer 支持按方法注册超时（语音 60s / skill.run 120s），brain rpc 增可选 timeoutMs（默认 35s）；幂等缓存改 FIFO 淘汰（原按 ConcurrentHashMap 随机序淘汰）
- **M1 skill.run 回放绕过敏感会话确认**：SkillStore 接入 SensitiveSessionTracker，敏感 App 内回放遇确认类动作词（发送/删除/转账…）→ SensitiveHandoff 停手转人工，与 control.* RPC 路径同闸；配套 `hitl.confirm` 增可选 `action` 参数（用户同意后按确切标签放行一次、2 分钟时效、敏感会话切换失效），修复敏感会话内"确认后重试仍被拦"的死锁
- **M2 Android 14+ 启动崩**：Hitl/VoiceLoop 动态注册接收器补 `RECEIVER_NOT_EXPORTED`（targetSdk 35 两参重载在 Android 14+ 抛 SecurityException）
- 清理：SpeechEngine.say 异常路径 wakelock/AudioTrack 释放（try/finally）；brain control.* 失败报错透传 body note（"无障碍服务未连接"不再误报"未命中元素"）；删 guards 死代码（inSensitiveSession 镜像、recordRecovery）

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
