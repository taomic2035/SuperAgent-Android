import type { ScreenResult } from "../ipc/types.ts"

function normalize(text: string): string {
  return text.replace(/\s+/g, "").toLowerCase()
}

export interface EvidenceVerdict {
  ok: boolean
  reason: string
}

function collectTexts(screen: ScreenResult): string[] {
  const texts: string[] = []
  if (screen.pageTexts) texts.push(...screen.pageTexts)
  if (screen.marks) texts.push(...screen.marks.map((m) => m.text))
  if (screen.nodes) texts.push(...screen.nodes.map((n) => n.label))
  return texts.map(normalize).filter((t) => t.length > 0)
}

export function verifyEvidence(
  current: ScreenResult,
  baseline: ScreenResult | undefined,
  evidence: string,
): EvidenceVerdict {
  const ev = normalize(evidence)
  if (ev.length < 2) {
    return { ok: false, reason: "证据文字过短（<2 字），请给出屏幕上真实可见的具体文字" }
  }
  const currentTexts = collectTexts(current)
  if (currentTexts.length === 0) {
    return { ok: false, reason: "当前屏幕无可读文字（可能白屏或不可识别），请重新感知" }
  }
  const exactHit = currentTexts.some((t) => t.includes(ev))
  const lineHit = currentTexts.some((t) => {
    const overlap = ev.length > 0 ? ev.split("").filter((ch, i) => t[i] === ch).length / ev.length : 0
    return overlap >= 0.5
  })
  if (!exactHit && !lineHit) {
    return { ok: false, reason: `屏幕上没有找到证据「${evidence}」，禁止谎报完成` }
  }
  if (baseline) {
    const baselineTexts = collectTexts(baseline)
    if (baselineTexts.some((t) => t.includes(ev))) {
      return { ok: false, reason: `证据「${evidence}」在任务开始前就已存在（可能是旧状态），需要新的证明` }
    }
  }
  return { ok: true, reason: "" }
}