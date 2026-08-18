import { Type } from "typebox"
import type { AgentTool } from "@earendil-works/pi-agent-core"
import { getRun, setBaseline } from "../runState.ts"
import { verifyEvidence } from "../guards/finish.ts"
import type { BodyClient } from "../ipc/client.ts"
import type {
  ActionResult,
  AsrResult,
  HeadsetResult,
  HitlAskResult,
  HitlConfirmResult,
  HitlHandoffResult,
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

function idem(tool: string, toolCallId: string): string {
  return `${tool}-${toolCallId}`
}

export function buildTools(body: BodyClient, personas: Record<string, Persona>): AgentTool<any>[] {
  const personaMap = new Map(Object.entries(personas))
  const say = (personaName: string | undefined) => personaMap.get(personaName ?? "")?.voice ?? personas.assistant.voice

  return [
    {
      name: "perceive.screen",
      label: "感知屏幕",
      description: "获取当前屏幕状态：界面元素、文字、页面签名。mode=auto 自动选路（a11y/视觉/OCR），可强制指定。",
      parameters: Type.Object({
        mode: Type.Optional(Type.Union([Type.Literal("auto"), Type.Literal("a11y"), Type.Literal("vision"), Type.Literal("ocr")])),
      }),
      execute: async (_id, params: any) => {
        const screen = await body.rpc<ScreenResult>("perceive.screen", { mode: params.mode ?? "auto" })
        setBaseline(screen)
        return { content: [{ type: "text", text: JSON.stringify(screen) }], details: { signature: screen.signature } }
      },
    },
    {
      name: "control.tap",
      label: "点击坐标",
      description: "在归一化坐标 (0-999) 处点击屏幕。",
      parameters: Type.Object({
        x: Type.Number({ description: "归一化横坐标 0-999" }),
        y: Type.Number({ description: "归一化纵坐标 0-999" }),
      }),
      execute: async (toolCallId, params: any) => {
        const result = await body.rpc<ActionResult>("control.tap", params, idem("control.tap", toolCallId))
        if (!result.located) throw new Error("点击未命中任何可交互元素，请重新感知屏幕")
        return { content: [{ type: "text", text: `已点击 (${params.x}, ${params.y})` }], details: result }
      },
    },
    {
      name: "control.longPress",
      label: "长按",
      description: "在归一化坐标处长按。",
      parameters: Type.Object({
        x: Type.Number(),
        y: Type.Number(),
        durationMs: Type.Optional(Type.Number()),
      }),
      execute: async (toolCallId, params: any) => {
        const result = await body.rpc<ActionResult>("control.longPress", params, idem("control.longPress", toolCallId))
        if (!result.located) throw new Error("长按未命中，请重新感知")
        return { content: [{ type: "text", text: "长按完成" }], details: result }
      },
    },
    {
      name: "control.swipe",
      label: "滑动",
      description: "从 (fromX,fromY) 滑到 (toX,toY)，坐标归一化 0-999。",
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
        if (!result.located) throw new Error("没有可用的输入框")
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
        const result = await body.rpc<ActionResult>("control.selectOption", params, idem("control.selectOption", toolCallId))
        if (!result.located) throw new Error(`未找到可见文字「${params.label}」`)
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
        if (!result.located) throw new Error(`规格「${params.label}」点选未生效（选中态校验失败）`)
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
        if (!result.located) throw new Error(`启动 ${params.pkg} 失败`)
        return { content: [{ type: "text", text: `已启动 ${params.pkg}` }], details: result }
      },
    },
    {
      name: "speech.asr",
      label: "语音识别",
      description: "开始录音并识别用户语音（静音 1.2s 自动结束），返回识别文本。",
      parameters: Type.Object({}),
      execute: async () => {
        const result = await body.rpc<AsrResult>("speech.asr", {})
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
        const voice = say(params.persona)
        const result = await body.rpc<SayResult>("speech.say", { text: params.text, voice })
        return { content: [{ type: "text", text: "已播报" }], details: result }
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
        const result = await body.rpc<VoiceprintEnrollResult>("speech.voiceprintEnroll", params)
        return { content: [{ type: "text", text: `已注册声纹 ${result.speaker}（${result.samples} 条样本）` }], details: result }
      },
    },
    {
      name: "speech.voiceprintIdentify",
      label: "声纹识别",
      description: "录制短语音并判断说话人身份。",
      parameters: Type.Object({}),
      execute: async () => {
        const result = await body.rpc<VoiceprintIdentifyResult>("speech.voiceprintIdentify", {})
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
        const result = await body.rpc<SkillRunResult>("skill.run", params, idem("skill.run", toolCallId))
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
      name: "hitl.confirm",
      label: "请求确认",
      description: "敏感操作前请求用户确认。approved=false 表示用户拒绝。",
      parameters: Type.Object({
        prompt: Type.String({ description: "向用户展示的确认文案" }),
      }),
      execute: async (_id, params: any) => {
        const result = await body.rpc<HitlConfirmResult>("hitl.confirm", params)
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
        const result = await body.rpc<HitlAskResult>("hitl.ask", params)
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
        const result = await body.rpc<HitlHandoffResult>("hitl.handoff", params)
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
        if (!verdict.ok) throw new Error(`证据核验失败：${verdict.reason}。请先重新感知屏幕，确认任务真正完成再调用。`)
        let learned: string | undefined
        const locatedSteps = run.trace.filter((s) => s.located)
        if (locatedSteps.length >= 2 && screen.appPackage) {
          try {
            const result = await body.rpc<SkillLearnResult>("skill.learn", {
              goal: run.goal,
              appPackage: screen.appPackage,
              trace: locatedSteps,
            })
            learned = result.slug
          } catch {
            learned = undefined
          }
        }
        return {
          content: [{ type: "text", text: `任务完成：${params.summary}` }],
          details: { evidenceVerified: true, traceSteps: locatedSteps.length, learned },
        }
      },
    },
  ]
}