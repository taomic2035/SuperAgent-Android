import type { AgentMessage } from "@earendil-works/pi-agent-core"

/**
 * 上下文窗口管理（P1 技术债兑现）：agent.state.messages 无限增长——长会话/多轮任务
 * tokens 膨胀（GLM 计费+上限风险）。transformContext 纯函数裁剪：
 * - 条数窗口：超 CONTEXT_MAX 条保留最近 CONTEXT_KEEP 条（更早的截断——完整事实已在
 *   runState + runs 表留档，断点续跑用 buildResumeContext 重建摘要，上下文内丢失可接受）
 * - 单条截断：超长 tool result（如 perceive.screen 的 nodes 大 JSON）截尾加标记
 *
 * 防旧坑（f4d5dd0 前科）：本函数绝不引用 agent 实例——纯 (messages) => messages，
 * 无闭包状态，杜绝循环引用导致的初始化顺序问题。
 */

const CONTEXT_MAX = 80
const CONTEXT_KEEP = 60
const SINGLE_MSG_MAX_CHARS = 12_000

function textOf(m: AgentMessage): string {
  // AgentMessage 内容形态随 role 变化，取字符串字段统一估长（防御式：非字符串按空）
  const c = (m as { content?: unknown }).content
  return typeof c === "string" ? c : ""
}

/** 单条截断：超长 tool result / 文本截尾（不破坏消息结构，仅缩短内容）。 */
function truncateSingle(m: AgentMessage): AgentMessage {
  const text = textOf(m)
  if (text.length <= SINGLE_MSG_MAX_CHARS) return m
  const marker = `\n…[上下文管理：原文 ${text.length} 字符，截断至 ${SINGLE_MSG_MAX_CHARS}]`
  return { ...m, content: text.slice(0, SINGLE_MSG_MAX_CHARS) + marker } as AgentMessage
}

/** pi transformContext 钩子：条数窗口 + 单条截断（幂等，失败原样返回——fail-open）。 */
export async function compactContext(messages: AgentMessage[]): Promise<AgentMessage[]> {
  try {
    const truncated = messages.map(truncateSingle)
    if (truncated.length <= CONTEXT_MAX) return truncated
    const dropped = truncated.length - CONTEXT_KEEP
    // 折叠行替代被丢弃的消息段，保住"发生过什么"的最小线索（不逐条摘要——事实源在 runs 表）
    const fold: AgentMessage = {
      role: "user",
      content: `【上下文管理】此前 ${dropped} 条消息已收起（完整记录在任务档案），请基于最近消息继续。`,
    } as AgentMessage
    return [fold, ...truncated.slice(-CONTEXT_KEEP)]
  } catch {
    return messages
  }
}
