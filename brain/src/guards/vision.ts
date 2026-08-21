import { Agent } from "@earendil-works/pi-agent-core"
import type { Model, MutableModels } from "@earendil-works/pi-ai"
import type { Mark } from "../ipc/types.ts"

/**
 * 感知 L1（BD-02.2 brain 侧）：截图送**可配置的视觉模型**识别可交互元素（marks）。
 * 模型由调用方注入；坐标为截图像素，调用方使用 body 返回的实际尺寸换算。
 * provider、输出格式和坐标失败均返回无敏感细节的 discriminated result。
 */

export type VisionResult =
  | { status: "success"; marks: Mark[] }
  | { status: "provider_unavailable" }
  | { status: "invalid_output" }
  | { status: "invalid_coordinates" }

export type VisionMarksFn = (imageBase64: string, screenshotWidth: number, screenshotHeight: number) => Promise<VisionResult>

const VLM_PROMPT = `你是手机屏幕元素识别器。识别截图中全部可交互元素（按钮/链接/输入框/列表项/开关等），
输出 JSON 数组，每项 {"text":"可见文字","x":中心横坐标,"y":中心纵坐标}，坐标为图像像素。
只输出 JSON 数组本身，不要任何解释文字。无可交互元素输出 []。`

export function parseVisionOutput(rawOutput: string, screenshotWidth: number, screenshotHeight: number): VisionResult {
  if (!Number.isFinite(screenshotWidth) || !Number.isFinite(screenshotHeight) || screenshotWidth <= 0 || screenshotHeight <= 0) {
    return { status: "invalid_coordinates" }
  }
  const text = rawOutput.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "")
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return { status: "invalid_output" }
  }
  if (!Array.isArray(parsed)) return { status: "invalid_output" }

  const marks: Mark[] = []
  for (const [index, item] of parsed.entries()) {
    if (typeof item !== "object" || item === null) return { status: "invalid_coordinates" }
    const candidate = item as { text?: unknown; x?: unknown; y?: unknown }
    const { x, y } = candidate
    if (
      typeof x !== "number" || typeof y !== "number" ||
      !Number.isFinite(x) || !Number.isFinite(y) ||
      x < 0 || y < 0 || x >= screenshotWidth || y >= screenshotHeight
    ) {
      return { status: "invalid_coordinates" }
    }
    marks.push({ index, text: String(candidate.text ?? ""), center: { x, y } })
  }
  return { status: "success", marks }
}

export async function visionResultFromProvider(
  request: () => Promise<string>,
  screenshotWidth: number,
  screenshotHeight: number,
): Promise<VisionResult> {
  try {
    return parseVisionOutput(await request(), screenshotWidth, screenshotHeight)
  } catch {
    return { status: "provider_unavailable" }
  }
}

export function buildLlmVisionMarks(models: MutableModels, model: Model<"openai-completions">): VisionMarksFn {
  return async (imageBase64: string, screenshotWidth: number, screenshotHeight: number) => {
    return visionResultFromProvider(async () => {
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
      return out
    }, screenshotWidth, screenshotHeight)
  }
}
