import { Agent } from "@earendil-works/pi-agent-core"
import type { Model, MutableModels } from "@earendil-works/pi-ai"
import type { Mark } from "../ipc/types.ts"

/**
 * 感知 L1（BD-02.2 brain 侧）：截图送 GLM-4.6V 识别可交互元素（marks）。
 * 坐标为截图像素（长边 1600，由 body ScreenshotService 限定）——与 control.tap 的
 * 屏幕像素坐标不同单位！brain 侧按屏幕/截图比例换算后再下发。
 * fail-open：识别异常时返回空 marks（perceive 结果仍带 screenshotRef 供模型自看）。
 */

export type VisionMarksFn = (imageBase64: string) => Promise<Mark[]>

const VLM_PROMPT = `你是手机屏幕元素识别器。识别截图中全部可交互元素（按钮/链接/输入框/列表项/开关等），
输出 JSON 数组，每项 {"text":"可见文字","x":中心横坐标,"y":中心纵坐标}，坐标为图像像素。
只输出 JSON 数组本身，不要任何解释文字。无可交互元素输出 []。`

export function buildLlmVisionMarks(models: MutableModels, model: Model<"openai-completions">): VisionMarksFn {
  return async (imageBase64: string) => {
    let out = ""
    const agent = new Agent({
      initialState: { systemPrompt: VLM_PROMPT, model, tools: [] },
      streamFn: models.streamSimple.bind(models),
    })
    agent.subscribe((event) => {
      if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta") {
        out += event.assistantMessageEvent.delta
      }
    })
    await agent.prompt("识别这张手机截图的可交互元素。", [
      { type: "image", data: imageBase64, mimeType: "image/jpeg" },
    ])
    const start = out.indexOf("[")
    const end = out.lastIndexOf("]")
    if (start < 0 || end <= start) return []
    try {
      const raw = JSON.parse(out.slice(start, end + 1)) as Array<{ text?: string; x?: number; y?: number }>
      return raw
        .filter((m) => typeof m.x === "number" && typeof m.y === "number")
        .map((m, i) => ({ index: i, text: String(m.text ?? ""), center: { x: m.x!, y: m.y! } }))
    } catch {
      return []
    }
  }
}
