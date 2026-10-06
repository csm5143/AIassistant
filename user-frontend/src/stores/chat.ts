import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ChatSession, ChatMessage, AiModel } from '@/api'
import { getSessions, createSession, deleteSession, getMessages, updateSession, getModels } from '@/api'
import type { Citation } from '@/utils/sse'

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<ChatSession[]>([])
  const messages = ref<(ChatMessage & { renderKey?: string })[]>([])
  let messagesRequest = 0
  const currentSessionId = ref<string | null>(null)
  const streaming = ref(false)
  const streamingContent = ref('')
  const models = ref<AiModel[]>([])
  const selectedModel = ref<string>('deepseek-chat')
  const selectedThinkingEffort = ref<'none' | 'low' | 'high' | 'max'>('none')
  const citations = ref<Record<string, Citation[]>>({})

  async function fetchSessions() {
    try {
      const { data } = await getSessions()
      sessions.value = data.data.records || []
    } catch { /* ignore */ }
  }

  async function fetchModels() {
    try {
      const { data } = await getModels('chat')
      models.value = data.data || []
      if (models.value.length > 0) {
        const def = models.value.find(m => m.isDefault) || models.value[0]
        const current = sessions.value.find(s => s.id === currentSessionId.value)
        selectedModel.value = current?.model || def.modelName
      }
    } catch { /* ignore */ }
  }

  async function newSession(title?: string, model?: string) {
    const { data } = await createSession({ title, model: model || selectedModel.value, thinkingEffort: selectedThinkingEffort.value })
    const session = data.data
    sessions.value.unshift(session)
    currentSessionId.value = session.id
    messages.value = []
    citations.value = {}
    return session
  }

  async function removeSession(id: string) {
    await deleteSession(id)
    sessions.value = sessions.value.filter(s => s.id !== id)
    if (currentSessionId.value === id) {
      currentSessionId.value = null
      messages.value = []
      citations.value = {}
    }
  }

  async function switchModel(sessionId: string, model: string) {
    await updateSession(sessionId, { model })
    selectedModel.value = model
  }

  async function fetchMessages(sessionId: string, options: { preserve?: boolean } = {}) {
    const preserve = options.preserve === true
    if (preserve && (currentSessionId.value !== sessionId || streaming.value)) return
    const request = ++messagesRequest
    const previous = [...messages.value]
    if (!preserve) {
      currentSessionId.value = sessionId
      messages.value = []
      citations.value = {}
    }
    try {
      const { data } = await getMessages(sessionId)
      if (request !== messagesRequest || currentSessionId.value !== sessionId || streaming.value) return
      const loaded = data.data || []
      if (preserve) {
        // A new send or regeneration must win over an older background refresh.
        if (messages.value.length !== previous.length ||
            previous.some((msg, i) => messages.value[i] !== msg)) return
        // The transaction may still be committing. Keep the completed answer visible.
        if (loaded.length !== previous.length ||
            loaded.some((msg, i) => msg.role !== previous[i].role || msg.content !== previous[i].content)) return
      }
      const nextCitations: Record<string, Citation[]> = {}
      messages.value = loaded.map((msg, i) => {
        const old = preserve ? previous[i] : undefined
        if (old && citations.value[old.id]) nextCitations[msg.id] = citations.value[old.id]
        if (msg.extra) {
          try {
            const meta = JSON.parse(msg.extra)
            if (Array.isArray(meta.citations)) nextCitations[msg.id] = meta.citations
          } catch { /* old messages may not contain JSON */ }
        }
        // Server IDs can change after saving; the existing bubble keeps its DOM identity.
        return { ...msg, renderKey: old?.renderKey || old?.id || msg.id }
      })
      citations.value = nextCitations
    } catch {
      // Background sync failures keep the currently displayed conversation intact.
    }
  }

  function addUserMessage(content: string) {
    messages.value.push({
      id: crypto.randomUUID(),
      sessionId: currentSessionId.value || '',
      role: 'user',
      content,
      toolCalls: null,
      extra: null,
      tokenCount: null,
      createdAt: new Date().toISOString(),
    })
  }

  function addAssistantMessage(content: string, id: string = crypto.randomUUID()): string {
    messages.value.push({
      id,
      sessionId: currentSessionId.value || '',
      role: 'assistant',
      content,
      toolCalls: null,
      extra: null,
      tokenCount: null,
      createdAt: new Date().toISOString(),
    })
    return id
  }

  function setCitations(msgId: string, cites: Citation[]) {
    citations.value[msgId] = cites
  }

  return {
    sessions, messages, currentSessionId, streaming, streamingContent,
    models, selectedModel, selectedThinkingEffort, citations,
    fetchSessions, fetchModels, newSession, removeSession, switchModel, fetchMessages,
    addUserMessage, addAssistantMessage, setCitations,
  }
})
