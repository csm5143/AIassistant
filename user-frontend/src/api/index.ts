import axios from 'axios'
import { useAuthStore } from '@/stores/auth'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

api.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

// Share one refresh operation between ordinary requests and SSE streams.
let refreshPromise: Promise<string> | null = null
export function refreshAccessToken(): Promise<string> {
  if (!refreshPromise) {
    const auth = useAuthStore()
    if (!auth.refreshToken) return Promise.reject(new Error('登录已过期'))
    refreshPromise = axios.post('/api/auth/refresh', { refreshToken: auth.refreshToken })
      .then(({ data }) => {
        if (data.code !== 0) throw new Error(data.message || '登录已过期')
        const access = data.data.accessToken as string
        const refresh = data.data.refreshToken as string
        auth.token = access
        auth.refreshToken = refresh
        localStorage.setItem('token', access)
        localStorage.setItem('refreshToken', refresh)
        return access
      }).finally(() => { refreshPromise = null })
  }
  return refreshPromise
}

api.interceptors.response.use(
  (res) => {
    if (res.config.responseType !== 'blob' && res.data && typeof res.data.code === 'number' && res.data.code !== 0) {
      const error = new Error(res.data.message || '请求失败') as Error & { response?: unknown }
      error.response = { data: res.data, status: res.status }
      return Promise.reject(error)
    }
    return res
  },
  async (err) => {
    const originalRequest = err.config

    if (err.response?.status !== 401 || !originalRequest || originalRequest._retried) {
      return Promise.reject(err)
    }

    const auth = useAuthStore()
    if (['/auth/login', '/auth/register', '/auth/refresh'].includes(originalRequest.url) || !auth.refreshToken) {
      return Promise.reject(err)
    }

    try {
      const newToken = await refreshAccessToken()
      originalRequest._retried = true
      originalRequest.headers.Authorization = `Bearer ${newToken}`
      return api(originalRequest)
    } catch (refreshErr) {
      auth.logout()
      window.location.href = '/login'
      return Promise.reject(refreshErr)
    }
  }
)

export default api

// ---- Auth ----
export const login = (data: { username: string; password: string; scope?: string }) =>
  api.post<R<LoginResponse>>('/auth/login', data)

export const register = (data: { username: string; password: string; email?: string; displayName?: string }) =>
  api.post<R<RegisterResponse>>('/auth/register', data)

export const getMe = () => api.get<R<UserProfile>>('/auth/me')

// ---- Chat ----
export const getSessions = (page = 1, size = 20) =>
  api.get<R<PageData<ChatSession>>>('/chat/sessions', { params: { page, size } })

export const getSession = (id: string) =>
  api.get<R<ChatSession>>(`/chat/sessions/${id}`)

export const createSession = (data?: { title?: string; model?: string; systemPrompt?: string; thinkingEffort?: 'none' | 'low' | 'high' | 'max' }) =>
  api.post<R<ChatSession>>('/chat/sessions', data)

export const deleteSession = (id: string) =>
  api.delete<R<null>>(`/chat/sessions/${id}`)

export const cancelChat = (id: string) =>
  api.post<R<null>>(`/chat/sessions/${id}/cancel`)

export const getMessages = (sessionId: string) =>
  api.get<R<ChatMessage[]>>(`/chat/sessions/${sessionId}/messages`)

export const updateSession = (id: string, data: { title?: string; model?: string; systemPrompt?: string; knowledgeMode?: string; knowledgeSelectionJson?: string; answerMode?: 'AUTO' | 'LOCAL'; thinkingEffort?: 'none' | 'low' | 'high' | 'max' }) =>
  api.patch<R<null>>(`/chat/sessions/${id}`, data)

