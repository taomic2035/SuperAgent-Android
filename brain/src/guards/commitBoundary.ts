/**
 * 提交边界词表（docs/07 §2.2 v2）。
 * 短语精确包含，仅多字短语，避免误伤"支付宝/付款码/支付方式说明"。
 */
const COMMIT_PHRASES = [
  "立即支付", "确认支付", "立即付款", "确认付款",
  "提交订单", "确认下单", "立即下单",
  "支付密码", "验证码支付", "指纹支付", "面容支付", "免密支付",
  "输密码", "确认收货",
]

const SENSITIVE_NAV_PHRASES = [
  "去支付", "去结算", "收银台",
]

export function isCommitBoundary(label: string): boolean {
  const normalized = label.replace(/\s+/g, "")
  return COMMIT_PHRASES.some((t) => normalized.includes(t))
}

export function isSensitiveContext(label: string): boolean {
  const normalized = label.replace(/\s+/g, "")
  return SENSITIVE_NAV_PHRASES.some((t) => normalized.includes(t)) || isCommitBoundary(normalized)
}