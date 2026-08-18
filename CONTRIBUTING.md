# 贡献指南

欢迎参与 SuperAgent-Android。本指南确保协作不破坏架构一致性。

## 1. 文档是事实源

任何代码变更若涉及以下方面，**必须先改文档再改代码**：

| 变更类型 | 改哪个文档 |
|----------|-----------|
| 接口（RPC 方法/参数/返回/错误码） | [docs/07](docs/07-接口规格说明书.md) + 升 protocolVersion |
| 需求（新增/修改功能、优先级） | [docs/06](docs/06-功能规格清单与追踪矩阵.md) |
| 架构（拓扑/安全模型/状态机/ADR） | [docs/05](docs/05-架构设计与移交基线-v2.md) |
| 质量（性能预算/验收标准） | [docs/08](docs/08-非功能需求与验收测试计划.md) |

不一致时裁决规则见 [docs/06 §6](docs/06-功能规格清单与追踪矩阵.md)。

## 2. 开发流程

```bash
# 大脑
cd brain && npm install
npm run typecheck   # 必须零错误
npm run smoke       # 11 项冒烟必须全绿

# 躯体
cd body
./gradlew :common:test        # 6 项单测必须全绿
./gradlew :app:assembleDebug  # 必须成功
```

PR 前确保以上全部通过。

## 3. 代码约定

- **不加注释**（除非逻辑极度非直觉）；命名自解释
- brain：TypeScript strict，import 带 `.ts` 扩展（`allowImportingTsExtensions`）
- body：Kotlin，`jvmTarget = 17`，package 按 `com.superagent.{common|body.core|body}`
- 第三方资产只入 `third/`，工程内相对路径引用，不拷贝进源码树
- 禁止提交：`local.properties`、`node_modules/`、`build/`、`.so` 二进制、API key

## 4. 安全红线

- 支付/提交边界逻辑改动需附带测试用例（参考 [docs/08 TC-04/TC-05](docs/08-非功能需求与验收测试计划.md)）
- 不得引入硬编码 token/密钥；调试默认值仅限 `BuildConfig.DEBUG`
- 不得削弱 body 侧硬拦截（R2 权威），brain 侧守卫是辅助不是依赖

## 5. 提交信息

```
<类型>: <描述>

类型: feat | fix | docs | refactor | test | chore
```

示例：`feat: 技能四态生命周期状态机`、`fix: BodyServer Main 线程死锁`。

## 6. 分支

- `main`：稳定基线
- `wp<N>-<短描述>`：工作包分支（如 `wp0-security-closure`）

## 7. 致谢原则

本项目融合 Pi / sherpa-onnx / Clowder / MiraAgent 四个先行者。引用其设计时注明来源；复用其代码时遵守各自协议（均 MIT）。
