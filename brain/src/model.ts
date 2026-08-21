import { createModels, createProvider, type MutableModels } from "@earendil-works/pi-ai"
import { openAICompletionsApi } from "@earendil-works/pi-ai/api/openai-completions.lazy"
import type { Model } from "@earendil-works/pi-ai"
import { env } from "./env.ts"

const ZERO_COST = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }

export interface ResolvedModel {
  models: MutableModels
  model: Model<"openai-completions">
  label: string
  /** M3 本地模型为主（BR-02.3 安全铁律：仅闲聊，工具集强制清空）。 */
  localOnly: boolean
  /** 备用云端（BR-02.2）：主模型连续失败 ≥3 次自动切换，能力表降级（可能无视觉）。 */
  backupModel?: Model<"openai-completions">
  backupLabel?: string
  /** M3 本地（仅当主模型为云端时作为最终兜底）。 */
  localModel?: Model<"openai-completions">
  localLabel?: string
  /**
   * 视觉模型（GPT 扫描 2026-08-21：视觉 provider 可配置、不写死型号）：
   * VISION_BASE_URL + VISION_API_KEY + VISION_MODEL 齐备时注册独立 vision provider——
   * 不随主模型降级链重建（独立解析）；未配置时为 undefined，视觉跟随主模型（现状语义）。
   */
  visionModel?: Model<"openai-completions">
  visionLabel?: string
}

export function resolveModel(): ResolvedModel {
  const glmKey = env("GLM_API_KEY", "")
  const glmBase = env("GLM_BASE_URL", "https://open.bigmodel.cn/api/paas/v4")
  const glmModel = env("MODEL", "glm-4.6v")

  const backupUrl = env("BACKUP_LLM_URL", "")
  const backupModel = env("BACKUP_MODEL", "")
  const localUrl = env("LOCAL_LLM_URL", "")
  const localModel = env("LOCAL_MODEL", "qwen3.5-2b")

  // M3 纯本地模式（BR-02.3）：无云端 key 且配置了本地 → 仅闲聊（localOnly 由 main 侧执行工具清空）
  if (!glmKey && localUrl) {
    const { models, model, label } = buildSingle("local", "Local llama.cpp", localUrl, undefined, localModel, false)
    return { models, model, label, localOnly: true }
  }

  if (glmKey) {
    const models = createModels()
    const provider = buildOpenAiCompatProvider({
      id: "glm", name: "Zhipu GLM", baseUrl: glmBase, apiKey: glmKey,
      modelId: glmModel, modelName: glmModel, vision: true,
    })
    models.setProvider(provider)
    const model = models.getModel("glm", glmModel) as Model<"openai-completions"> | undefined
    if (!model) throw new Error(`模型不存在: ${glmModel}`)
    const resolved: ResolvedModel = { models, model, label: `glm/${glmModel}`, localOnly: false }

    if (backupUrl && backupModel) {
      const p = buildOpenAiCompatProvider({
        id: "backup", name: "Backup cloud", baseUrl: backupUrl,
        apiKey: env("BACKUP_LLM_KEY", "") || undefined,
        modelId: backupModel, modelName: backupModel, vision: false,
      })
      models.setProvider(p)
      const m = models.getModel("backup", backupModel) as Model<"openai-completions"> | undefined
      if (m) {
        resolved.backupModel = m
        resolved.backupLabel = `backup/${backupModel}（无视觉降级）`
      }
    }
    if (localUrl) {
      const p = buildOpenAiCompatProvider({
        id: "local", name: "Local llama.cpp", baseUrl: localUrl,
        modelId: localModel, modelName: localModel, vision: false,
      })
      models.setProvider(p)
      const m = models.getModel("local", localModel) as Model<"openai-completions"> | undefined
      if (m) {
        resolved.localModel = m
        resolved.localLabel = `local/${localModel}（离线闲聊）`
      }
    }
    // 视觉独立配置（GPT 边界：默认视觉配置不得隐式改变主规划模型——反之亦然，独立注册互不影响）
    const visionBase = env("VISION_BASE_URL", "")
    const visionKey = env("VISION_API_KEY", "")
    const visionModelId = env("VISION_MODEL", "")
    if (visionBase && visionModelId) {
      const p = buildOpenAiCompatProvider({
        id: "vision", name: "Vision provider", baseUrl: visionBase,
        apiKey: visionKey || undefined,
        modelId: visionModelId, modelName: visionModelId, vision: true,
      })
      models.setProvider(p)
      const m = models.getModel("vision", visionModelId) as Model<"openai-completions"> | undefined
      if (m) {
        resolved.visionModel = m
        resolved.visionLabel = `vision/${visionModelId}（独立视觉）`
      }
    }
    return resolved
  }

  throw new Error(
    "未配置模型：设置 GLM_API_KEY（云端，推荐）或 LOCAL_LLM_URL（端侧 llama.cpp 兜底）",
  )
}

function buildSingle(id: string, name: string, baseUrl: string, apiKey: string | undefined, modelId: string, vision: boolean) {
  const models = createModels()
  const provider = buildOpenAiCompatProvider({ id, name, baseUrl, apiKey, modelId, modelName: modelId, vision })
  models.setProvider(provider)
  const model = models.getModel(id, modelId) as Model<"openai-completions"> | undefined
  if (!model) throw new Error(`模型不存在: ${modelId}`)
  return { models, model, label: `${id}/${modelId}` }
}

function buildOpenAiCompatProvider(opts: {
  id: string
  name: string
  baseUrl: string
  apiKey?: string
  modelId: string
  modelName: string
  vision: boolean
}): ReturnType<typeof createProvider<"openai-completions">> {
  return createProvider<"openai-completions">({
    id: opts.id,
    name: opts.name,
    baseUrl: opts.baseUrl,
    auth: {
      apiKey: {
        name: `${opts.name} API key`,
        resolve: async () =>
          opts.apiKey ? { auth: { apiKey: opts.apiKey } } : undefined,
      },
    },
    models: [
      {
        id: opts.modelId,
        name: opts.modelName,
        api: "openai-completions",
        provider: opts.id,
        baseUrl: opts.baseUrl,
        reasoning: false,
        input: opts.vision ? ["text", "image"] : ["text"],
        cost: ZERO_COST,
        contextWindow: 131072,
        maxTokens: 32768,
      },
    ],
    api: openAICompletionsApi(),
  })
}

