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
}