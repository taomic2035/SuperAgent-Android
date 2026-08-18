const PAYMENT_TERMS = [
  "支付",
  "付款",
  "立即支付",
  "确认支付",
  "去支付",
  "收银台",
  "输密码",
  "验证码支付",
  "支付密码",
  "指纹支付",
  "面容支付",
  "免密支付",
]

export function checkPaymentTarget(label: string): boolean {
  const normalized = label.replace(/\s+/g, "")
  return PAYMENT_TERMS.some((t) => normalized.includes(t))
}