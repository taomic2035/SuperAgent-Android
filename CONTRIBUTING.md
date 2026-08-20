# 贡献指南

欢迎参与 SuperAgent-Android。本指南确保协作不破坏架构一致性。

## 1. 文档是事实源

任何代码变更若涉及以下方面，**必须先改文档再改代码**：

| 变更类型 | 改哪个文档 |
|----------|-----------|
| 接口（RPC 方法/参数/返回/错误码） | [docs/07](docs/07-接口规格说明书.md) + `contract.json` 三处同步 |
| 需求（新增/修改功能、优先级） | [docs/06](docs/06-功能规格清单与追踪矩阵.md) |
| 架构（拓扑/安全模型/状态机/ADR） | [docs/05](docs/05-架构设计与移交基线-v2.md) + [docs/09](docs/09-架构决策记录.md) |
| 用户体验（交互模型/验收标准） | [docs/12](docs/12-产品体验需求-用户旅程视角.md) |
| 质量（性能预算/验收标准） | [docs/08](docs/08-非功能需求与验收测试计划.md) |

**契约纪律**（CT-05）：跨 brain↔body 的类型变更必须先改 `body/common/src/main/resources/contract.json`，然后同步 `Protocol.kt` 和 `types.ts`，双侧镜像测试会拦截漂移。

## 2. 开发流程

```bash
# 大脑（brain/）
cd brain && npm install
npm run typecheck     # TypeScript 类型检查，必须零错误
npm run contract      # 契约镜像（25 类型），必须全绿
npm run smoke         # 冒烟测试（18 项），必须全绿

# 躯体（body/）
cd body
./gradlew :common:test :core:testDebugUnitTest   # 56 项 JVM 单测，必须全绿
./gradlew :app:assembleDebug                     # APK 构建（需先 fetch-models）

# 模型获取（首次或升级时）
bash scripts/fetch-models.sh   # ~600MB（sherpa-onnx .so + ASR/TTS/声纹模型）
```

CI（GitHub Actions）会在 push/PR 时自动跑 brain + body 测试。

## 3. 架构铁律（违反即回退）

1. **brain 只想，body 只做**——感知/操控/语音/HITL/词表/技能权威全在 body；brain 不碰 UI 不持词表
2. **安全闸门在 body 硬实现**——`ActionExecutor` 统一收口（RPC 与回放共用），`ActionGate` 全节点坐标校验，`HITL nonce` 一次性消费
3. **pi 只用核心 Loop**——`Agent` + `pi-ai`，不用 AgentHarness/Session 等周边（AD-05）
4. **M3 本地模型无工具仅闲聊**——弱模型不授予设备控制权
5. **契约唯一真源**——`contract.json`，双侧镜像测试拦截漂移

详见 [docs/05](docs/05-架构设计与移交基线-v2.md) 和 [docs/09](docs/09-架构决策记录.md)（AD-01~11）。

## 4. 多智能体协作

本项目采用多 agent 协作开发（见 [docs/13](docs/13-多智能体分工与协作规约.md)）：

| 角色 | 职责 |
|---|---|
| GLM-5.3 | 架构/规划/实现/提交（唯一 git 操作者） |
| GPT sol | UX 行为定义/审计（docs/12 唯一维护者） |
| DeepSeek | 低成本执行（本地 handoff 队列） |

红线：git commit/push 仅编排方；禁删除文件（移 d:\tmp）；单点精准编辑；密钥只引 SESSION.md 不落盘。

## 5. 提交规范

```
feat(scope): 一句话描述
fix(scope): 一句话描述
docs: 一句话描述
test: 一句话描述
```

- 每次提交附 `Co-Authored-By` 标识
- 语音/模型文件不入库（.gitignore 覆盖 `*.onnx` `*.so` `*.wav` `*.apk`）
- `SESSION.md` 和 `docs/handoff/` 仅本地（已 gitignore）
