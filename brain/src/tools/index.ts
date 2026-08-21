import { Type } from "typebox"
import type { AgentTool } from "@earendil-works/pi-agent-core"
import { getRun, setBaseline, finishRun, noteFinishRejected, markFinishVerified } from "../runState.ts"
import { markFinishRejected } from "../guards/index.ts"
import { verifyEvidence } from "../guards/finish.ts"
import type { RelevanceCheck } from "../guards/relevance.ts"
import type { VisionMarksFn } from "../guards/vision.ts"
import { BodyRpcError } from "../ipc/client.ts"
import type { BodyClient } from "../ipc/client.ts"
import type {
  ActionResult,
  AsrResult,
  HeadsetResult,
  HitlAskResult,
  HitlConfirmResult,
  HitlHandoffResult,
  MemorySearchResult,
  MemoryWriteResult,
  SayResult,
  ScreenResult,
  SensorResult,
  SkillLearnResult,
  SkillListResult,
  SkillRunResult,
  VoiceprintEnrollResult,
  VoiceprintIdentifyResult,
} from "../ipc/types.ts"
import type { Persona } from "../personas/promptBuilder.ts"
import type { Reflector } from "../memory/reflect.ts"
import { commitBoundaryLesson, recordLesson, skillStaleLesson } from "../memory/lessons.ts"
import { redactScreen, redactText } from "../guards/redact.ts"
import { speak } from "../tts/index.ts"

function idem(tool: string, toolCallId: string): string {
  return `${tool}-${toolCallId}`
}

/** 客户端超时必须 > body 侧对应 handler 超时（BodyCore/BodyServer 常量），否则 body 还在等 brain 已判死。 */
const HITL_RPC_TIMEOUT_MS = 90_000
const SPEECH_RPC_TIMEOUT_MS = 75_000
const SKILL_RUN_RPC_TIMEOUT_MS = 150_000

