# SuperAgent-Android

> 把 Pi 级智能大脑装进 Termux，让手机成为它的躯体——能听、能说、能看、能动手、能感知，越用越懂你。
>
> **Android 深度定制超级 AI 助手**：双进程架构（Termux 大脑 + Android 躯体）+ 端侧语音全家桶 + 可编程 Agent + 技能自学习 + 三档位深度操控演进（免 root → Shizuku → AOSP）。

[![status](https://img.shields.io/badge/status-P0%20原型- blue)]()
[![license](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)
[![protocol](https://img.shields.io/badge/IPC-v2-orange)](docs/07-接口规格说明书.md)

---

## 为什么做这个

聊天助手是红海，**可自主行动的本地优先语音人设助手**是蓝海。现有方案各占一隅：

- **Pi**（92.5k★）有最强 Agent 内核，但跑在云端，不碰设备
- **MiraAgent** 有系统级权威设计，但绑定 AOSP 深度定制，门槛极高
- **Kestrel** 有真机操控实证，但 Agent 内核自研、迭代慢
- **Clowder** 有语音人设契约，但无操控能力

SuperAgent 把四者融合：**Pi 的大脑 + Kestrel 的躯体 + Clowder 的人设 + MiraAgent 的权威**，且全部本地优先、开源。

## 架构一览

```
┌────────────── Android 设备 ──────────────┐
│  Termux 大脑 (Node/TS + pi-agent-core)    │
│   规划·工具编排·守卫·技能召回·角色         │
│ ─────────── JSON-RPC localhost ──────────│
│  躯体 App (Kotlin + sherpa-onnx JNI)      │
│   感知·操控·语音·硬件·HITL·技能存储        │
│             ↑ 仅 brain 出网               │
│        GLM-4.6v 云端主脑 (tool-calling)    │
└──────────────────────────────────────────┘
```

**职责铁律**：大脑只"想"，躯体只"做"；红线与闸门在躯体侧硬实现，大脑被注入也无法绕过（R1/R2 权威分立）。

三档位：**L1 免 root**（无障碍服务，任何零售机）→ **L2 Shizuku**（ADB 提权）→ **L3 AOSP 定制 ROM**（系统级）。

## 快速开始

### 前置

- Android 8.0+（API 24），arm64-v8a 真机
- Android SDK（compileSdk 35）、JDK 17+、Node 22.19+
- GLM API Key（[智谱开放平台](https://open.bigmodel.cn/)）

### 大脑（Termux / 开发机）

```bash
cd brain
npm install
export GLM_API_KEY=你的key
npm run typecheck   # 类型检查
npm run smoke       # 冒烟（mock 躯体，动态计数）
npm run start       # REPL 入口
```

**打包与部署**（pi 生态依赖 external，Termux 侧 npm 预装）：

```bash
cd brain
node build.mjs                      # 产出 dist/brain.mjs（约 45KB，仅本项目代码）
# 部署到 Termux（brain.mjs 需放在装有 node_modules 的目录，如 brain-lite/）
adb push dist/brain.mjs /data/local/tmp/brain.mjs
adb shell run-as com.termux cp /data/local/tmp/brain.mjs /data/data/com.termux/files/home/brain-lite/brain.mjs
# Termux 内运行（pi 依赖已在 brain-lite/node_modules）
cd ~/brain-lite
GLM_API_KEY=你的key BODY_URL=http://127.0.0.1:8765 BODY_TOKEN=$(cat token) node brain.mjs
```

**开发机直连真机**（替代 Termux 跑 brain，输出可直接读取）：

```bash
adb forward tcp:8765 tcp:8765    # PC 的 127.0.0.1:8765 → 真机 body
# 然后本地跑 brain.mjs 即可操控真机（BODY_URL=http://127.0.0.1:8765）
```

### 躯体（Android Studio / Gradle）

```bash
cd body
# 取 sherpa-onnx v1.13.2 预编译 .so（见 third/README.md §1）
./gradlew :common:test          # 纯 JVM 模块 6 项单测
./gradlew :app:assembleDebug    # 出 APK
```

装机后：开启无障碍服务 → 启动躯体前台服务 → Termux 读取 token 桥接 → 大脑连接。

详见 [docs/08](docs/08-非功能需求与验收测试计划.md) 验收测试计划。

## 目录结构

```
super-agent/
├─ docs/                       # 正式交付件文档集
│  ├─ 01-市场分析.md
│  ├─ 02-可行性分析.md
│  ├─ 03-选型分析.md
│  ├─ 04-架构蓝图与脚手架骨架.md
│  ├─ 05-架构设计与移交基线-v2.md   # 架构事实源
│  ├─ 06-功能规格清单与追踪矩阵.md   # 需求事实源
│  ├─ 07-接口规格说明书.md          # 接口事实源
│  └─ 08-非功能需求与验收测试计划.md # 质量事实源
├─ brain/                      # 大脑（Termux / TypeScript）
│  ├─ src/
│  │  ├─ ipc/                  # IPC 协议类型 + 客户端
│  │  ├─ tools/                # android.* 工具层（21 个）
│  │  ├─ guards/               # 提交边界 + 证据闸门
│  │  ├─ personas/             # 角色系统 + VoiceConfig
│  │  ├─ skills/               # TF-IDF 技能检索
│  │  ├─ model.ts              # GLM / 本地模型接入
│  │  └─ main.ts               # REPL 入口
│  └─ test/                    # mock-body + 冒烟测试
├─ body/                       # 躯体（Android / Kotlin）
│  ├─ common/                  # 纯 JVM：协议/守卫/技能索引
│  ├─ core/                    # Android 库：RPC/感知/操控/语音/硬件/HITL/技能
│  └─ app/                     # 应用：无障碍服务 + 前台服务 + 主界面
├─ third/                      # 第三方开源资产（vendored）
│  └─ sherpa-onnx/             # v1.13.2 kotlin-api + jniLibs（.so 脚本获取）
├─ README.md
├─ LICENSE
├─ CONTRIBUTING.md
└─ CHANGELOG.md
```

## 文档索引

| 文档 | 内容 | 角色 |
|------|------|------|
| [01](docs/01-市场分析.md) | 市场定位与蓝海论证 | 决策依据 |
| [02](docs/02-可行性分析.md) | 立项可行性 | 决策依据 |
| [03](docs/03-选型分析.md) | 技术选型 | 决策依据 |
| [04](docs/04-架构蓝图与脚手架骨架.md) | 初版架构（已被 05 修订） | 历史 |
| [05](docs/05-架构设计与移交基线-v2.md) | 架构 v2 + ADR | **架构事实源** |
| [06](docs/06-功能规格清单与追踪矩阵.md) | 45 条 FR + 追踪矩阵 | **需求事实源** |
| [07](docs/07-接口规格说明书.md) | IPC v2 全量接口 | **接口事实源** |
| [08](docs/08-非功能需求与验收测试计划.md) | NFR + 测试用例 + 交付清单 | **质量事实源** |

## 路线图

| 阶段 | 时间 | 目标 | 状态 |
|------|------|------|------|
| **P0** 最小闭环 | 2026-08 → 09 | 真机端到端：语音→规划→操控→证据→固化→回放 | 🔧 实施中 |
| **P1** 生产可用 | 2026-10 → 11 | 感知 L1 视觉、技能飞轮、语音主循环、安全闭合 | 📐 |
| **P2** 深度定制起步 | 2026-12 → 2027-02 | Shizuku 档 + 分发（PAD 动态下发）+ 种子用户 | 📐 |

详见 [docs/05 §4](docs/05-架构设计与移交基线-v2.md#4-计划与里程碑)。

## 核心原则

1. **复用成熟方案，不追求技术洁癖**——pi 内核不重造、sherpa 一栈不拼凑
2. **本地优先**——语音全栈端侧、屏幕感知端侧；云端只做规划，且可降级
3. **安全铁律**——提交边界动作 100% 走人工确认；弱模型不授予设备控制权
4. **开源资产入 third/**——版本锁定、相对路径引用、升级只换目录

## 致谢

本项目融合四个先行者的智慧：

- [Pi](https://github.com/earendil-works/pi) — Agent 内核
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — 端侧语音推理
- Clowder — VoiceConfig 音色契约
- MiraAgent — 系统权威与验证链设计

## 协议

[Apache License 2.0](LICENSE)。

第三方组件各自遵循其原始协议（sherpa-onnx MIT、pi MIT、llama.cpp MIT）。