// ---- Models ----
export const uploadChatImage = (file: File) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post<R<{ url: string; fileName: string; size: number }>>('/chat/images/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export const getModels = (type: 'chat' | 'embedding' | 'all' = 'all') =>
  api.get<R<AiModel[]>>('/models', { params: { type } })

export const createModel = (data: {
  name: string
  provider: string
  baseUrl: string
  modelName: string
  thinkingSupported?: boolean
  apiKeyAlias?: string
  apiKeyConfigured?: boolean
  embeddingDimension?: number
  temperature?: number
  maxTokens?: number
}) =>
  api.post<R<AiModel>>('/models', data)

export const setDefaultModel = (id: string) =>
  api.post<R<null>>(`/models/${id}/set-default`)

export const updateModel = (id: string, data: Partial<AiModel>) =>
  api.put<R<AiModel>>(`/models/${id}`, data)

export const deleteModel = (id: string) =>
  api.delete<R<null>>(`/models/${id}`)

export const testModelConnection = (id: string) =>
  api.post<R<{ success: boolean; message: string; httpStatus?: number }>>(`/models/${id}/test`)

// ---- User ----
export const getUserQuota = (userId: string) =>
  api.get<R<UserQuota>>(`/users/${userId}/quota`)

export const getUsers = (params?: { page?: number; size?: number; keyword?: string }) =>
  api.get<R<PageData<SysUser>>>('/users', { params })

export const getUser = (id: string) =>
  api.get<R<SysUser>>(`/users/${id}`)

export const updateUser = (id: string, data: { displayName?: string; email?: string; phone?: string; avatar?: string }) =>
  api.put<R<SysUser>>(`/users/${id}`, data)

export const deleteUser = (id: string) =>
  api.delete<R<null>>(`/users/${id}`)

export const enableUser = (id: string) =>
  api.patch<R<null>>(`/users/${id}/enable`)

export const disableUser = (id: string) =>
  api.patch<R<null>>(`/users/${id}/disable`)

export const updateUserQuota = (userId: string, data: {
  dailyTokenLimit?: number
  monthlyTokenLimit?: number
  dailyTokenUsed?: number
  monthlyTokenUsed?: number
  dailyRequestLimit?: number
  monthlyRequestLimit?: number
  dailyRequestUsed?: number
  monthlyRequestUsed?: number
  quotaResetAt?: string
}) =>
  api.put<R<null>>(`/users/${userId}/quota`, data)

// ---- Knowledge ----
export const getCollections = () =>
  api.get<R<KbCollection[]>>('/knowledge/collections')

export const createCollection = (data: { name: string; description?: string }) =>
  api.post<R<KbCollection>>('/knowledge/collections', data)

export const deleteCollection = (id: string) =>
  api.delete<R<null>>(`/knowledge/collections/${id}`)

export const getDocuments = (collectionId: string) =>
  api.get<R<KbDocument[]>>(`/knowledge/collections/${collectionId}/documents`)

export interface LocalFolderListing {
  enabled: boolean
  path: string | null
  parent: string | null
  roots: { name: string; path: string }[]
  directories: { name: string; path: string }[]
  storagePath: string
}
export interface FolderSyncResult {
  folderPath: string
  added: number
  updated: number
  removed: number
  unchanged: number
  skipped: number
  syncedAt: string
  entries: { path: string; status: string; documentId: string | null; error: string | null }[]
}
export const browseKnowledgeFolders = (path?: string) =>
  api.get<R<LocalFolderListing>>('/knowledge/local-folders', { params: path ? { path } : {} })
export const bindKnowledgeFolder = (id: string, path: string) =>
  api.post<R<FolderSyncResult>>(`/knowledge/collections/${id}/folder`, { path }, { timeout: 300000 })
export const syncKnowledgeFolder = (id: string) =>
  api.post<R<FolderSyncResult>>(`/knowledge/collections/${id}/folder/sync`, null, { timeout: 300000 })
export const unbindKnowledgeFolder = (id: string) =>
  api.delete<R<null>>(`/knowledge/collections/${id}/folder`, { timeout: 300000 })

export const searchKnowledge = (q: string, limit = 5, collectionId?: string) =>
  api.get<R<KbChunk[]>>('/knowledge/search', { params: { q, limit, collectionId } })

export const getDocumentStatus = (id: string) =>
  api.get<R<DocumentStatus>>(`/knowledge/documents/${id}/status`)

export const getCollectionDocumentProgress = (collectionId: string) =>
  api.get<R<DocumentProgress[]>>(`/knowledge/collections/${collectionId}/documents/progress`)

export const retryDocument = (id: string) =>
  api.post<R<null>>(`/knowledge/documents/${id}/retry`)

export const getChunk = (id: string) =>
  api.get<R<KbChunk>>(`/knowledge/chunks/${id}`)

export const exportDocument = (id: string, format: string) =>
  api.get(`/knowledge/documents/${id}/export`, { params: { format }, responseType: 'blob' })

export const exportDocumentsBatch = (documentIds: string[], format: string) =>
  api.post('/knowledge/documents/export/batch', { documentIds, format }, { responseType: 'blob' })

export const importFromUrls = (collectionId: string, data: { urls: string[]; followLinks?: boolean; maxPages?: number }) =>
  api.post<R<ImportResult>>(`/knowledge/collections/${collectionId}/import-url`, data)

export interface ImportResult {
  totalUrls: number
  successCount: number
  failCount: number
  totalChars: number
  results: { url: string; title?: string; id?: string; status: string; chars?: number; error?: string }[]
}

export interface DocumentStatus {
  id: string
  status: string
  filename: string
  chunkCount: number
  errorMessage: string | null
  parseReportJson?: string | null
  createdAt: string
  updatedAt: string
}

export type DocumentProgress = Pick<KbDocument,
  'id' | 'status' | 'progressStage' | 'processedPages' | 'totalPages' |
  'vectorizedCount' | 'chunkCount' | 'errorMessage'>

// ---- Analytics ----
export const recordVisit = (data?: { userId?: string; page?: string; referrer?: string }) =>
  api.post<R<null>>('/analytics/visit', data)

// ---- Admin ----
export const getDashboardStats = () =>
  api.get<R<DashboardStats>>('/admin/dashboard/stats')

export const getChatLogs = (params: { page?: number; size?: number; userId?: string; startDate?: string; endDate?: string }) =>
  api.get<R<PageData<ChatLog>>>('/admin/chat-logs', { params })

export const getGuardLogs = (params: { page?: number; size?: number; direction?: string }) =>
  api.get<R<PageData<GuardLog>>>('/admin/guard-logs', { params })

// ---- Types ----
export interface R<T> {
  code: number
  message: string
  data: T
  traceId: string | null
  timestamp: number
}

export interface PageData<T> {
  records: T[]
  current: number
  size: number
  total: number
  pages: number
}

export interface RegisterResponse {
  user: { id: string; username: string; displayName: string; email: string }
  tokens: { accessToken: string; refreshToken: string; expiresIn: number }
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
  profile: UserProfile
}

export interface UserProfile {
  id: string
  username: string
  displayName: string
  avatar: string | null
  email: string | null
  scope: string
  roles: string[]
}

export interface AiModel {
  id: string
  name: string
  provider: string
  baseUrl: string
  apiKeyAlias?: string
  embeddingDimension?: number | null
  modelName: string
  thinkingSupported?: boolean
  temperature: number
  maxTokens: number
  pricePer1kInput: number
  pricePer1kOutput: number
  enabled: boolean
  isDefault: boolean
  sortOrder: number
}

export interface UserQuota {
  id: string
  userId: string
  tier: string
  dailyTokenLimit: number
  monthlyTokenLimit: number
  dailyTokenUsed: number
  monthlyTokenUsed: number
  dailyRequestLimit: number
  monthlyRequestLimit: number
  dailyRequestUsed: number
  monthlyRequestUsed: number
  quotaResetAt: string
}

export interface SysUser {
  id: string
  username: string
  displayName: string | null
  email: string | null
  phone: string | null
  avatar: string | null
  status: number
  lastLoginAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ChatSession {
  id: string
  userId: string
  title: string
  model: string
  systemPrompt: string | null
  pinned: number | null
  knowledgeMode?: 'AUTO' | 'NONE' | 'ALL' | 'SELECTED'
  answerMode?: 'AUTO' | 'LOCAL'
  thinkingEffort?: 'none' | 'low' | 'high' | 'max'
  knowledgeSelectionJson?: string | null
  knowledgeRouteJson?: string | null
  createdAt: string
  updatedAt: string
}

export interface ChatMessage {
  id: string
  sessionId: string
  role: 'user' | 'assistant' | 'system'
  content: string
  toolCalls: string | null
  extra: string | null
  tokenCount: number | null
  createdAt: string
}

export interface ChatLog {
  id: string
  userId: string
  sessionId: string
  question: string
  answer: string
  model: string
  promptTokens: number | null
  completionTokens: number | null
  latencyMs: number | null
  toolHit: number | null
  knowledgeHit: number | null
  guardHit: number | null
  errorMessage: string | null
  createdAt: string
}

export interface GuardLog {
  id: string
  userId: string
  direction: string
  stage: string
  rule: string
  matchedContent: string
  action: string
  createdAt: string
}

export interface KbCollection {
  id: string
  userId: string
  name: string
  description: string | null
  embeddingModel: string | null
  dimension: number | null
  chunkSize: number | null
  chunkOverlap: number | null
  folderPath?: string | null
  folderSyncedAt?: string | null
  documentCount: number
  chunkCount: number
  createdAt: string
  updatedAt: string
}

export interface KbDocument {
  id: string
  collectionId: string
  userId: string
  filename: string
  mimeType: string
  sizeBytes: number
  storagePath: string | null
  sourceKind?: 'UPLOAD' | 'IMPORT' | 'FOLDER'
  sourceRelativePath?: string | null
  status: string
  errorMessage: string | null
  parseReportJson?: string | null
  chunkCount: number | null
  processedPages?: number | null
  totalPages?: number | null
  vectorizedCount?: number | null
  progressStage?: 'QUEUED' | 'PARSING' | 'ENHANCING' | 'CHUNKING' | 'EMBEDDING' | 'COMPLETE' | 'FAILED' | null
  parseDurationMs?: number | null
  chunkDurationMs?: number | null
  vectorDurationMs?: number | null
  createdAt: string
  updatedAt: string
}

export interface PdfParseReport {
  engine: string
  totalPages: number
  nativePages: number
  enhancedPages: number
  ocrPages: number
  tables: number
  images: number
  visionCalls: number
  visionTokens: number
  cacheHits: number
  elapsedMs: number
  warnings: string[]
}

export interface KbChunk {
  id: string
  documentId: string
  collectionId: string
  userId: string
  ordinal: number
  content: string
  tokenCount: number | null
  vectorId: string | null
  createdAt: string
}

export interface DashboardStats {
  todaySessions: number
  todayChats: number
  todayGuardHits: number
  todayActiveUsers: number
  totalSessions: number
  totalMessages: number
  totalGuardHits: number
  thisMonthChats: number
  sessionTrend: number[]
  chatTrend: number[]
  trendLabels: string[]
  modelDistribution: { name: string; count: number }[]
}
