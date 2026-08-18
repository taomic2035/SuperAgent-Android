import { readFileSync } from "node:fs"
import { join, dirname } from "node:path"

interface CommitBoundariesJson {
  commitPhrases: string[]
  sensitiveNavPhrases: string[]
  sensitiveSessionActionVerbs: string[]
  sensitiveUrlPatterns: string[]
  sensitiveAppPrefixes: string[]
}

function loadBoundaries(): CommitBoundariesJson {
  const jsonPath = join(dirname(import.meta.url.replace("file://", "")), "../../../body/common/src/main/resources/commit_boundaries.json")
  try {
    const raw = readFileSync(jsonPath, "utf8")
    return JSON.parse(raw) as CommitBoundariesJson
  } catch {
    return {
      commitPhrases: [
        "立即支付", "确认支付", "立即付款", "确认付款",
        "提交订单", "确认下单", "立即下单",
        "支付密码", "验证码支付", "指纹支付", "面容支付", "免密支付",
        "输密码", "确认收货",
      ],
      sensitiveNavPhrases: ["去支付", "去结算", "收银台"],
      sensitiveSessionActionVerbs: ["确认", "提交", "转账", "发送", "删除", "修改密码", "实名认证"],
      sensitiveUrlPatterns: ["pay", "checkout", "cashier", "收银", "结算", "payment"],
      sensitiveAppPrefixes: [
        "com.chinamworld", "com.ccb", "com.icbc", "com.abchina", "com.bankcomm",
        "com.cmbchina", "com.chinamobile.boce", "com.spdb", "com.cebbank",
        "com.citic", "com.cgb", "com.pab", "com.epay", "com.bankofchina",
        "com.eg.android.AlipayGphone", "com.tencent.mm", "com.unionpay",
        "com.tencent.mobileqq", "com.tencent.qqlive", "com.sina.weibo",
        "com.ss.android.article", "com.netease.mail",
      ],
    }
  }
}

const boundaries = loadBoundaries()

export function isCommitBoundary(label: string): boolean {
  const normalized = label.replace(/\s+/g, "")
  return boundaries.commitPhrases.some((t) => normalized.includes(t))
}

export function isSensitiveContext(label: string): boolean {
  const normalized = label.replace(/\s+/g, "")
  return boundaries.sensitiveNavPhrases.some((t) => normalized.includes(t)) || isCommitBoundary(normalized)
}

export function isSensitiveSessionAction(label: string): boolean {
  const normalized = label.replace(/\s+/g, "")
  return boundaries.sensitiveSessionActionVerbs.some((t) => normalized.includes(t))
}

export function isSensitiveUrl(url: string): boolean {
  const lower = url.toLowerCase()
  return boundaries.sensitiveUrlPatterns.some((t) => lower.includes(t.toLowerCase()))
}

export function isSensitiveApp(pkg: string): boolean {
  return boundaries.sensitiveAppPrefixes.some((prefix) => pkg === prefix || pkg.startsWith(`${prefix}.`))
}

export function getBoundaries(): CommitBoundariesJson {
  return boundaries
}

export { type CommitBoundariesJson }
