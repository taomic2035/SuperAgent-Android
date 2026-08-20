# P0 v0.1.0 Release Notes（草稿——等人评通过后打 tag 发布）

## SuperAgent-Android v0.1.0

**首个 P0 里程碑版本**：真机端到端闭环 + 安全架构 + 悬浮交互层 + 技能飞轮。

### 核心能力

- **端到端闭环**：文字/语音指令 → GLM-4.6v 规划 → 操控真机 → 证据核验 → 技能固化 → 回放
- **安全铁律**：提交边界（词表+坐标+敏感会话三闸）、HITL nonce 一次性消费、ActionExecutor 统一收口
- **感知阶梯**：L0 无障碍树 → L1 截图+VLM（坐标自动换算）→ L2 auto 路由
- **悬浮交互**：侧边控制球 + 穿透状态条 + 输入面板 + 暂停/继续/停止 + OFFLINE 心跳
- **技能飞轮**：learn → run（签名步进校验）→ feedback（candidate→verified→active）
- **模型韧性**：GLM 主 → 备用云端 → 本地闲聊三档降级链 + 120s 流超时
- **端侧语音**：SenseVoice ASR + 三层播报链（在线 edge/azure 音色 → 本地 sherpa 流式 → 系统 TTS 兜底）+ eres2net 声纹

### 架构

双进程：Termux 大脑（pi-agent-core + TypeScript）↔ Android 躯体（Kotlin + NanoHTTPD + sherpa-onnx JNI）
HTTP RPC `0.0.0.0:8765` + Bearer token 鉴权 + 幂等缓存 + 事件流。

### 安全特性

- `ActionGate`：全包含节点坐标校验（防父容器遮挡绕过）
- `SensitiveSessionTracker`：真实前台绑定 + nonce 单次消费 + 前台切换失效
- `HITL`：服务端规范化通知文案（模型不能伪造确认界面）
- 证据闸门：存在性 + 新颖性 + 相关性三重校验
- 发送前脱敏：身份证/卡号/密码/验证码/余额 → `[REDACTED:类型]`

### 质量

- 103+ 项自动化测试全绿（brain 18 + body 60 + 契约镜像 25）
- GitHub Actions CI（push/PR 自动跑 brain + body 测试）
- 契约镜像测试（brain types.ts ↔ body Protocol.kt，防跨端漂移）
- 性能基准脚本（perceive/RPC/health/skill.search/events/并发 六项指标）

### 文档

- docs/00-13 完整文档体系（导读/市场/可行性/选型/架构/FR/接口/NFR/ADR/计划/审计/UX/分工）
- README / CONTRIBUTING / CHANGELOG 全面对齐代码现状
