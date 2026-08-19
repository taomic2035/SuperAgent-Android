import type { ScreenResult } from "../ipc/types.ts"

/**
 * finish 防幻觉闸门（AD-05，参照 Kestrel FinishEvidence）。
 * 归一化（去空白标点、小写）后：① evidence 整段出现在某行 → 通过；
 * ② 某行出现在 evidence 里（行被 OCR 截断）→ 仅当该行 ≥4 字且覆盖 evidence 至少一半才通过，
 * 防"屏上短词（喜茶）撞中模型编的长声明（已进入喜茶店铺）"。
 */
function normalize(text: string): string {
  return text.replace(/[\s,.、（）()【】\[\]·￥¥:：!！?？"''「」『』_\-/\\@#$%^&*+=|~`<>]+/g, "").toLowerCase()
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
  return texts.map(normalize).filter((t) => t.length >= 2)
}

/** evidence 是否被屏幕上的可见文字支持（存在性核验）。 */
function verifyExists(evidence: string, screen: ScreenResult): boolean {
  const ev = normalize(evidence)
  if (ev.length < 2) return false
  const lines = collectTexts(screen)
  return lines.some((l) => {
    if (l.includes(ev)) return true // 证据整段可见
    if (ev.includes(l)) return l.length >= 4 && l.length * 2 >= ev.length // 行被截断的宽容
    return false
  })
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
  if (!verifyExists(evidence, current)) {
    return { ok: false, reason: `屏幕上没有找到证据「${evidence}」，禁止谎报完成` }
  }
  // 新颖性核验（参照 Kestrel verifyNovel）：证据须在当前屏可见 且 不在开局基线屏上。
  // 防旧状态（残留购物车「差X元起送」）冒充新成果。baseline 缺失时退化为存在性核验。
  if (baseline && verifyExists(evidence, baseline)) {
    return {
      ok: false,
      reason: `证据「${evidence}」在任务开始前就已存在（可能是旧状态），需要这次操作后新出现的证明`,
    }
  }
  return { ok: true, reason: "" }
}