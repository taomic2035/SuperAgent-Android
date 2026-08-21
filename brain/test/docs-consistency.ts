import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import { resolve } from "node:path"

const root = fileURLToPath(new URL("../../", import.meta.url))
const files = [
  "README.md",
  "docs/05-架构设计与移交基线-v2.md",
  "docs/06-功能规格清单与追踪矩阵.md",
  "docs/14-复盘与P1准备.md",
  "docs/17-当前方案设计.md",
] as const

const contents = new Map(files.map((file) => [file, readFileSync(resolve(root, file), "utf8")]))
const violations: string[] = []

function modelPolicyViolations(line: string): string[] {
  const found: string[] = []
  for (const match of line.matchAll(/\b(?:glm-\d[\w.-]*|qwen\d[\w.-]*|deepseek-[\w.-]+|gpt-\d[\w.-]*)\b/gi)) {
    const start = Math.max(0, match.index! - 24)
    const end = Math.min(line.length, match.index! + match[0].length + 24)
    const context = line.slice(start, end)
    if (!/(?:历史|实证|示例|当前部署)/.test(context)) {
      found.push("model name must be locally labelled as history, evidence, example, or current deployment")
    }
  }
  const binding = line.match(/(?:GLM|Qwen|DeepSeek|GPT)[^|\n]{0,24}(?:主模型|规划模型|视觉模型|连续失败|不可达|限流|自动切换|能力降级)/i)
  if (binding && !/(?:历史|实证|示例|当前部署)/.test(binding[0])) {
    found.push("provider family must not be an architecture component")
  }
  return found
}

assert.ok(modelPolicyViolations("Cloud[DeepSeek-V3 主模型]").length > 0)
assert.ok(modelPolicyViolations("GLM 连续失败 3 次自动切换").length > 0)
assert.deepEqual(modelPolicyViolations("VISION_MODEL=qwen4.0 # 当前部署示例，可替换"), [])
assert.deepEqual(modelPolicyViolations("历史实证 glm-9.0 tool-call"), [])

function rejectPhrase(file: typeof files[number], phrase: string): void {
  const lines = contents.get(file)!.split(/\r?\n/)
  lines.forEach((line, index) => {
    if (line.includes(phrase)) violations.push(`${file}:${index + 1}: stale phrase: ${phrase}`)
  })
}

const staleMatrixPhrases = [
  "selectOption` 最终内部 tap 尚未统一",
  "closed 与注释相反仍可续",
  "task.finish 与 wrapper 双写",
  "OFFLINE 仍假受理",
  "handler 超时不取消后台协程",
  "当前 handler 超时不会取消后台协程",
  "GLM 连续失败",
  "无 GLM key",
  "MainActivity 授权结果未 attach",
  "WebView class 信号未进入 ScreenResult",
  "普通 selectOption 最终 tap 需统一坐标闸",
  "auto 路由信号和显式 vision 前台同步待修",
  "fire-and-forget 顺序、commandId/resolution、PAUSING/STOPPING 生产者待补",
  "revise 未复用 validator",
  "技能命中会覆盖已构造的主人记忆块",
  "快照明文非原子、无 schema/checksum",
] as const

for (const file of files) {
  for (const phrase of staleMatrixPhrases) rejectPhrase(file, phrase)
}

for (const file of files) {
  rejectPhrase(file, "GLM 主模型")
  const lines = contents.get(file)!.split(/\r?\n/)
  lines.forEach((line, index) => {
    for (const rule of modelPolicyViolations(line)) {
      violations.push(`${file}:${index + 1}: ${rule}`)
    }
  })
}

const readme = contents.get("README.md")!
for (const required of [
  "`MODEL`",
  "`VISION_BASE_URL`",
  "`VISION_API_KEY`",
  "`VISION_MODEL`",
  "npm run resume-coordinator",
  "npm run vision-fallback",
]) {
  if (!readme.includes(required)) violations.push(`README.md:1: missing required configurable/verification fact: ${required}`)
}

if (!contents.get("docs/14-复盘与P1准备.md")!.includes("**历史快照**")) {
  violations.push("docs/14-复盘与P1准备.md:1: missing explicit historical snapshot boundary")
}

assert.deepEqual(violations, [], `documentation consistency violations:\n${violations.join("\n")}`)
console.log(`文档一致性检查通过：${files.length} 个事实源，${staleMatrixPhrases.length} 条已关闭缺口规则`)
