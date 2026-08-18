import { readFileSync, existsSync } from "node:fs"
import { join, dirname } from "node:path"

interface CommitBoundariesJson {
  commitPhrases: string[]
  sensitiveNavPhrases: string[]
  sensitiveSessionActionVerbs: string[]
  sensitiveUrlPatterns: string[]
  sensitiveAppPrefixes: string[]
}

const FALLBACK: CommitBoundariesJson = {
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

/**
 * 词表单一主源：body/common/src/main/resources/commit_boundaries.json。
 * 查找顺序：① body 源码树（开发同源，即时反映修改）② brain 资源副本（Termux/部署，
 *   由 `npm run sync-vocab` 从主源同步）③ 硬编码 fallback。
 */
function loadBoundaries(): CommitBoundariesJson {
  const here = dirname(import.meta.url.replace("file://", ""))
  const candidates = [
    join(here, "../../../body/common/src/main/resources/commit_boundaries.json"),
    join(here, "commit_boundaries.json"),
  ]
  for (const p of candidates) {
    try {
      if (existsSync(p)) return JSON.parse(readFileSync(p, "utf8")) as CommitBoundariesJson
    } catch {
      // 继续尝试下一个候选路径
    }
  }
  return FALLBACK
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
