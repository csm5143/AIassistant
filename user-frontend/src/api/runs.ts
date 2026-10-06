import api from '@/api'
export interface RunRecord {
  id: string; kind: 'CHAT' | 'DOC' | 'TOOL'; status: string; question: string; partial?: string; note: string; attempts: number
  steps: {name: string; status: string}[]; artifacts: import('./workspaceTools').ToolAsset[]; result?: Record<string, any>
  modelRequests: number; reportedModelRequests: number; promptTokens: number; completionTokens: number; usageIncomplete: boolean
}
export const listRuns = (session: string) => api.get(`/chat/sessions/${session}/runs`)
export const getRun = (session: string, id: string) => api.get(`/chat/sessions/${session}/runs/${id}`)
export const resumeToolRun = (session: string, id: string) => api.post(`/workspace-tools/sessions/${session}/jobs/${id}/resume`)
