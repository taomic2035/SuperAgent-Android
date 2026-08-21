# SuperAgent-Android

> 把 Pi 级智能大脑装进 Termux，让手机成为它的躯体——能听、能说、能看、能动手、能感知，越用越懂你。

**Android 深度定制超级 AI 助手**：双进程架构（Termux 大脑 + Android 躯体）+ 端侧语音 + 可编程 Agent + 技能自学习 + 三档位深度操控（免 root → Shizuku → AOSP）。

[![status](https://img.shields.io/badge/status-v0.1.1%20post--release%20audit-orange)]()
[![license](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)
[![tests](https://img.shields.io/badge/tests-brain55%20%7C%20body183-brightgreen)]()
[![protocol](https://img.shields.io/badge/IPC-v2-orange)](docs/07-接口规格说明书.md)

---

## 当前状态（2026-08-21）

仓库最新标签为 `v0.1.1`；当前 `main` 位于其后的 post-release 审计与 P1 整改阶段。Android `versionName` 与 brain `package.json` 仍是运行时版本 `0.1.0`，不要与 Git 发布标签混用。历史 P0 真机闭环有效；本轮已收口安全内层旁路、视觉失败真实性与暂停恢复竞态，真机视觉标定和其余 P1 项仍按下表管理：

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 安全绿 | ✅ | Token 鉴权 + 协议版本 + 错误码全量 |
| M1 红线绿 | ✅ | selector 最终坐标与普通 tap 共用 typed ActionGate；提交边界仍由 Body 权威拦截 |
| M2 闭环绿 | ✅ | 真机端到端：文字指令→规划→操控→证据核验→技能固化 |
| M3 飞轮绿 | ✅ | 技能回放（签名步进校验）+ 状态机（candidate→verified） |
| G5 交互绿 | ✅ | 暂停→持久化→单次恢复、停止不可复活；离线输入在 EventBus 前明确拒绝 |
| M4 语音绿 | ◐ | ASR 与在线播报有真机证据；本地 sherpa TTS 当前禁用，系统 TTS 兜底 |
| M5 工程绿 | ◐ | 本轮 brain 55 个行为组、body 183 JVM 与 Debug APK 构建全绿；真机未复验 |
| ME 记忆 | ◐ | SQLite、反思、归档与原子快照代码已闭环；真实 DB 恢复演练、import 批内去重与管理体验待验 |

审计详情见 [docs/16](docs/16-当前架构代码审计-2026-08-21.md)，整改后的当前方案见 [docs/17](docs/17-当前方案设计.md)。

```mermaid
flowchart LR
    U[用户<br/>语音 / 文字 / 悬浮球] --> B[Termux 大脑<br/>理解 · 规划 · 工具编排]
    B -->|JSON-RPC + Token| C[Android 躯体<br/>感知 · 操控 · 语音 · HITL]
    C --> A[Android 应用与系统]
    A -->|界面 / 状态 / 结果| C
    C -->|结构树 / 截图 / 事件| B
    B -.仅规划/视觉侧出网.-> M[可配置 OpenAI 兼容模型<br/>主模型与视觉模型可独立选择]
    C -->|提交边界确认| U
```

## 架构

```mermaid
flowchart TB
    subgraph Device[Android 设备]
        subgraph Brain[Termux 大脑 · Node.js / TypeScript]
            Agent[pi Agent Loop]
            Tools[工具编排]
            Guards[止损 / 证据 / 脱敏]
            Recall[技能与记忆召回]
            Agent --> Tools
            Guards --> Agent
            Recall --> Agent
        end

        subgraph Body[躯体 App · Kotlin]
            RPC[BodyServer / JSON-RPC]
            Gate[ActionExecutor + ActionGate]
            Sense[无障碍 / 截图 / 语音]
            UX[悬浮 UI / HITL]
            Store[(技能 / 记忆 / run)]
            RPC --> Gate
            RPC --> Sense
            RPC --> UX
            RPC --> Store
        end

        Tools <-->|0.0.0.0:8765<br/>Token + IPC v2| RPC
    end

    Agent -->|tool-calling| Cloud[可配置云端模型<br/>MODEL/VISION_MODEL]
    Gate --> Android[Android 应用 / 系统能力]
    Sense --> Android
```

| 层 | 拥有的权力 | 不应承担 |
|---|---|---|
| 大脑 | 理解意图、拆解任务、选择工具、判断证据 | 直接执行 Android 手势或绕过安全门 |
| 躯体 | 读取端侧状态、执行动作、管理敏感会话和确认 | 自行决定用户业务意图 |
| 云模型 | 生成计划与工具参数 | 持有设备执行权、端侧数据权威 |
| 用户 | 授权高风险动作、随时暂停或终止 | 为普通低风险步骤逐项确认 |

**安全铁律**：大脑只“想”，躯体才“做”。`ActionExecutor`、`ActionGate`、敏感会话和 HITL nonce 主链已经落地；selector 内部最终坐标也复用同一 typed gate。

**感知阶梯**：L0 a11y、L1 screenshot/blob/VLM 与 L2 auto 路由已接通。provider、格式、越界坐标或取帧期间旋转/resize 会 typed fallback 到 fresh a11y；但“边界内”不等于“语义坐标系正确”。受控多点标定或 SoM 映射完成前，direct-coordinate 仅属实验能力，不应直接驱动生产动作。

以下是**目标门禁（尚未实现）**；当前代码仍可能暴露或消费视觉 marks，不能把该图理解为已上线运行链。

```mermaid
flowchart LR
    V[VLM 输出] --> S{格式与边界合法?}
    S -->|否| A[fresh a11y fallback]
    S -->|是| M{目标门禁<br/>语义坐标已验证?}
    M -->|否| B[目标行为：阻断设备动作<br/>当前尚未实现]
    M -->|是| G[ActionGate 最终坐标闸]
```

### 一次任务如何闭环

```mermaid
sequenceDiagram
    actor User as 用户
    participant Body as Android 躯体
    participant Brain as Termux 大脑
    participant App as 目标应用

    User->>Body: 语音或文字指令
    Body-->>User: 本地排队 / 明确拒绝
    Body->>Brain: 命令唤醒与领取
    Brain-->>Body: prompt_start（Brain 接受）
    Brain->>Body: perceive.screen
    Body-->>Brain: 结构树 / 截图证据
    Brain->>Brain: 规划 + 守卫检查
    Brain->>Body: control.*
    alt 命中提交边界
        Body->>User: HITL 确认
        User-->>Body: 一次性 nonce 授权
    end
    Body->>App: 执行动作
    App-->>Body: 界面变化
    Brain->>Body: 再感知并核验证据
    Body-->>Brain: 结果证据
    Brain-->>User: 完成 / 失败 / 需介入
```

> 命令回执现状：跨重启的持久 command journal/原子 claim 已完成设计、尚未实现；当前文字输入仍经易失 EventBus。图中的“排队/领取”表示目标闭环，不是已完成声明。状态倒退保护已实现：PAUSING、STOPPING、PAUSED 不会被新文字指令覆盖。

## 快速开始

### 前置

| 依赖 | 最低要求 | 用途 |
|---|---|---|
| Android 真机 | Android 8.0+（API 24）、arm64-v8a | 运行躯体、无障碍和端侧模型 |
| Android 工具链 | SDK compileSdk 35、JDK 17+ | 构建与安装 APK |
| Node.js | 20+ | 运行 Termux 大脑与验证脚本 |
| OpenAI 兼容 API 凭据 | 你选择的云端 provider | 主规划与视觉可分别配置 |

### 获取模型

```bash
bash scripts/fetch-models.sh   # 下载 sherpa-onnx .so + ASR/TTS/声纹模型（当前约 978MB）
```

### 躯体（Android）

```bash
cd body
./gradlew :common:test :core:testDebugUnitTest :app:testDebugUnitTest   # 当前 183 项 JVM 单测
./gradlew :app:assembleDebug                     # Debug APK 当前约 1.01GB
bash scripts/install.sh                          # 装机 + token 桥接
```

装机后在手机上：开无障碍 → 授权悬浮窗 → 启动躯体服务 → 侧边出现控制球。

### 大脑（PC 直连 / Termux）

```bash
cd brain
npm install
export GLM_API_KEY=你的key
npm run typecheck && npm run contract && npm run smoke && npm run integration
adb forward tcp:8765 tcp:8765    # PC 直连真机
BODY_URL=http://127.0.0.1:8765 BODY_TOKEN=$(adb shell run-as com.superagent.body cat files/token | tr -d '\r\n') npm run start
```

`GLM_*` 是历史兼容变量名，不代表业务代码绑定某个厂商；主模型通过 OpenAI 兼容地址与模型 ID 选择：

| 配置 | 作用 | 说明 |
|---|---|---|
| `GLM_BASE_URL` | 主 provider 的 OpenAI 兼容地址 | 历史变量名，地址可替换 |
| `GLM_API_KEY` | 主 provider 鉴权 | 只从环境读取 |
| `MODEL` | 主规划模型 ID | 不应在业务分支判断具体型号 |
| `BACKUP_LLM_URL/BACKUP_MODEL` | 备用云端层 | 与主 provider 独立 |
| `LOCAL_LLM_URL/LOCAL_MODEL` | 本地只读闲聊层 | 不授予设备工具 |

视觉路由可独立选择 provider：

| 配置 | 作用 | 缺省语义 |
|---|---|---|
| `VISION_BASE_URL` | 视觉 provider 的 OpenAI 兼容地址 | 不注册独立视觉 provider |
| `VISION_API_KEY` | 视觉 provider 鉴权，只从环境读取 | 允许内网免鉴权端点 |
| `VISION_MODEL` | 视觉模型 ID | 未配置时跟随当前主模型 |
| `VISION` | 视觉能力开关 | `1` |

当前部署示例（型号只是配置值，可替换）：

```bash
export VISION_BASE_URL=https://your-openai-compatible-endpoint/v1
export VISION_API_KEY=your-vision-key
export VISION_MODEL=qwen3.7-plus  # 当前部署示例，可替换
```

不得把凭据写入 README、`SESSION.md` 或源码；建议放入权限受限的本机环境文件。修改 `VISION_*` 不会改变主规划模型、Body 安全门禁或设备执行权。

不接 PC 可使用悬浮球和文字输入。暂停只在动作边界落盘，继续只领取一次 settled paused checkpoint；stop 会把 checkpoint 写成不可续终态。

## 验证流水线

| 范围 | 命令 | 当前证据 |
|---|---|---|
| brain 类型 | `npm run typecheck` | 通过 |
| IPC 契约 | `npm run contract` | 34 个共享类型一致 |
| brain 行为 | `npm run smoke` | 26 项通过 |
| brain 集成 | `npm run integration` | 6 项通过 |
| 恢复竞态 | `npm run resume-coordinator` | 15 项通过：single-flight、stop 优先、paused→stopped |
| 视觉降级 | `npm run vision-fallback` | 8 项通过：typed fallback、坐标与尺寸校验 |
| body JVM | `:common:test :core:testDebugUnitTest :app:testDebugUnitTest` | 183 项通过（23 common + 156 core + 4 app） |
| APK 构建 | `:app:assembleDebug` | 本轮通过 |
| 真机 | 装机验收 | 本轮尚未复验 |

```bash
# brain
cd brain && npm run typecheck && npm run contract && npm run smoke && npm run integration && npm run resume-coordinator && npm run vision-fallback

# body
cd body && ./gradlew :common:test :core:testDebugUnitTest :app:assembleDebug
```

## 目录结构

```

### 文档怎么读

| 你想了解 | 首选文档 | 定位 |
|---|---|---|
| 快速接手项目 | [项目导读与审计交接](docs/00-项目导读与审计交接.md) | 入口、边界、验证方式 |
| 当前系统实际上怎么工作 | [架构设计与移交基线](docs/05-架构设计与移交基线-v2.md) | 当前架构事实源 |
| 当前有哪些风险 | [当前架构代码审计](docs/16-当前架构代码审计-2026-08-21.md) | 证据、等级、整改建议 |
| 下一版准备怎么改 | [当前方案设计](docs/17-当前方案设计.md) | 目标架构与分批方案 |
| 查功能完成度 | [功能规格与追踪矩阵](docs/06-功能规格清单与追踪矩阵.md) | FR 状态和证据 |
| 查 RPC 与错误码 | [接口规格说明书](docs/07-接口规格说明书.md) | IPC v2 契约 |
| 理解关键取舍 | [架构决策记录](docs/09-架构决策记录.md) | ADR 与约束 |
super-agent/
├─ docs/
│  ├─ 00-项目导读与审计交接.md      # 新人入门单一入口
│  ├─ 05-架构设计与移交基线-v2.md   # 架构事实源（含交互层 §6）
│  ├─ 06-功能规格清单与追踪矩阵.md   # 需求事实源（45+ FR）
│  ├─ 07-接口规格说明书.md          # 接口事实源（IPC v2 全量）
│  ├─ 08-非功能需求与验收测试计划.md # 质量事实源（TC-01~14 + 验收报告）
│  ├─ 09-架构决策记录.md           # AD-01~14
│  ├─ 10-P0冲刺计划-重排版.md       # 里程碑与滚动任务池
│  ├─ 12-产品体验需求-用户旅程视角.md # UX 行为基线（GPT sol 审计）
│  ├─ 13-多智能体分工与协作规约.md
│  ├─ 15-记忆与自进化架构.md       # 记忆专题设计
│  ├─ 16-当前架构代码审计-2026-08-21.md # 当前审计事实
│  └─ 17-当前方案设计.md           # 审计后目标方案
├─ brain/src/
│  ├─ ipc/           # BodyClient + BrainEvent 上报
│  ├─ tools/         # 感知/操控/语音/技能/记忆/HITL/finish 工具
│  ├─ guards/        # ReAct 止损 + 证据闸门 + 脱敏 + 相关性 + VLM
│  ├─ personas/      # 角色系统 + 系统提示
│  └─ model.ts       # 可配置主/备用/本地三档降级链 + 独立视觉 provider
├─ body/
│  ├─ common/        # 纯 JVM：协议契约 + 词表守卫 + 技能索引
│  ├─ core/          # Android：RPC/感知/操控/语音/HITL/技能/悬浮UI/截图
│  └─ app/           # 应用：主界面 + 前台服务 + 悬浮球 + 输入面板
├─ scripts/          # fetch-models / install / deploy-brain / rpc-concurrency-test
├─ third/sherpa-onnx/ # v1.13.2 vendored（.so 脚本获取）
└─ .github/workflows/ci.yml  # GitHub Actions CI
```

## 核心能力

| 能力 | 实现 |
|---|---|
| **语音输入** | SenseVoice 端侧 ASR（16kHz，静音断句） |
| **语音输出** | 在线 edge/azure 合成 → body 内存播放；失败时系统 TTS，sherpa 本地路径待恢复 |
| **屏幕感知** | a11y + 截图/VLM/auto；typed fallback、真实尺寸换算与旋转 fail-closed |
| **操控** | tap/swipe/type/select/back/home/launch；主路径走 ActionExecutor，内部 selector 闸待补 |
| **安全** | 提交边界词表 + 坐标全节点校验 + 敏感会话 + HITL nonce 一次性消费 |
| **技能** | learn（轨迹固化）→ run（签名步进校验回放）→ feedback（candidate→verified→active） |
| **守卫** | ReAct 止损（5 类退化+豁免通道）+ 证据闸门（存在性+新颖性+相关性）+ 脱敏 |
| **记忆** | SQLite 记忆、成功/失败反思、run 归档、Termux 快照恢复（治理待加强） |
| **交互** | 悬浮球+状态条+输入面板+OFFLINE 心跳；竞态安全的暂停/继续/停止 |

## 路线图

| 阶段 | 时间 | 目标 | 状态 |
|---|---|---|---|
| **P0** | 2026-08 | v0.1.0 真机最小闭环 | 已发布；审计缺口转整改 |
| **P1** | 2026-08 → 11 | 安全收口、视觉真实性、状态/记忆可靠性、KWS | 进行中 |
| **P2** | 2026-12 → 2027-02 | Shizuku 档 + 分发 + 种子用户 | 📐 |

## 核心原则

1. **复用成熟方案**——pi 内核不重造、sherpa 一栈不拼凑
2. **本地优先**——ASR/安全/数据权威在端侧；规划与当前主路 TTS 可用云服务并可降级
3. **安全铁律**——提交边界动作 100% 走人工确认；弱模型不授予设备控制权
4. **开源资产入 third/**——版本锁定、相对路径引用、升级只换目录

## 致谢

- [Pi](https://github.com/earendil-works/pi) — Agent 内核
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — 端侧语音推理
- Clowder — VoiceConfig 音色契约
- MiraAgent — 系统权威与验证链设计
- Kestrel — 真机操控与 UI 交互模式

## 协议

[Apache License 2.0](LICENSE)。第三方组件各自遵循其原始协议（sherpa-onnx MIT、pi MIT）。
