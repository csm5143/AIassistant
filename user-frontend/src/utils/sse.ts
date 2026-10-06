import { refreshAccessToken } from '@/api'

export interface KnowledgeScope {
  mode: 'AUTO' | 'NONE' | 'ALL' | 'SELECTED'
  label: string
  reason: string
  notice?: string
  strict: boolean
  collectionIds: string[]
  documentIds: string[]
  usedKnowledge: boolean
  answerMode?: 'AUTO' | 'LOCAL'
  webAllowed?: boolean
}

export interface CitationClaim {
  id: number; text: string; indices: number[]; status: 'supported' | 'contradicted' | 'insufficient' | 'not_checked'
  reason: string; quotes: { index: number; text: string }[]
}
export interface CitationAudit { supported: number; rejected: number; unchecked: number; claims: CitationClaim[] }
export interface Citation {
  index: number
  documentId?: string
  fileName: string
  chunkId: string
  ordinal: number
  contentSnippet: string
  sourceType?: 'knowledge' | 'temporary' | 'web'
  url?: string
  evidenceText?: string
  evidenceKind?: 'page_text' | 'search_excerpt'
  retrievedAt?: string
  publishedAt?: string
  sessionId?: string
  sourceVersion?: string
  page?: number | null
  section?: string | null
  verification?: { status: CitationClaim['status']; claims: CitationClaim[] }
}

export interface ToolCall {
  name: string
  arguments: string
}

export interface ToolResult {
    name: string
    result: string
    status?: 'success' | 'failed'
}

export function createSseParser(onEvent: (type: string, data: string) => void) {
  const decoder = new TextDecoder()
  let buffer = ''
  let eventType = ''
  let dataLines: string[] = []
  const line = (raw: string) => {
    const value = raw.endsWith('\r') ? raw.slice(0, -1) : raw
    if (value === '') {
      if (dataLines.length) onEvent(eventType || 'message', dataLines.join('\n'))
      eventType = ''
      dataLines = []
    } else if (value.startsWith('event:')) {
      eventType = value.slice(6).trim()
    } else if (value.startsWith('data:')) {
      dataLines.push(value.slice(5).replace(/^ /, ''))
    }
  }
  return {
    feed(chunk: Uint8Array) {
      buffer += decoder.decode(chunk, { stream: true })
      let boundary = buffer.indexOf('\n')
      while (boundary >= 0) {
        line(buffer.slice(0, boundary))
        buffer = buffer.slice(boundary + 1)
        boundary = buffer.indexOf('\n')
      }
    },
    finish() {
      buffer += decoder.decode()
      if (buffer) line(buffer)
      if (dataLines.length) onEvent(eventType || 'message', dataLines.join('\n'))
      buffer = ''
      dataLines = []
    },
  }
}

export function streamChat(
  sessionId: string,
  content: string,
  token: string,
  callbacks: {
    resumeRunId?: string
    resumeKind?: string
    model?: string
    images?: string[]
    onToken: (text: string) => void
    onDone: () => void
    onReplace?: (content: string) => void
    onError: (msg: string) => void
    onKnowledgeScope?: (scope: KnowledgeScope) => void
    onCitations?: (citations: Citation[]) => void
    onToolCall?: (tool: ToolCall) => void
    onToolResult?: (result: ToolResult) => void
  }
): AbortController {
  const controller = new AbortController()

  const body: Record<string, unknown> = { content, stream: true }
  if (callbacks.model) body.model = callbacks.model
  if (callbacks.images && callbacks.images.length > 0) body.images = callbacks.images

  const path=callbacks.resumeRunId ? callbacks.resumeKind==='DOC'?`/api/document-qa/sessions/${sessionId}/runs/${callbacks.resumeRunId}/resume`:`/api/chat/sessions/${sessionId}/runs/${callbacks.resumeRunId}/resume` : `/api/chat/sessions/${sessionId}/stream`
  const request = (access: string) => fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${access}` },
    body: JSON.stringify(body), signal: controller.signal,
  })
  request(token).then(async (initial) => {
    const response = initial.status === 401 ? await request(await refreshAccessToken()) : initial
    if (!response.ok) {
      const err = await response.json().catch(() => ({}))
      throw new Error(err.message || 'Stream request failed')
    }
    const reader = response.body?.getReader()
    if (!reader) throw new Error('服务器未返回流式响应')
    let terminal = false
    const parser = createSseParser((type, data) => {
      if (terminal) return
      if (type === 'done' || type === 'error' || type === 'guard_block') terminal = true
      handleSSELine(data, type, callbacks)
    })

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      parser.feed(value)
    }
    parser.finish()
    if (!terminal && !controller.signal.aborted) throw new Error('连接中断，请重试')
  }).catch((err) => {
    if (err.name !== 'AbortError') {
      callbacks.onError(err.message)
    }
  })

  return controller
}

function handleSSELine(
  data: string,
  eventType: string,
  callbacks: {
    onToken: (t: string) => void
    onDone: () => void
    onReplace?: (content: string) => void
    onError: (m: string) => void
    onKnowledgeScope?: (scope: KnowledgeScope) => void
    onCitations?: (citations: Citation[]) => void
    onToolCall?: (tool: ToolCall) => void
    onToolResult?: (result: ToolResult) => void
  }
) {
  switch (eventType) {
    case 'token':
      callbacks.onToken(data)
      break
    case 'done':
      callbacks.onDone()
      break
    case 'replace':
      callbacks.onReplace?.(data)
      break
    case 'error':
      callbacks.onError(data)
      break
    case 'guard_block':
      callbacks.onError(data || '内容被安全规则拦截')
      break
    case 'knowledge_scope':
      try { callbacks.onKnowledgeScope?.(JSON.parse(data)) } catch { /* invalid scope metadata */ }
      break
    case 'retrieval_stats':
    case 'research_stats':
    case 'memory_context':
    case 'request_stats':
    case 'usage':
      // Diagnostic events must never appear in answer text.
      break
    case 'citations':
      try {
        const parsed = JSON.parse(data)
        callbacks.onCitations?.(Array.isArray(parsed) ? parsed : [])
      } catch {
        // ignore parse errors
      }
      break
    case 'tool_call':
      try {
        const parsed = JSON.parse(data)
        callbacks.onToolCall?.({ name: parsed.name, arguments: parsed.arguments })
      } catch {
        // ignore parse errors
      }
      break
    case 'tool_result':
      try {
        const parsed = JSON.parse(data)
        callbacks.onToolResult?.({ name: parsed.name, result: parsed.result, status: parsed.status })
      } catch {
        // ignore parse errors
      }
      break
    case 'message':
      // Preserve unnamed legacy text events, including legitimate JSON answers.
      if (data && data !== '"done"' && data !== '""') {
        callbacks.onToken(data)
      }
      break
    default:
      // New named events are metadata until explicitly supported by the UI.
      break
  }
}
