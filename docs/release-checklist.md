# v0.1.0 Release Checklist

## 前置条件（全部满足才能 tag）

- [x] 全部 P0 FR 代码完成（docs/06 ✅ 或 ◐）
- [x] 流水线全绿（brain 18+integration 6+body 60+契约 25=109 项）
- [x] CI 四层通过（typecheck+contract+smoke+integration）
- [x] 安全审计闭环（GPT sol 13+DeepSeek 8+U2 12+UX+DS=40+ 项）
- [x] TC-01~05 ✅（安全/红线）
- [x] TC-07 文本模式 ✅
- [x] TC-08 ✅（证据防谎报）
- [x] TC-09 ✅（技能回放）
- [x] TC-10 ✅（状态机）
- [x] TC-11 ✅（ASR 20/20 100%）
- [x] TC-14 ✅（断点续跑）
- [x] G5 交互绿 ◐（用户确认通过）
- [x] 文档全对齐（README/CONTRIBUTING/CHANGELOG/docs 00-14/release notes）
- [ ] **TC-12 TTS 人评通过**（等 DS-012 修复复验）
- [ ] TC-13 声纹真人贴麦复测（可选——enroll ✅）
- [ ] P4 语音版 TC-07（等 TTS + microphone 前台类型）
- [ ] 性能基准实测（等设备空闲）
- [ ] v1.0 验收报告终版（等人评数据填入）

## Tag 步骤（TC-12 通过后执行）

```bash
# 1. 确认全部测试通过
cd brain && npm run typecheck && npm run contract && npm run smoke && npm run integration
cd ../body && ./gradlew :common:test :core:testDebugUnitTest :app:assembleDebug

# 2. 更新版本号（可选）
# body/app/build.gradle.kts: versionName = "0.1.0"

# 3. 打 tag
git tag -a v0.1.0 -m "P0 最小闭环：真机端到端+安全架构+悬浮交互+技能飞轮+端侧语音"
git push origin v0.1.0

# 4. GitHub Release（gh CLI 或手动）
gh release create v0.1.0 --title "v0.1.0 - P0 最小闭环" --notes-file docs/release-notes-v0.1.0.md
```

## 已知限制（release notes 中注明）

- TTS 流式回调需 sherpa 侧修复（当前用全量生成，首包 ~300-500ms）
- 华为设备可能杀后台进程（看门狗+heartbeat 检测，但无法绕过系统限制）
- 声纹 identify 需真人贴麦（扬声器重放频响损失影响匹配）
- 视觉 L1 需 SAW+MediaProjection 授权（未授权自动回退 a11y）
