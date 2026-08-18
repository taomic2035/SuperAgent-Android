import type { SkillMeta } from "../ipc/types.ts"

export function tokenize(text: string): string[] {
  const tokens: string[] = []
  const lower = text.toLowerCase()
  let buffer = ""
  for (const ch of lower) {
    if (/[\u4e00-\u9fff]/.test(ch)) {
      if (buffer) {
        tokens.push(buffer)
        buffer = ""
      }
      tokens.push(ch)
    } else if (/[a-z0-9]/.test(ch)) {
      buffer += ch
    } else {
      if (buffer) {
        tokens.push(buffer)
        buffer = ""
      }
    }
  }
  if (buffer) tokens.push(buffer)
  const grams = new Set<string>()
  for (let i = 0; i < tokens.length; i++) {
    grams.add(tokens[i])
    if (i > 0 && /[\u4e00-\u9fff]/.test(tokens[i - 1]) && /[\u4e00-\u9fff]/.test(tokens[i])) {
      grams.add(tokens[i - 1] + tokens[i])
    }
  }
  return [...grams]
}

function cosine(a: Map<string, number>, b: Map<string, number>): number {
  let dot = 0
  let normA = 0
  let normB = 0
  for (const v of a.values()) normA += v * v
  for (const v of b.values()) normB += v * v
  for (const [k, v] of a) {
    const other = b.get(k)
    if (other !== undefined) dot += v * other
  }
  if (normA === 0 || normB === 0) return 0
  return dot / Math.sqrt(normA * normB)
}

interface IndexedSkill extends SkillMeta {
  docFreq: Map<string, number>
  norm: number
}

export class SkillIndex {
  private readonly skills: IndexedSkill[] = []

  rebuild(list: SkillMeta[]): void {
    this.skills.length = 0
    const docs = list.map((s) => ({ skill: s, tokens: tokenize(`${s.name} ${s.description} ${s.tags.join(" ")}`) }))
    const df = new Map<string, number>()
    for (const d of docs) {
      for (const t of new Set(d.tokens)) df.set(t, (df.get(t) ?? 0) + 1)
    }
    const total = docs.length || 1
    for (const d of docs) {
      const tf = new Map<string, number>()
      for (const t of d.tokens) tf.set(t, (tf.get(t) ?? 0) + 1)
      const tfidf = new Map<string, number>()
      let norm = 0
      for (const [t, f] of tf) {
        const w = f * Math.log((total + 1) / ((df.get(t) ?? 0) + 1))
        tfidf.set(t, w)
        norm += w * w
      }
      this.skills.push({ ...d.skill, docFreq: tfidf, norm: Math.sqrt(norm) })
    }
  }

  retrieve(query: string, threshold = 0.30): { skill: SkillMeta; score: number }[] {
    const q = tokenize(query)
    const qf = new Map<string, number>()
    for (const t of q) qf.set(t, (qf.get(t) ?? 0) + 1)
    const results: { skill: SkillMeta; score: number }[] = []
    for (const s of this.skills) {
      let dot = 0
      for (const [t, v] of qf) {
        const other = s.docFreq.get(t)
        if (other !== undefined) dot += v * other
      }
      if (s.norm === 0) continue
      const score = dot / s.norm
      if (score >= threshold) results.push({ skill: s, score })
    }
    return results.sort((a, b) => b.score - a.score)
  }
}