export interface RpcRequest {
  id: number
  method: string
  params: unknown
  idempotencyKey?: string
}

export type RpcResponse =
  | { id: number; ok: true; result: unknown }
  | { id: number; ok: false; error: { code: string; message: string; reason?: string } }

export interface BodyEvent {
  seq: number
  type: "state" | "hitl" | "voice" | "sensor" | "log"
  payload: unknown
}

export interface HealthStatus {
  ok: boolean
  bootId: string
  protocolVersion: number
  uptimeMs: number
  services: Record<string, boolean>
}

export interface ActionResult {
  located: boolean
  signature?: string
  note?: string
}

export interface A11yNode {
  label: string
  clickable: boolean
  selected?: boolean
  sensitive?: boolean
  bounds: { left: number; top: number; right: number; bottom: number }
}

export interface Mark {
  index: number
  text: string
  center: { x: number; y: number }
}

export interface ScreenResult {
  signature: string
  kind: "a11y" | "vision" | "ocr"
  blank: boolean
  nodes?: A11yNode[]
  marks?: Mark[]
  pageTexts?: string[]
  appPackage?: string
  sensitiveSession?: boolean
  /** 视觉感知（L1）截图引用：GET /blob/{ref} 取 JPEG，brain 侧送 VLM 识别 marks */
  screenshotRef?: string
}

export interface AsrResult {
  text: string
  confidence: number
  durationMs: number
}

export interface SayResult {
  route: string
}

export interface VoiceprintEnrollResult {
  speaker: string
  samples: number
}

export interface VoiceprintIdentifyResult {
  speaker?: string
  confidence: number
}

export interface SensorResult {
  type: string
  value: number
  timestamp: number
}

export interface HeadsetResult {
  connected: boolean
  type: "wired" | "bluetooth" | "none"
}

export interface SkillMeta {
  name: string
  description: string
  appPackage: string
  tags: string[]
}

export interface SkillListResult {
  skills: SkillMeta[]
}

export interface SkillSearchHit {
  skill: SkillMeta
  score: number
}

export interface SkillSearchResult {
  hits: SkillSearchHit[]
}

export interface SkillRunResult {
  result: "success" | "stale" | "sensitive_handoff"
  completedSteps: number
}

export interface SkillLearnResult {
  slug: string
}

export interface HitlConfirmResult {
  approved: boolean
}

export interface HitlAskResult {
  answer: string
}

export interface HitlHandoffResult {
  taken: boolean
}

export interface TraceStep {
  tool: string
  args: Record<string, unknown>
  located: boolean
  signature?: string
  timestamp: number
  /** 该步是否涉及敏感操作（HITL 确认过 / 支付红线停手），供技能学习识别 sensitive 步。 */
  sensitive?: boolean
  /** 结果分类：ok / not_located / handoff / finish_rejected / need_human / recover。 */
  resultKind?: string
}

/** UI-0 事件回灌（docs/12 §7 UX 最低契约）：brain → body → 悬浮层，类型化封闭契约。 */
export interface BrainEvent {
  taskId: string
  seq: number
  /** 有限枚举（docs/12 §6）：prompt_start/act/act_done/hitl_wait/blocked/finish/error */
  state: string
  stepIndex?: number
  displayText: string
  requiresUser: string
  resultKind?: string
  timestamp: number
}