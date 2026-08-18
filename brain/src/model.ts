import { createModels, createProvider, type MutableModels } from "@earendil-works/pi-ai"
import { openAICompletionsApi } from "@earendil-works/pi-ai/api/openai-completions.lazy"
import type { Model } from "@earendil-works/pi-ai"
import { env } from "./env.ts"

const ZERO_COST = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }

export interface ResolvedModel {
  models: MutableModels
  model: Model<"openai-completions">
  label: string
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

export function resolveModel(): ResolvedModel {
  const glmKey = env("GLM_API_KEY", "")
  const glmBase = env("GLM_BASE_URL", "https://open.bigmodel.cn/api/paas/v4")
  const glmModel = env("MODEL", "glm-4.6v")

  if (glmKey) {
    const models = createModels()
    const provider = buildOpenAiCompatProvider({
      id: "glm",
      name: "Zhipu GLM",
      baseUrl: glmBase,
      apiKey: glmKey,
      modelId: glmModel,
      modelName: glmModel,
      vision: true,
    })
    models.setProvider(provider)
    const model = models.getModel("glm", glmModel) as Model<"openai-completions"> | undefined
    if (!model) throw new Error(`模型不存在: ${glmModel}`)
    return { models, model, label: `glm/${glmModel}` }
  }

  const localUrl = env("LOCAL_LLM_URL", "")
  const localModel = env("LOCAL_MODEL", "qwen3.5-2b")
  if (localUrl) {
    const models = createModels()
    const provider = buildOpenAiCompatProvider({
      id: "local",
      name: "Local llama.cpp",
      baseUrl: localUrl,
      modelId: localModel,
      modelName: localModel,
      vision: false,
    })
    models.setProvider(provider)
    const model = models.getModel("local", localModel) as Model<"openai-completions"> | undefined
    if (!model) throw new Error(`模型不存在: ${localModel}`)
    return { models, model, label: `local/${localModel}` }
  }

  throw new Error(
    "未配置模型：设置 GLM_API_KEY（云端，推荐）或 LOCAL_LLM_URL（端侧 llama.cpp 兜底）",
  )
}