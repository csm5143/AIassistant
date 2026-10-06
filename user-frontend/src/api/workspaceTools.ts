import api from '@/api'

export interface ToolAsset {
  id: string
  sessionId: string
  name: string
  mime: string
  size: number
  kind: 'upload' | 'table' | 'chart' | 'report' | 'extraction' | 'pdf'
  createdAt: string
}
export interface WorkspaceResult {
  ok: boolean
  summary?: string
  artifacts?: ToolAsset[]
  columns?: string[]
  preview?: unknown[][]
  audit?: Record<string, unknown>
  elapsedMs?: number
}
export const listToolFiles = (session: string) => api.get(`/workspace-tools/sessions/${session}/files`)
export const uploadToolFile = (session: string, file: File) => {
  const form = new FormData(); form.append('file', file)
  return api.post(`/workspace-tools/sessions/${session}/files`, form, { timeout: 120000 })
}
export const removeToolFile = (session: string, id: string) => api.delete(`/workspace-tools/sessions/${session}/files/${id}`)
export async function executeFileTool(session: string, name: string, args: unknown) {
  const started=await api.post(`/workspace-tools/sessions/${session}/jobs/${name}`,args)
  const id=started.data.data.id,deadline=Date.now()+240000
  while(Date.now()<deadline){const status=await api.get(`/chat/sessions/${session}/runs/${id}`),run=status.data.data
    if(run.status==='COMPLETED')return {...status,data:{...status.data,data:run.result}}
    if(run.status!=='RUNNING')throw new Error(run.note||'文件任务中断，请在聊天的任务记录中查看或恢复')
    await new Promise(resolve=>setTimeout(resolve,600))
  }
  throw new Error('文件任务仍在后台处理，可在聊天的任务记录中查看进度')
}
export async function toolFileBlob(asset: ToolAsset): Promise<Blob> {
  const { data } = await api.get(`/workspace-tools/files/${asset.id}`, { params: { inline: true }, responseType: 'blob' })
  if (data.type?.includes('application/json')) {
    const error = JSON.parse(await data.text()); throw new Error(error.message || '文件读取失败')
  }
  return data
}
export async function downloadToolFile(asset: ToolAsset) {
  const blob = await toolFileBlob(asset), url = URL.createObjectURL(blob)
  const link = document.createElement('a'); link.href = url; link.download = asset.name; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
export function parseToolArtifacts(value: string | null | undefined): ToolAsset[] {
  if (!value) return []
  try { const parsed = JSON.parse(value); return Array.isArray(parsed.artifacts) ? parsed.artifacts : [] } catch { return [] }
}
export const fileToolLabels: Record<string, string> = {
  inspectTable: '查看表格', analyzeTable: '计算表格与图表', generateDocument: '制作报告',
  readDocuments: '读取原文', exportExtractedFields: '核验并汇总字段', pdfTools: '处理 PDF',
}
