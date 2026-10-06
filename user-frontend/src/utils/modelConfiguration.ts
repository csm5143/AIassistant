export interface ModelConfiguration {
  id: string; name: string; provider: string; type: string; modelName: string; baseUrl: string
  apiKeyAlias?: string; apiKeyConfigured?: boolean; enabled: boolean; isDefault: boolean
  temperature?: number; maxTokens?: number; topP?: number; embeddingDimension?: number | null; sortOrder: number
}

export const configurationTypes = [
  { value: 'chat', label: '对话模型', description: '日常对话与知识库回答' },
  { value: 'embedding', label: '知识库检索', description: '文档向量化与语义检索' },
  { value: 'vision', label: '独立图片识别', description: '对话模型不支持识图时使用' },
  { value: 'search', label: '备用联网搜索', description: '主要搜索服务不可用时使用' },
  { value: 'ocr', label: '云端文字识别', description: '按需使用的图片文字识别工具' },
]

export function primaryConfigurations(models: ModelConfiguration[]) {
  return ['chat', 'embedding'].map(type => models.filter(row => row.type === type).sort((a, b) =>
    Number(b.enabled) - Number(a.enabled)
    // EmbeddingModelConfig selects by sort order at startup, not by isDefault.
    || (type === 'chat' ? Number(b.isDefault) - Number(a.isDefault) : 0)
    || (a.sortOrder ?? 99) - (b.sortOrder ?? 99))[0])
}

export function hasSavedKey(row: ModelConfiguration) {
  return row.apiKeyConfigured === true || !!row.apiKeyAlias?.trim()
}

export function reusesChatVision(row?: ModelConfiguration) {
  if (!row?.enabled) return false
  try {
    return new URL(row.baseUrl).hostname.toLowerCase() === 'api.deepseek.com'
      && ['deepseek-chat', 'deepseek-flash', 'deepseek-v4-flash', 'deepseek-v4-flash-vision-exp'].includes(row.modelName.toLowerCase())
  } catch { return false }
}

export function configurationPreset(type: string): Omit<ModelConfiguration, 'id'> {
  const embedding = type === 'embedding'
  return {
    name: embedding ? 'BGE-M3' : type === 'chat' ? 'DeepSeek Chat' : '',
    provider: embedding ? 'siliconflow' : type === 'chat' ? 'deepseek' : '', type,
    modelName: embedding ? 'BAAI/bge-m3' : type === 'chat' ? 'deepseek-chat' : '',
    baseUrl: embedding ? 'https://api.siliconflow.cn/v1' : type === 'chat' ? 'https://api.deepseek.com/v1' : '',
    apiKeyAlias: '', enabled: true, isDefault: false, sortOrder: 99,
    temperature: 0.3, maxTokens: 4096, embeddingDimension: embedding ? 1024 : null,
  }
}