export function buildTools(
  body: BodyClient,
  personas: Record<string, Persona>,
  relevance?: RelevanceCheck,
  vision?: VisionMarksFn,
  reflect?: Reflector,
): AgentTool<any>[] {
  const personaMap = new Map(Object.entries(personas))

  return [
    {
      name: "perceive.screen",
      label: "感知屏幕",
      description: "获取当前屏幕状态：界面元素、文字、页面签名。mode=auto 自动选路（a11y/视觉/OCR），可强制指定。vision 模式返回截图识别的可交互元素（适合 WebView/游戏等无障碍覆盖差的页面）。",
      parameters: Type.Object({
        mode: Type.Optional(Type.Union([Type.Literal("auto"), Type.Literal("a11y"), Type.Literal("vision"), Type.Literal("ocr")])),
      }),
      execute: async (_id, params: any) => {
        const screen = await body.rpc<ScreenResult>("perceive.screen", { mode: params.mode ?? "auto" })
        setBaseline(screen)
        let enriched = screen
        // 感知 L1：body 已给截图引用且有识别器 → 取图送 VLM，marks 并入（识别失败 fail-open 留空）。
        // 坐标换算：VLM 返回截图像素（body 长边 1600），control.tap 用屏幕像素（1152×2256）。
        // 因子 = 屏幕 / 截图（body ScreenshotService scale=1600/max(w,h) → 竖屏 2256/1600=1.41）。
        if (screen.screenshotRef && vision) {
          try {
            const buf = await body.blob(screen.screenshotRef)
            const marks = await vision(buf.toString("base64"))
            if (marks.length) {
              const SCREEN_W = 1152
              const SCREEN_H = 2256
              const SHOT_LONG_EDGE = 1600
              const scale = SCREEN_H / SHOT_LONG_EDGE // 竖屏因子（横屏时用 SCREEN_W/SHOT_LONG_EDGE）
              enriched = {
                ...screen,
                marks: marks.map((m) => ({
                  ...m,
                  center: { x: Math.round(m.center.x * scale), y: Math.round(m.center.y * scale) },
                })),
                pageTexts: marks.map((m) => m.text),
              }
            }
          } catch {
            enriched = screen // 取图/识别失败：保留 body 原始结果
          }
        }
        // BR-04.4：raw 只进 runState；进模型上下文的 content 打码（signature/基线/证据核验仍用 raw）
        return { content: [{ type: "text", text: JSON.stringify(redactScreen(enriched)) }], details: { signature: screen.signature } }
      },
    },
    {
      name: "control.tap",
      label: "点击坐标",
      description: "在像素坐标处点击屏幕（与 perceive.screen 的 marks.center 同单位）。",
      parameters: Type.Object({
        x: Type.Number({ description: "像素横坐标" }),
        y: Type.Number({ description: "像素纵坐标" }),
      }),
      execute: async (toolCallId, params: any) => {
        const result = await body.rpc<ActionResult>("control.tap", params, idem("control.tap", toolCallId))
        if (!result.located) throw new Error(result.note ?? "点击未命中任何可交互元素，请重新感知屏幕")
        return { content: [{ type: "text", text: `已点击 (${params.x}, ${params.y})` }], details: result }
      },
    },
    {
      name: "control.longPress",
      label: "长按",
      description: "在像素坐标处长按。",
      parameters: Type.Object({
        x: Type.Number(),
        y: Type.Number(),
        durationMs: Type.Optional(Type.Number()),
      }),
      execute: async (toolCallId, params: any) => {
        const result = await body.rpc<ActionResult>("control.longPress", params, idem("control.longPress", toolCallId))
        if (!result.located) throw new Error(result.note ?? "长按未命中，请重新感知")
        return { content: [{ type: "text", text: "长按完成" }], details: result }
      },
    },
    {
      name: "control.swipe",
      label: "滑动",
      description: "从 (fromX,fromY) 滑到 (toX,toY)，像素坐标。",
      parameters: Type.Object({
        fromX: Type.Number(),
        fromY: Type.Number(),
        toX: Type.Number(),
        toY: Type.Number(),
        durationMs: Type.Optional(Type.Number()),
      }),
      execute: async (toolCallId, params: any) => {
        const result = await body.rpc<ActionResult>("control.swipe", params, idem("control.swipe", toolCallId))
        return { content: [{ type: "text", text: "滑动完成" }], details: result }
      },
    },
    {
      name: "control.typeText",
      label: "输入文本",
      description: "向当前聚焦的输入框输入文本。",
      parameters: Type.Object({
        text: Type.String(),
      }),
      execute: async (toolCallId, params: any) => {
        const result = await body.rpc<ActionResult>("control.typeText", params, idem("control.typeText", toolCallId))
        if (!result.located) throw new Error(result.note ?? "没有可用的输入框")
        return { content: [{ type: "text", text: "输入完成" }], details: result }
      },
    },
    {
      name: "control.selectOption",
      label: "按文字点选",
      description: "按可见文字定位并点击（label 为屏幕上的可见文字；near 为同文多匹配时就近消歧的参考坐标）。",
      parameters: Type.Object({
        label: Type.String({ description: "目标可见文字" }),
        near: Type.Optional(Type.Object({ x: Type.Number(), y: Type.Number() })),
      }),
      execute: async (toolCallId, params: any) => {
        let result: ActionResult
        try {
          result = await body.rpc<ActionResult>("control.selectOption", params, idem("control.selectOption", toolCallId))
        } catch (err) {
          if (err instanceof BodyRpcError && err.code === "COMMIT_BOUNDARY") {
            // ME-3a：gate 拦截 → lesson 自动采集（fire-and-forget，不吞原错误语义）
            const draft = commitBoundaryLesson(params.label, Boolean(err.nonce))
            void recordLesson(body, draft.topic, draft.content)
            if (err.nonce) {
              throw new Error(
                `敏感会话拦截（nonce=${err.nonce}）。调用 hitl.confirm 时把 nonce="${err.nonce}" 传入 nonce 参数；` +
                "用户同意后重试本动作即可放行。通知文案由服务端生成，你不需要自己写确认文案。",
              )
            }
          }
          throw err
        }
        if (!result.located) throw new Error(result.note ?? `未找到可见文字「${params.label}」`)
        return { content: [{ type: "text", text: `已点选「${params.label}」` }], details: result }
      },
    },
    {
      name: "control.selectSpec",
      label: "选择规格",
      description: "按文字定位并点击规格项，并用前后帧像素差异校验选中态（用于选规格/单选/勾选等）。",
      parameters: Type.Object({
        label: Type.String(),
        near: Type.Optional(Type.Object({ x: Type.Number(), y: Type.Number() })),
      }),
      execute: async (toolCallId, params: any) => {
        const result = await body.rpc<ActionResult>("control.selectSpec", params, idem("control.selectSpec", toolCallId))
        if (!result.located) throw new Error(result.note ?? `规格「${params.label}」点选未生效（选中态校验失败）`)
        return { content: [{ type: "text", text: `已选中规格「${params.label}」` }], details: result }
      },
    },
    {
      name: "control.back",
      label: "返回",
      description: "发送系统返回键。",
      parameters: Type.Object({}),
      execute: async (toolCallId) => {
        const result = await body.rpc<ActionResult>("control.back", {}, idem("control.back", toolCallId))
        return { content: [{ type: "text", text: "已返回" }], details: result }
      },
    },
    {
      name: "control.home",
      label: "回桌面",
      description: "回到桌面（Home）。",
      parameters: Type.Object({}),
      execute: async (toolCallId) => {
        const result = await body.rpc<ActionResult>("control.home", {}, idem("control.home", toolCallId))
        return { content: [{ type: "text", text: "已回桌面" }], details: result }
      },
    },
    {
      name: "control.launch",
      label: "启动应用",
      description: "启动（或重置到干净首屏）指定包名的应用。",
      parameters: Type.Object({
        pkg: Type.String(),
      }),
      execute: async (toolCallId, params: any) => {
        const result = await body.rpc<ActionResult>("control.launch", params, idem("control.launch", toolCallId))
        if (!result.located) throw new Error(result.note ?? `启动 ${params.pkg} 失败`)
        return { content: [{ type: "text", text: `已启动 ${params.pkg}` }], details: result }
      },
    },
    {
      name: "speech.asr",
      label: "语音识别",
      description: "开始录音并识别用户语音（静音 1.2s 自动结束），返回识别文本。",
      parameters: Type.Object({}),
      execute: async () => {
        const result = await body.rpc<AsrResult>("speech.asr", {}, undefined, SPEECH_RPC_TIMEOUT_MS)
        return { content: [{ type: "text", text: `识别结果：${result.text}` }], details: result }
      },
    },
    {
      name: "speech.say",
      label: "语音播报",
      description: "用指定角色的音色播报文本。persona 可选：assistant / momo。",
      parameters: Type.Object({
        text: Type.String(),
        persona: Type.Optional(Type.String()),
      }),
      execute: async (_id, params: any) => {
        const p = personaMap.get(params.persona ?? "") ?? personas.assistant
        // BD-04 三层链：在线 edge/azure（音质优先）→ 本地 sherpa → 系统 TTS（speak 内部降级）
        const outcome = await speak(body, params.text, { bodyVoice: p.voice, edgeVoice: p.voice.edgeVoice })
        const via = outcome.via === "online" ? `在线（${outcome.provider}${outcome.synthMs ? ` 合成${outcome.synthMs}ms` : " 缓存"}）` : "本地"
        return { content: [{ type: "text", text: `已播报（${via}）` }], details: outcome.result }
      },
    },
    {
      name: "speech.interrupt",
      label: "停止播报",
      description: "立即停止当前语音播报。",
      parameters: Type.Object({}),
      execute: async () => {
        await body.rpc("speech.interrupt", {})
        return { content: [{ type: "text", text: "已停止播报" }], details: {} }
      },
    },
    {
      name: "speech.voiceprintEnroll",
      label: "声纹注册",
      description: "录制用户语音并注册声纹（需用户连续说话数秒）。",
      parameters: Type.Object({
        name: Type.String({ description: "说话人名字" }),
      }),
      execute: async (_id, params: any) => {
        const result = await body.rpc<VoiceprintEnrollResult>("speech.voiceprintEnroll", params, undefined, SPEECH_RPC_TIMEOUT_MS)
        return { content: [{ type: "text", text: `已注册声纹 ${result.speaker}（${result.samples} 条样本）` }], details: result }
      },
    },
    {
      name: "speech.voiceprintIdentify",
      label: "声纹识别",
      description: "录制短语音并判断说话人身份。",
      parameters: Type.Object({}),
      execute: async () => {
        const result = await body.rpc<VoiceprintIdentifyResult>("speech.voiceprintIdentify", {}, undefined, SPEECH_RPC_TIMEOUT_MS)
        return { content: [{ type: "text", text: result.speaker ? `说话人：${result.speaker}` : "无法识别说话人" }], details: result }
      },
    },
    {
      name: "hardware.audioRoute",
      label: "音频路由",
      description: "切换音频输出：earpiece 听筒 / speaker 扬声器 / headset 耳机 / auto 自动。",
      parameters: Type.Object({
        target: Type.Union([Type.Literal("earpiece"), Type.Literal("speaker"), Type.Literal("headset"), Type.Literal("auto")]),
      }),
      execute: async (_id, params: any) => {
        const result = await body.rpc<SayResult>("hardware.audioRoute", params)
        return { content: [{ type: "text", text: `音频已切到 ${result.route}` }], details: result }
      },
    },
    {
      name: "hardware.vibrate",
      label: "振动",
      description: "按波形振动（pattern 为毫秒序列，如 [0,200,100,200]）。",
      parameters: Type.Object({
        pattern: Type.Array(Type.Number()),
        repeat: Type.Optional(Type.Number()),
      }),
      execute: async (_id, params: any) => {
        await body.rpc("hardware.vibrate", params)
        return { content: [{ type: "text", text: "已振动" }], details: {} }
      },
    },
    {
      name: "hardware.sensor",
      label: "读取传感器",
      description: "读取设备传感器：proximity 接近 / light 光线 / step 步数 / motion 运动状态。",
      parameters: Type.Object({
        type: Type.Union([Type.Literal("proximity"), Type.Literal("light"), Type.Literal("step"), Type.Literal("motion")]),
      }),
      execute: async (_id, params: any) => {
        const result = await body.rpc<SensorResult>("hardware.sensor", params)
        return { content: [{ type: "text", text: `${params.type}: ${result.value}` }], details: result }
      },
    },
    {
      name: "hardware.headset",
      label: "耳机状态",
      description: "查询耳机是否插入。",
      parameters: Type.Object({}),
      execute: async () => {
        const result = await body.rpc<HeadsetResult>("hardware.headset", {})
        return { content: [{ type: "text", text: `耳机：${result.connected ? result.type : "未插入"}` }], details: result }
      },
    },
    {
      name: "skill.list",
      label: "技能列表",
      description: "列出已固化的技能。",
      parameters: Type.Object({}),
      execute: async () => {
        const result = await body.rpc<SkillListResult>("skill.list", {})
        return { content: [{ type: "text", text: JSON.stringify(result.skills) }], details: result }
      },
    },
    {
      name: "skill.run",
      label: "执行技能",
      description: "按名称执行已固化技能（app 专属）。执行敏感步骤前会停手转人工。",
      parameters: Type.Object({
        name: Type.String({ description: "技能名（必须是 skill.list 中的名字，不得自造）" }),
        args: Type.Optional(Type.Record(Type.String(), Type.String())),
      }),
      execute: async (toolCallId, params: any) => {
        let result: SkillRunResult
        try {
          result = await body.rpc<SkillRunResult>("skill.run", params, idem("skill.run", toolCallId), SKILL_RUN_RPC_TIMEOUT_MS)
        } catch (err) {
          // BD-07.3 Recovery mode：失配上下文透传给模型，从失配处续走而非从头重来
          if (err instanceof BodyRpcError && err.code === "SKILL_STALE") {
            // ME-3a：技能失配 → lesson 自动采集（fire-and-forget）
            const draft = skillStaleLesson(params.name, err.message)
            void recordLesson(body, draft.topic, draft.content)
            throw new Error(
              `技能 ${params.name} 回放失配（${err.message}）。已完成的步骤无需重做：先 perceive.screen 确认当前位置，从失配处现场规划继续；` +
                "任务最终成功后 task.finish 会自动重新固化该技能（以新轨迹复活为 candidate）。",
            )
          }
          throw err
        }
        if (result.result === "sensitive_handoff") {
          return { content: [{ type: "text", text: `技能 ${params.name} 在 ${result.completedSteps} 步后遇敏感步骤，已停手转人工接管。` }], details: { result } }
        }
        if (result.result !== "success") {
          throw new Error(`技能 ${params.name} 回放失配（界面已变化），请改为现场规划`)
        }
        return { content: [{ type: "text", text: `技能 ${params.name} 执行完成（${result.completedSteps} 步）。注意：执行完成不代表目标达成，仍需 task.finish 校验。` }], details: { result } }
      },
    },
    {
      name: "skill.feedback",
      label: "技能反馈",
      description: "任务结束后回报技能使用成败，驱动技能状态机（candidate→verified→active→deprecated）。",
      parameters: Type.Object({
        name: Type.String({ description: "技能名" }),
        success: Type.Boolean({ description: "本次使用是否成功" }),
      }),
      execute: async (_id, params: any) => {
        await body.rpc("skill.feedback", params)
        return { content: [{ type: "text", text: `已反馈技能 ${params.name}：${params.success ? "成功" : "失败"}` }], details: {} }
      },
    },
    {
      name: "memory.remember",
      label: "记住",
      description:
        "把用户的稳定事实/偏好/习惯/教训写入长期记忆（用户说「记住…」「以后都…」时必须调用，这是对用户的承诺）。" +
        "kind：fact 事实/preference 偏好/lesson 教训/routine 习惯流程；topic 是归并键（如 奶茶口味）。" +
        "敏感信息（身份证/卡号/密码/验证码）会被拒绝入库。",
      parameters: Type.Object({
        kind: Type.Union([
          Type.Literal("fact"),
          Type.Literal("preference"),
          Type.Literal("lesson"),
          Type.Literal("routine"),
        ]),
        topic: Type.String({ description: "归并键，≤12 字（如 奶茶口味 / 快递）" }),
        content: Type.String({ description: "记忆内容陈述句，≤60 字" }),
      }),
      execute: async (_id, params: any) => {
        // ME 隐私红线（docs/15 §6）：写入前过 redact——打码命中即拒绝入库（不是入库打码版）
        if (redactText(params.content) !== params.content || redactText(params.topic) !== params.topic) {
          throw new Error("内容含敏感信息（身份证/卡号/密码/验证码类），不入库——请换不含敏感信息的表述")
        }
        const result = await body.rpc<MemoryWriteResult>("memory.write", {
          kind: params.kind,
          topic: params.topic,
          content: params.content,
          source: "user-told",
          confidence: 1.0,
        })
        return {
          content: [{ type: "text", text: result.merged ? `已更新记忆「${params.topic}」` : `已记住「${params.topic}」` }],
          details: result,
        }
      },
    },
    {
      name: "memory.search",
      label: "记忆检索",
      description: "检索长期记忆中关于用户的事实/偏好/教训（不确定用户偏好或历史约定时先查这里）。",
      parameters: Type.Object({
        query: Type.String({ description: "检索关键词" }),
        limit: Type.Optional(Type.Number()),
      }),
      execute: async (_id, params: any) => {
        const result = await body.rpc<MemorySearchResult>("memory.search", { query: params.query, limit: params.limit ?? 5 })
        const text = result.hits.length
          ? result.hits
              .map((h) => `- ${h.memory.topic}：${h.memory.content}（${h.memory.kind}，置信${h.memory.confidence.toFixed(1)}）`)
              .join("\n")
          : "（无相关记忆）"
        return { content: [{ type: "text", text }], details: result }
      },
    },
    {
      name: "hitl.confirm",
      label: "请求确认",
      description:
        "敏感操作前请求用户确认。approved=false 表示用户拒绝。" +
        "当动作被 COMMIT_BOUNDARY(sensitive_session) 拒绝且错误中携带 nonce 时：**必须把 nonce 传入本工具的 nonce 参数**（服务端校验一次性消费，不可伪造）。" +
        "nonce 路径下通知文案由服务端生成（规范化），用户看到的就是将要执行的真实动作。" +
        "旧路径（无 nonce 时）把被拦截动作文字传入 action 参数。",
      parameters: Type.Object({
        prompt: Type.String({ description: "向用户展示的确认文案" }),
        action: Type.Optional(Type.String({ description: "被拦截动作的确切文字（旧路径，nonce 缺失时用）" })),
        nonce: Type.Optional(Type.String({ description: "敏感动作被拒时返回的一次性 nonce（AD-10，优先于 action）" })),
      }),
      execute: async (_id, params: any) => {
        const result = await body.rpc<HitlConfirmResult>("hitl.confirm", params, undefined, HITL_RPC_TIMEOUT_MS)
        return { content: [{ type: "text", text: result.approved ? "用户已确认" : "用户拒绝" }], details: result }
      },
    },
    {
      name: "hitl.ask",
      label: "询问用户",
      description: "向用户提问并等待回答（不确定项、需要补充信息时使用）。",
      parameters: Type.Object({
        prompt: Type.String(),
      }),
      execute: async (_id, params: any) => {
        const result = await body.rpc<HitlAskResult>("hitl.ask", params, undefined, HITL_RPC_TIMEOUT_MS)
        return { content: [{ type: "text", text: `用户回答：${result.answer}` }], details: result }
      },
    },
    {
      name: "hitl.handoff",
      label: "转人工",
      description: "遇到红线（支付等）或无法继续时，转人工接管并说明原因。",
      parameters: Type.Object({
        reason: Type.String(),
      }),
      execute: async (_id, params: any) => {
        const result = await body.rpc<HitlHandoffResult>("hitl.handoff", params, undefined, HITL_RPC_TIMEOUT_MS)
        return { content: [{ type: "text", text: `已转人工：${params.reason}` }], details: result }
      },
    },
    {
      name: "task.finish",
      label: "完成任务",
      description: "声明任务完成。evidence 必须是当前屏幕上真实可见的、且不是任务开始时已存在的证明文字（例如「已下单」「已送达」）。内部会重新感知屏幕核验，核验失败会被驳回。",
      parameters: Type.Object({
        summary: Type.String(),
        evidence: Type.String({ description: "屏幕上的证据文字" }),
      }),
      execute: async (_id, params: any) => {
        const run = getRun()
        const screen = await body.rpc<ScreenResult>("perceive.screen", { mode: "auto" })
        const verdict = verifyEvidence(screen, run.baselineScreen, params.evidence)
        if (!verdict.ok) {
          const rejects = noteFinishRejected()
          markFinishRejected(params.evidence, verdict.reason)
          const escalation =
            rejects >= 3
              ? `（已连续 ${rejects} 次证据驳回，疑似无法自证完成——立即 hitl.handoff 转人工，不要再尝试 task.finish）`
              : ""
          throw new Error(`证据核验失败：${verdict.reason}。请先重新感知屏幕，确认任务真正完成再调用。${escalation}`)
        }
        // BR-04.3 相关性软门（fail-open：审查不可达/超时放行；硬门仍是存在性+新颖性）
        if (relevance) {
          const rel = await relevance(run.goal, params.evidence, screen.pageTexts ?? []).catch(() => null)
          if (rel && !rel.ok) {
            const rejects = noteFinishRejected()
            markFinishRejected(params.evidence, `证据与目标不相关：${rel.reason}`)
            const escalation =
              rejects >= 3
                ? `（已连续 ${rejects} 次证据驳回——立即 hitl.handoff 转人工）`
                : ""
            throw new Error(`证据核验失败：证据「${params.evidence}」与任务目标不相关（${rel.reason}）。请寻找与目标直接相关的新证据。${escalation}`)
          }
        }
        let learned: string | undefined
        let learnError: string | undefined
        const locatedSteps = run.trace.filter((s) => s.located)
        if (locatedSteps.length >= 2 && screen.appPackage) {
          try {
            const result = await body.rpc<SkillLearnResult>("skill.learn", {
              goal: run.goal,
              appPackage: screen.appPackage,
              trace: locatedSteps,
            })
            learned = result.slug
          } catch (err) {
            // 固化失败不吞：任务仍算完成，但失败必须可观测（P1 修复——曾静默吞掉 body 序列化 bug）
            learnError = err instanceof Error ? err.message : String(err)
            console.warn(`[brain] skill.learn 固化失败：${learnError}`)
          }
        }
        markFinishVerified()
        finishRun("success")
        // ME-2 反思提取（docs/15 §5）：证据核验通过后异步入库——fire-and-forget，绝不阻塞任务完成
        if (reflect) {
          // G2-05 防御纪律：显式吞 rejection，不依赖全局 unhandledRejection handler（会误写 crashed 终态）
          void reflect({ goal: run.goal, summary: params.summary, tools: locatedSteps.map((s) => s.tool) }).catch(() => undefined)
        }
        return {
          content: [{ type: "text", text: `任务完成：${params.summary}` }],
          details: { evidenceVerified: true, traceSteps: locatedSteps.length, learned, learnError },
        }
      },
    },
  ]
}