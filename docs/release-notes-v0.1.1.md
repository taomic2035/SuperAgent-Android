# SuperAgent-Android v0.1.1 Release Notes

**审计清剿与自进化收口版本**（2026-08-21）：v0.1.0 后 40+ 提交，双 agent（GLM/GPT）协作规约 v2 下交付。

## 核心变更

### 主模型切换：GLM-4.6v → qwen3.7-plus
- 阿里云 MaaS OpenAI 兼容端点（`GLM_BASE_URL/GLM_API_KEY/MODEL` env，零代码改动切换）
- 全链 PC 等价验证三连 PASS：工具执行（真机 launch+perceive 闭环）/ 记忆注入并被模型使用（"我记得你喜欢无糖的奶茶"）/ 中文记住入库
- 视觉模型独立可配：`VISION_BASE_URL/API_KEY/MODEL`（不随主链降级、互不隐式影响）；全库 11 文档去写死

### 架构审计 C 系列 15/15 闭环（docs/16）
- **P0 安全/隐私**：selectOption 最终坐标 typed gate（C-01）、显式 vision 敏感前台 TOCTOU 关闭（C-02）
- **P1 可靠性**：视觉链入口 attach 闭环（C-03）、WebView className 结构化路由（C-04）、run 终态单写者+幂等门+RESUMABLE 策略表（C-05）、**resume 自动断点续跑闭环**（C-06，RunOutcome+paused）、离线输入诚实拒绝（C-07）、记忆注入覆盖 bug（C-08）、记忆三入口 PII 齐平（C-09）、BodyServer 结构化并发+幂等闭环+unknown_side_effect 语义（C-10）
- **P2**：.super-agent 忽略（C-11）、事件串行链防终态丢失（C-12）、PAUSING/STOPPING 生产者与迁移回归（C-13）、快照原子写+checksum+0600（C-14）、文档漂移校准（C-15）

### 自进化（ME 系列 P1 全落地）
- 反思双通道：成功提取用户事实/偏好 + 失败归因 lesson
- 生命周期：90 天衰减 + 容量治理（月度水位触发）
- 进化度量：`evolve-report`（成功率趋势/注入 A/B/lesson 复用/技能复用）+ memoriesInjected 埋点（runs 表 v2 迁移）
- 记忆管理入口：「我的记忆」UI（可查可删——隐私红线兑现）
- 备份恢复：6h 快照（原子写+校验）+ 手动恢复 CLI

### 工程
- 上下文窗口管理（compactContext：条数窗口+单条截断，防 tokens 膨胀）
- 协作规约 v2：claims 认领制 + 交叉复核 + 自动推进闭环（docs/13）
- 测试：brain 43 项四层 + body JVM 168 项 + 契约镜像 34 类型

## 已知限制

- 真机回归清单待设备批次：敏感 App vision、重叠节点、MediaProjection 标定、暂停/继续 UI、kill 后续跑
- sherpa 离线 TTS 根因未解（播报主路在线 edge 不受影响）
- Termux 侧 qwen env 部署确认待 DeepSeek 回填

## 升级指引

模型配置见 SESSION.md（不入库）；`scripts/fetch-models.sh` 拉模型后 Android Studio 构建 body，`scripts/deploy-brain.sh` 部署 brain。
