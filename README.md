# SuperAgent-Android

> 把 Pi 级智能大脑装进 Termux，让手机成为它的躯体——能听、能说、能看、能动手、能感知，越用越懂你。

**Android 深度定制超级 AI 助手**：双进程架构（Termux 大脑 + Android 躯体）+ 端侧语音 + 可编程 Agent + 技能自学习 + 三档位深度操控（免 root → Shizuku → AOSP）。

[![status](https://img.shields.io/badge/status-P0%20原型-blue)]()
[![license](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)
[![tests](https://img.shields.io/badge/tests-98%20passing-brightgreen)]()
[![protocol](https://img.shields.io/badge/IPC-v2-orange)](docs/07-接口规格说明书.md)

---

## 当前状态（2026-08-20）

P0 冲刺 **六个里程碑全部达成或代码完成**：

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 安全绿 | ✅ | Token 鉴权 + 协议版本 + 错误码全量 |
| M1 红线绿 | ✅ | 支付/提交边界 body 硬拦（词表+坐标+敏感会话三闸） |
| M2 闭环绿 | ✅ | 真机端到端：文字指令→规划→操控→证据核验→技能固化 |
| M3 飞轮绿 | ✅ | 技能回放（签名步进校验）+ 状态机（candidate→verified） |
| G5 交互绿 | ◐ | 悬浮球+状态条+输入面板+步骤流+暂停/停止（用户确认通过） |
| M4 语音绿 | ◐ | ASR 端侧可用 + TTS vits 代码完成，人评待做 |
| M5 工程绿 | ◐ | 98 项测试全绿 + GitHub Actions CI + 验收报告 v0.95 |

## 架构

```
┌────────────── Android 设备 ──────────────┐
│  Termux 大脑 (Node/TS + pi-agent-core)    │
│   规划·工具编排·守卫·技能召回·角色         │
│ ─────────── JSON-RPC 0.0.0.0:8765 ───────│
│  躯体 App (Kotlin + sherpa-onnx JNI)      │
│   感知·操控·语音·硬件·HITL·技能·悬浮UI    │
│             ↑ 仅 brain 出网               │
│        GLM-4.6v 云端主脑 (tool-calling)    │
└──────────────────────────────────────────┘
```

**安全铁律**：大脑只"想"，躯体只"做"。红线与闸门在躯体侧硬实现——`ActionExecutor` 统一收口（RPC 与技能回放共用唯一执行入口），`ActionGate` 全节点坐标校验，`HITL nonce` 一次性消费绑定原始动作。大脑被注入也无法绕过。

**感知阶梯**：L0 无障碍树 → L1 屏幕截图+VLM（GLM-4.6v 视觉，坐标自动换算）→ L2 auto 路由（a11y 节点不足或含 WebView 时自动切视觉）。

## 快速开始

### 前置

- Android 8.0+（API 24），arm64-v8a 真机
- Android SDK（compileSdk 35）、JDK 17+、Node 20+
- GLM API Key（[智谱开放平台](https://open.bigmodel.cn/)）

### 获取模型

```bash
bash scripts/fetch-models.sh   # 下载 sherpa-onnx .so + ASR/TTS/声纹模型（~600MB）
```

### 躯体（Android）

```bash
cd body
./gradlew :common:test :core:testDebugUnitTest   # 56 项 JVM 单测
./gradlew :app:assembleDebug                     # 出 APK（含模型 ~967MB）
bash scripts/install.sh                          # 装机 + token 桥接
```

装机后在手机上：开无障碍 → 授权悬浮窗 → 启动躯体服务 → 侧边出现控制球。

### 大脑（PC 直连 / Termux）

```bash
cd brain
npm install
export GLM_API_KEY=你的key
npm run typecheck && npm run contract && npm run smoke   # 17+25 项全绿
adb forward tcp:8765 tcp:8765    # PC 直连真机
BODY_URL=http://127.0.0.1:8765 BODY_TOKEN=$(adb shell run-as com.superagent.body cat files/token | tr -d '\r\n') npm run start
```

不接 PC 的完整体验：悬浮球 → 文字输入 → 步骤实时可见 → 暂停/停止/继续。

## 验证流水线

```bash
# brain
cd brain && npm run typecheck && npm run contract && npm run smoke

# body
cd body && ./gradlew :common:test :core:testDebugUnitTest :app:assembleDebug
```

## 目录结构

```
super-agent/
├─ docs/
│  ├─ 00-项目导读与审计交接.md      # 新人入门单一入口
│  ├─ 05-架构设计与移交基线-v2.md   # 架构事实源（含交互层 §6）
│  ├─ 06-功能规格清单与追踪矩阵.md   # 需求事实源（45+ FR）
│  ├─ 07-接口规格说明书.md          # 接口事实源（IPC v2 全量）
│  ├─ 08-非功能需求与验收测试计划.md # 质量事实源（TC-01~14 + 验收报告）
│  ├─ 09-架构决策记录.md           # AD-01~11
│  ├─ 10-P0冲刺计划-重排版.md       # 里程碑与滚动任务池
│  ├─ 12-产品体验需求-用户旅程视角.md # UX 行为基线（GPT sol 审计）
│  └─ 13-多智能体分工与协作规约.md
├─ brain/src/
│  ├─ ipc/           # BodyClient + BrainEvent 上报
│  ├─ tools/         # android.* 工具层（26 个）
│  ├─ guards/        # ReAct 止损 + 证据闸门 + 脱敏 + 相关性 + VLM
│  ├─ personas/      # 角色系统 + 系统提示
│  └─ model.ts       # GLM 主/备用/本地三档降级链
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
| **语音输出** | vits-zh TTS（jieba 分词 + lexicon + fst 规则） |
| **屏幕感知** | a11y 树（L0）→ 截图+GLM-4.6v 视觉识别（L1）→ auto 路由（L2） |
| **操控** | tap/swipe/type/selectOption/selectSpec/back/home/launch（全走 ActionExecutor 统一闸门） |
| **安全** | 提交边界词表 + 坐标全节点校验 + 敏感会话 + HITL nonce 一次性消费 |
| **技能** | learn（轨迹固化）→ run（签名步进校验回放）→ feedback（candidate→verified→active） |
| **守卫** | ReAct 止损（5 类退化+豁免通道）+ 证据闸门（存在性+新颖性+相关性）+ 脱敏 |
| **交互** | 悬浮球+穿透状态条+输入面板+控制面板（暂停/继续/停止）+OFFLINE 心跳 |

## 路线图

| 阶段 | 时间 | 目标 | 状态 |
|---|---|---|---|
| **P0** | 2026-08 → 09 | 真机端到端闭环 | ◐ 接近完成 |
| **P1** | 2026-10 → 11 | 语音主循环 + KWS 唤醒 + 视觉全量 | 📐 |
| **P2** | 2026-12 → 2027-02 | Shizuku 档 + 分发 + 种子用户 | 📐 |

## 核心原则

1. **复用成熟方案**——pi 内核不重造、sherpa 一栈不拼凑
2. **本地优先**——语音全栈端侧、屏幕感知端侧；云端只做规划，且可降级
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
