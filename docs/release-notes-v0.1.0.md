# SuperAgent-Android v0.1.0 Release Notes

**首个 P0 里程碑版本**（2026-08-20）：真机端到端闭环 + 安全架构 + 悬浮交互层 + 技能飞轮 + 三层语音播报。

P0 六里程碑：M0 安全绿 ✅ / M1 红线绿 ✅ / M2 闭环绿 ✅ / M3 飞轮绿 ✅ / G5 交互绿 ✅ / M4 语音绿 ✅（分别提前 7~42 天）；M5 工程绿 ◐（v1.0-rc，110+ 项测试 + CI 四层）。

## 核心能力

- **端到端闭环**：文字/语音指令 → 云端模型规划（v0.1.0 历史验证 GLM-4.6v）→ 操控真机 → 证据核验 → 技能固化 → 回放（TC-07 美团 15 步全 located）
- **安全铁律**：提交边界（词表+坐标+敏感会话三闸）、HITL nonce 一次性消费、ActionExecutor 统一收口
- **感知阶梯**：L0 无障碍树 → L1 截图+VLM（坐标自动换算）→ L2 auto 路由
- **悬浮交互**：侧边控制球 + 穿透状态条 + 输入面板 + 暂停/继续/停止 + OFFLINE 心跳
- **技能飞轮**：learn → run（签名步进校验）→ feedback（candidate→verified→active）
- **模型韧性**：GLM 主 → 备用云端 → 本地闲聊三档降级链 + 120s 流超时
- **语音**：SenseVoice ASR（TC-11 人评 20/20 100%）+ 三层播报链（在线 edge/azure 音色 → 本地 sherpa 流式 → 系统 TTS 兜底；真机端到端 1.0-1.5s，播放中可打断，零中间文件）+ eres2net 声纹

## 架构

双进程：Termux 大脑（pi-agent-core + TypeScript）↔ Android 躯体（Kotlin + NanoHTTPD + sherpa-onnx JNI）
HTTP RPC `0.0.0.0:8765` + Bearer token 鉴权 + 幂等缓存 + 事件流。

## 安全特性

- `ActionGate`：全包含节点坐标校验（防父容器遮挡绕过）
- `SensitiveSessionTracker`：真实前台绑定 + nonce 单次消费 + 前台切换失效
- `HITL`：服务端规范化通知文案（模型不能伪造确认界面）
- 证据闸门：存在性 + 新颖性 + 相关性三重校验
- 发送前脱敏：身份证/卡号/密码/验证码/余额 → `[REDACTED:类型]`

## 质量

- 测试：brain 18 项（typecheck/契约/smoke/集成/TTS 链）+ body 56 项 + 契约镜像 25 类型 = 110+ 项全绿
- CI：GitHub Actions 四层（typecheck → 契约 → 冒烟+集成 → body 单测）
- 真机验收：TC-01~14 全 PASS 或按计划留 P4（人评项 TC-11/12 已过）

## 已知限制（P1 处理）

- sherpa-onnx 离线 TTS 在部分华为真机构造失败（DS-015/017）——离线播报暂走系统 TTS（音质一般）；在线链不受影响
- KWS 常驻唤醒、视觉感知全量落地、Play 上架为 P1+ 范围
- TC-13 声纹 identify 需真人复测；P4 语音版 TC-07 需用户在场

## 安装与运行

模型与原生库不入库（体积策略）：`scripts/fetch-models.sh` 拉取 → Android Studio 打开 `body/` 构建 → Termux 部署 brain（`scripts/deploy-brain.sh`）。详见 `docs/00` 导读。
