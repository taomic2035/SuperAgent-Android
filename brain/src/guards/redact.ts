import type { ScreenResult } from "../ipc/types.ts"

/**
 * BR-04.4 隐私脱敏：发送给 LLM 前打码（AD-10 双层脱敏的 brain 侧）。
 * 原则：raw 屏只留在 brain 进程内（runState 基线/证据核验/签名），
 * 进模型上下文（perceive.screen 工具 content）一律过 redactScreen。
 * signature 保留原值——它由 body 从 raw marks 计算，脱敏后改变会破坏 ReAct 无进展判定。
 */

/** 关键词→[REDACTED:类型]：命中关键词且含数字的文本整体掩码（"余额: ¥123.45"→[REDACTED:余额]）。 */
const KEYWORD_RULES: Array<[RegExp, string]> = [
  [/密码|口令/, "密码"],
  [/验证码|校验码/, "验证码"],
  [/余额|存款|账单金额/, "余额"],
]

const ID_CARD_RE = /\d{17}[\dXx]/g // 身份证 18 位
const CARD_NO_RE = /\d{16,19}/g // 银行卡 16-19 位（空格分组先归并）

export function redactText(text: string): string {
  let out = text.replace(/(\d)\s+(?=\d)/g, "$1") // 只归并数字之间的空格（"6222 0210…"→连续卡号、"836 290"→连续验证码）
  out = out.replace(ID_CARD_RE, "[REDACTED:身份证]")
  out = out.replace(CARD_NO_RE, "[REDACTED:卡号]")
  for (const [re, kind] of KEYWORD_RULES) {
    if (re.test(out) && /\d/.test(out)) {
      return `[REDACTED:${kind}]`
    }
  }
  return out
}

export function redactScreen(screen: ScreenResult): ScreenResult {
  const { visionActionToken: _visionActionToken, ...publicScreen } = screen
  return {
    ...publicScreen,
    pageTexts: screen.pageTexts?.map(redactText),
    marks: screen.marks?.map((m) => ({ ...m, text: redactText(m.text) })),
    nodes: screen.nodes?.map((n) => ({ ...n, label: redactText(n.label) })),
  }
}
