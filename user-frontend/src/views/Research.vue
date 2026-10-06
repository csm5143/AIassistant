<template>
  <div class="research-page">
    <header class="research-topbar">
      <div class="research-brand"><span class="brand-mark">◈</span><span>专题研究</span><small>Research workspace</small></div>
      <nav aria-label="主导航">
        <button type="button" @click="router.push('/chat')">返回对话</button>
        <button type="button" @click="router.push('/knowledge')">知识库</button>
      </nav>
    </header>

    <main class="research-layout">
      <section class="setup-panel" aria-label="研究任务设置">
        <div class="panel-kicker">01 / 研究范围</div>
        <h1>让资料给出答案</h1>
        <p class="panel-intro">选定已处理的资料，系统会生成可追溯的专题报告。第一版适合对比 1–3 份文档。</p>

        <label class="field-label" for="research-topic">研究问题 <span>必填</span></label>
        <textarea id="research-topic" v-model="topic" rows="3" maxlength="300" placeholder="例如：比较这几篇论文的检索方法、效果与局限" :disabled="busy" />
        <label class="field-label" for="research-dimensions">重点维度 <span>可选</span></label>
        <input id="research-dimensions" v-model="dimensions" maxlength="300" placeholder="例如：方法、数据集、成本、局限" :disabled="busy" />

        <div class="field-label field-heading">资料选择 <span>{{ selectedIds.length }}/{{ MAX_RESEARCH_DOCUMENTS }}</span></div>
        <div class="document-search-row">
          <input v-model="documentSearch" aria-label="筛选资料" placeholder="搜索文件名或知识库" />
          <button type="button" class="refresh-button" :disabled="loading || busy" @click="loadData">刷新</button>
        </div>
        <p v-if="loading" class="subtle">正在读取资料…</p>
        <div v-else-if="filteredDocuments.length" class="document-list" role="group" aria-label="选择研究资料">
          <label v-for="doc in filteredDocuments" :key="doc.id" class="document-option" :class="{ chosen: selectedIds.includes(doc.id), unavailable: doc.status !== 'READY' }">
            <input type="checkbox" :checked="selectedIds.includes(doc.id)" :disabled="busy || doc.status !== 'READY' || (!selectedIds.includes(doc.id) && selectedIds.length >= MAX_RESEARCH_DOCUMENTS)" @change="toggleDoc(doc.id)" />
            <span class="document-option-text"><strong :title="doc.filename">{{ doc.filename }}</strong><small>{{ collectionName(doc.collectionId) }} · {{ doc.status === 'READY' ? '可研究' : '处理中或未就绪' }}</small></span>
          </label>
        </div>
        <div v-else class="empty-documents">没有可显示的资料。先到知识库导入文档，待处理完成后再开始研究。</div>

        <div class="field-label field-heading">回答依据</div>
        <div class="mode-options">
          <label><input v-model="answerMode" type="radio" value="LOCAL" :disabled="busy" />仅依据所选资料</label>
          <label><input v-model="answerMode" type="radio" value="AUTO" :disabled="busy" />资料不足时补充网页</label>
        </div>
        <button type="button" class="start-button" :disabled="busy || loading || !canStart" @click="startResearch">
          <span v-if="busy" class="loading-dot" aria-hidden="true"></span>{{ busy ? '正在研究…' : '生成研究报告' }} <span aria-hidden="true">↗</span>
        </button>
        <button v-if="busy" type="button" class="cancel-button" @click="stopResearch">停止本次生成</button>
        <p v-if="error" class="error-note" role="alert">{{ error }}</p>

        <div class="recent-heading">最近的专题研究</div>
        <div v-if="researchSessions.length" class="recent-list">
          <button v-for="session in researchSessions" :key="session.id" type="button" :class="{ active: currentId === session.id }" @click="openReport(session.id)">
            <span>{{ session.title }}</span><small>{{ formatDate(session.updatedAt) }}</small>
          </button>
        </div>
        <button v-if="sessionPage < sessionPages" type="button" class="more-sessions" :disabled="loadingMore" @click="loadOlderSessions">{{ loadingMore ? '读取中…' : '查看更早的研究' }}</button>
        <p v-if="!researchSessions.length" class="subtle">完成的研究报告会保存在这里。</p>
      </section>

      <section class="report-panel" aria-label="研究报告">
        <div v-if="!currentId" class="report-placeholder">
          <div class="placeholder-symbol">◎</div>
          <h2>从一个具体问题开始</h2>
          <p>选择资料并提出比较问题。报告将整理证据、指出分歧与信息缺口。</p>
        </div>
        <template v-else>
          <div class="report-toolbar">
            <div><span class="panel-kicker">02 / 研究报告</span><h2>{{ reportTitle }}</h2><p v-if="reportDocumentNames.length" class="report-scope">研究资料：{{ reportDocumentNames.join('、') }}</p></div>
            <div class="report-actions">
              <button type="button" :disabled="!displayReport" @click="exportMarkdown">导出 Markdown</button>
              <button type="button" @click="router.push('/chat/' + currentId)">继续追问 ↗</button>
            </div>
          </div>
          <div v-if="busy" class="research-progress" role="status" aria-live="polite"><span class="loading-dot"></span>{{ progressText }}</div>
          <div v-if="displayReport" class="report-content" @click="onReportClick" v-html="renderedReport"></div>
          <div v-else-if="!busy" class="report-placeholder compact"><h3>该任务尚无已完成的报告</h3><p>可以重新发起研究，或进入对应会话查看记录。</p></div>
          <div v-if="displayReport" class="evidence-check" :class="{ warning: audit.unknown.length || !audit.used.length || uncitedDocuments.length }">
            <strong>引用核对</strong>
            <span>报告使用 {{ audit.used.length }} 个已登记来源。</span>
            <span v-if="audit.unknown.length">编号 {{ audit.unknown.map(n => '[' + n + ']').join('、') }} 没有来源记录，请勿直接使用这些结论。</span>
            <span v-else-if="!audit.used.length">报告没有正文引用，请核对后使用。</span>
            <span v-if="uncitedDocuments.length">以下所选资料尚无正文引用：{{ uncitedDocuments.join('、') }}。相关比较结论需要人工核对。</span>
            <small>编号有效不等于结论一定正确；重要结论请点击原文核查。</small>
          </div>
          <div v-if="usedCitations.length" class="source-section">
            <h3>报告引用的资料 <span>{{ usedCitations.length }}</span></h3>
            <div class="source-grid">
              <button v-for="cite in usedCitations" :key="cite.index + '-' + cite.chunkId" type="button" @click="openCitation(cite)">
                <span class="source-index">[{{ cite.index }}]</span>
                <span class="source-detail"><strong>{{ cite.fileName }}</strong><small>{{ cite.sourceType === 'web' ? '网页来源 · ' + (cite.evidenceKind === 'page_text' ? '已读取正文' : '搜索摘录') : (cite.page ? '第 ' + cite.page + ' 页 · ' : '') + '第 ' + cite.ordinal + ' 段' }}</small></span>
                <span aria-hidden="true">↗</span>
              </button>
            </div>
          </div>
        </template>
      </section>
    </main>
    <SourceViewer v-model="sourceVisible" :citation="activeCitation" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { cancelChat, createSession, deleteSession, getCollections, getDocuments, getMessages, getSession, getSessions, updateSession } from '@/api'
import type { ChatSession, KbCollection, KbDocument } from '@/api'
import { streamChat } from '@/utils/sse'
import type { Citation } from '@/utils/sse'
import { citationAudit, MAX_RESEARCH_DOCUMENTS, RESEARCH_SESSION_MARKER, researchQuestion, researchSystemPrompt, stripReferenceTail } from '@/utils/researchReport'
import SourceViewer from '@/components/SourceViewer.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: true, typographer: true })
const collections = ref<KbCollection[]>([])
const documents = ref<KbDocument[]>([])
const sessions = ref<ChatSession[]>([])
const sessionPage = ref(1)
const sessionPages = ref(1)
const loadingMore = ref(false)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const topic = ref('')
const dimensions = ref('')
const documentSearch = ref('')
const selectedIds = ref<string[]>([])
const answerMode = ref<'AUTO' | 'LOCAL'>('LOCAL')
const currentId = ref('')
const draft = ref('')
const report = ref('')
const citations = ref<Citation[]>([])
const progressText = ref('正在查找资料并整理证据…')
const sourceVisible = ref(false)
const activeCitation = ref<Citation | null>(null)
let controller: AbortController | null = null

const filteredDocuments = computed(() => {
  const query = documentSearch.value.trim().toLowerCase()
  return documents.value.filter(doc => !query || (doc.filename + ' ' + collectionName(doc.collectionId)).toLowerCase().includes(query))
})
const selectedDocs = computed(() => selectedIds.value.map(id => documents.value.find(doc => doc.id === id)).filter((doc): doc is KbDocument => !!doc))
const canStart = computed(() => topic.value.trim().length >= 4 && selectedDocs.value.length > 0 && selectedDocs.value.length <= MAX_RESEARCH_DOCUMENTS)
const researchSessions = computed(() => sessions.value.filter(session => session.systemPrompt?.startsWith(RESEARCH_SESSION_MARKER)))
const reportTitle = computed(() => sessions.value.find(session => session.id === currentId.value)?.title || '专题研究')
const displayReport = computed(() => stripReferenceTail(busy.value ? draft.value : report.value))
const audit = computed(() => citationAudit(displayReport.value, citations.value))
const usedCitations = computed(() => citations.value.filter(cite => audit.value.used.includes(cite.index)))
const reportDocumentNames = computed(() => {
  const selected = sessions.value.find(session => session.id === currentId.value)?.knowledgeSelectionJson
  if (!selected) return []
  try {
    const ids: string[] = JSON.parse(selected).documentIds || []
    return ids.map(id => documents.value.find(doc => doc.id === id)?.filename || '已选文档')
  } catch { return [] }
})
const uncitedDocuments = computed(() => {
  const selected = sessions.value.find(session => session.id === currentId.value)?.knowledgeSelectionJson
  if (!selected) return []
  try {
    const ids: string[] = JSON.parse(selected).documentIds || []
    const used = new Set(citations.value.filter(cite => audit.value.used.includes(cite.index)).map(cite => cite.documentId))
    return ids.filter(id => !used.has(id)).map(id => documents.value.find(doc => doc.id === id)?.filename || '已选文档')
  } catch { return [] }
})
const renderedReport = computed(() => {
  let text = displayReport.value.replace(/\[(\d+)\]/g, '⟨cite⟩$1⟨/cite⟩')
  text = markdown.render(text)
  return text.replace(/⟨cite⟩(\d+)⟨\/cite⟩/g, (_full, index: string) => '<button type="button" class="report-cite" data-cite="' + index + '" aria-label="查看引用 ' + index + ' 原文">[' + index + ']</button>')
})

function collectionName(id: string) { return collections.value.find(collection => collection.id === id)?.name || '知识库' }
function formatDate(value: string) { return value ? new Date(value).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' }) : '' }
function toggleDoc(id: string) {
  if (selectedIds.value.includes(id)) selectedIds.value = selectedIds.value.filter(item => item !== id)
  else if (selectedIds.value.length < MAX_RESEARCH_DOCUMENTS) selectedIds.value = [...selectedIds.value, id]
}
async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const [collectionResponse, sessionResponse] = await Promise.all([getCollections(), getSessions(1, 100)])
    collections.value = collectionResponse.data.data || []
    sessions.value = sessionResponse.data.data.records || []
    sessionPage.value = 1
    sessionPages.value = sessionResponse.data.data.pages || 1
    const responses = await Promise.all(collections.value.map(collection => getDocuments(collection.id)))
    documents.value = responses.flatMap(response => response.data.data || [])
    selectedIds.value = selectedIds.value.filter(id => documents.value.some(doc => doc.id === id && doc.status === 'READY'))
  } catch (cause: any) {
    error.value = cause.response?.data?.message || '资料列表加载失败，请刷新重试。'
  } finally { loading.value = false }
}
async function loadOlderSessions() {
  if (loadingMore.value || sessionPage.value >= sessionPages.value) return
  loadingMore.value = true
  try {
    const next = sessionPage.value + 1
    const { data } = await getSessions(next, 100)
    sessions.value.push(...data.data.records.filter(item => !sessions.value.some(existing => existing.id === item.id)))
    sessionPage.value = next
    sessionPages.value = data.data.pages || next
  } catch { error.value = '更早的报告读取失败，请重试。' }
  finally { loadingMore.value = false }
}
async function openReport(id: string) {
  if (!id || busy.value) return
  if (!researchSessions.value.some(session => session.id === id)) {
    try {
      const { data } = await getSession(id)
      if (!data.data.systemPrompt?.startsWith(RESEARCH_SESSION_MARKER)) throw new Error('这不是专题研究会话。')
      sessions.value.unshift(data.data)
    } catch (cause: any) { error.value = cause.response?.data?.message || cause.message || '报告不存在。'; return }
  }
  currentId.value = id
  report.value = ''
  draft.value = ''
  citations.value = []
  error.value = ''
  if (route.params.sessionId !== id) await router.push('/research/' + id)
  try {
    const { data } = await getMessages(id)
    if (currentId.value !== id) return
    const message = (data.data || []).find(item => item.role === 'assistant')
    if (!message) return
    report.value = message.content || ''
    try {
      const meta = JSON.parse(message.extra || '{}')
      citations.value = Array.isArray(meta.citations) ? meta.citations : []
    } catch { citations.value = [] }
  } catch (cause: any) {
    error.value = cause.response?.data?.message || '报告读取失败。'
  }
}
async function startResearch() {
  if (!canStart.value || busy.value) return
  busy.value = true
  error.value = ''
  draft.value = ''
  report.value = ''
  citations.value = []
  progressText.value = '正在查找资料并整理证据…'
  const title = topic.value.trim()
  const chosen = [...selectedDocs.value]
  let createdId = ''
  let readyToStream = false
  try {
    const { data } = await createSession({ title: '专题研究 · ' + title.slice(0, 38), systemPrompt: researchSystemPrompt() })
    createdId = data.data.id
    const collectionIds = [...new Set(chosen.map(doc => doc.collectionId))]
    await updateSession(createdId, {
      title: '专题研究 · ' + title.slice(0, 38),
      knowledgeMode: 'SELECTED',
      knowledgeSelectionJson: JSON.stringify({ collectionIds, documentIds: chosen.map(doc => doc.id) }),
      answerMode: answerMode.value,
    })
    currentId.value = createdId
    sessions.value.unshift({ ...data.data, title: '专题研究 · ' + title.slice(0, 38), knowledgeMode: 'SELECTED', knowledgeSelectionJson: JSON.stringify({ collectionIds, documentIds: chosen.map(doc => doc.id) }), answerMode: answerMode.value })
    await router.push('/research/' + createdId)
    readyToStream = true
    controller = streamChat(createdId, researchQuestion(title, dimensions.value, chosen.length), auth.token || '', {
      onToken: text => { draft.value += text },
      onReplace: content => { draft.value = content },
      onCitations: values => { citations.value = values },
      onToolCall: call => { progressText.value = call.name.includes('web') ? '正在核查网页证据…' : '正在检索所选资料…' },
      onToolResult: () => { progressText.value = '正在组织研究报告…' },
      onDone: () => { void finishResearch(createdId, title) },
      onError: message => { if (currentId.value === createdId) { report.value = draft.value; busy.value = false; controller = null; error.value = message || '报告生成失败，请重试。' } },
    })
  } catch (cause: any) {
    busy.value = false
    error.value = cause.response?.data?.message || cause.message || '创建研究任务失败。'
    if (createdId && !readyToStream) {
      await deleteSession(createdId).catch(() => {})
      sessions.value = sessions.value.filter(item => item.id !== createdId)
      if (currentId.value === createdId) currentId.value = ''
    }
  }
}
async function finishResearch(id: string, title: string) {
  if (currentId.value !== id) return
  try {
    const { data } = await getMessages(id)
    if (currentId.value !== id) return
    const message = (data.data || []).find(item => item.role === 'assistant')
    if (message) {
      report.value = message.content || draft.value
      try {
        const meta = JSON.parse(message.extra || '{}')
        if (Array.isArray(meta.citations)) citations.value = meta.citations
      } catch { /* Keep streamed citations if metadata is malformed. */ }
    }
    await updateSession(id, { title: '专题研究 · ' + title.slice(0, 38) })
    const session = sessions.value.find(item => item.id === id)
    if (session) session.title = '专题研究 · ' + title.slice(0, 38)
  } catch { report.value = draft.value }
  finally { if (currentId.value === id) { busy.value = false; controller = null } }
  if (!report.value.trim()) error.value = '本次没有生成报告，请重试。'
}
function stopResearch() {
  if (!busy.value) return
  controller?.abort()
  controller = null
  if (currentId.value) void cancelChat(currentId.value)
  busy.value = false
  report.value = draft.value
  error.value = '已停止生成。当前草稿可能不完整。'
}
function openCitation(citation: Citation) { activeCitation.value = citation; sourceVisible.value = true }
function onReportClick(event: MouseEvent) {
  const element = (event.target as HTMLElement).closest<HTMLButtonElement>('.report-cite')
  if (!element) return
  const citation = citations.value.find(item => String(item.index) === element.dataset.cite)
  if (citation) openCitation(citation)
  else ElMessage.warning('该编号没有保存来源信息。')
}
function exportMarkdown() {
  if (!displayReport.value) return
  const used = new Set(audit.value.used)
  const appendix = citations.value.filter(cite => used.has(cite.index)).map(cite => {
    const location = cite.sourceType === 'web' ? (cite.url || '网页来源') : '第 ' + (cite.page || '?') + ' 页 · 第 ' + cite.ordinal + ' 段'
    return '- [' + cite.index + '] ' + cite.fileName + ' · ' + location
  })
  const content = '# ' + reportTitle.value + '\n\n' + displayReport.value + (appendix.length ? '\n\n---\n\n## 来源索引\n\n' + appendix.join('\n') : '') + '\n'
  const objectUrl = URL.createObjectURL(new Blob([content], { type: 'text/markdown;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = objectUrl
  anchor.download = (reportTitle.value.replace(/[\\/:*?"<>|]/g, '_').slice(0, 60) || '专题研究') + '.md'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 30000)
}

onMounted(async () => {
  await loadData()
  const id = route.params.sessionId
  if (typeof id === 'string' && id) await openReport(id)
})
watch(() => route.params.sessionId, id => { if (typeof id === 'string' && id && id !== currentId.value) void openReport(id) })
onUnmounted(() => { if (busy.value) stopResearch() })
</script>

<style scoped>
.research-page { min-height: 100vh; background: var(--bg-main); color: var(--text-primary); }
.research-topbar { height: 68px; border-bottom: 1px solid var(--border); display: flex; align-items: center; justify-content: space-between; padding: 0 clamp(18px, 3vw, 42px); background: var(--bg-main); }
.research-brand { display: flex; align-items: baseline; gap: 10px; font-size: 17px; font-weight: 700; }
.research-brand small { color: var(--text-secondary); font-size: 11px; font-weight: 400; letter-spacing: .06em; }
.brand-mark { color: var(--pulsar); font-size: 24px; }
.research-topbar nav { display: flex; gap: 8px; }
.research-topbar nav button, .report-actions button, .refresh-button, .cancel-button { border: 1px solid var(--control-border); background: var(--bg-subtle); color: var(--text-primary); border-radius: 10px; padding: 8px 12px; cursor: pointer; font: inherit; font-size: 13px; }
.research-topbar nav button:hover, .report-actions button:hover, .refresh-button:hover { border-color: var(--pulsar); color: var(--pulsar); }
.research-layout { display: grid; grid-template-columns: minmax(310px, 375px) minmax(0, 1fr); max-width: 1800px; min-height: calc(100vh - 68px); margin: auto; }
.setup-panel { border-right: 1px solid var(--border); padding: 30px 23px 40px; background: var(--bg-card); }
.panel-kicker { color: var(--pulsar); font-size: 11px; font-weight: 700; letter-spacing: .16em; text-transform: uppercase; }
.setup-panel h1 { margin: 10px 0 8px; font-size: 27px; line-height: 1.25; }
.panel-intro { color: var(--text-secondary); line-height: 1.65; font-size: 13px; margin-bottom: 24px; }
.field-label { display: flex; align-items: center; justify-content: space-between; margin: 18px 0 8px; color: var(--text-primary); font-size: 13px; font-weight: 650; }
.field-label span { color: var(--text-secondary); font-size: 11px; font-weight: 400; }
.field-heading { margin-top: 24px; }
.setup-panel textarea, .setup-panel input:not([type='checkbox']):not([type='radio']) { width: 100%; border: 1px solid var(--control-border); border-radius: 10px; background: var(--bg-input); color: var(--text-primary); font: inherit; font-size: 13px; padding: 11px 12px; outline: none; }
.setup-panel textarea:focus, .setup-panel input:not([type='checkbox']):not([type='radio']):focus { border-color: var(--pulsar); box-shadow: 0 0 0 3px rgba(224,224,224,.1); }
.setup-panel textarea::placeholder, .setup-panel input::placeholder { color: var(--text-secondary); }
.setup-panel textarea { resize: vertical; min-height: 88px; line-height: 1.55; }
.document-search-row { display: flex; gap: 7px; }
.document-search-row input { min-width: 0; }
.refresh-button { flex-shrink: 0; }
.document-list { max-height: 258px; overflow-y: auto; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-subtle); padding: 5px; }
.document-option { display: flex; align-items: center; gap: 10px; min-height: 54px; padding: 8px 10px; border-radius: 8px; cursor: pointer; }
.document-option:hover, .document-option.chosen { background: var(--bg-selected); }
.document-option.unavailable { color: var(--text-muted); cursor: not-allowed; }
.document-option input, .mode-options input { accent-color: var(--pulsar); flex-shrink: 0; }
.document-option-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.document-option-text strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; font-weight: 550; }
.document-option-text small { color: var(--text-secondary); font-size: 11px; }
.empty-documents { border: 1px dashed var(--border); border-radius: 10px; padding: 18px; line-height: 1.6; color: var(--text-secondary); font-size: 12px; }
.mode-options { display: flex; flex-wrap: wrap; gap: 8px; }
.mode-options label { display: flex; align-items: center; gap: 6px; padding: 8px 10px; border: 1px solid var(--border); border-radius: 9px; font-size: 12px; cursor: pointer; }
.mode-options label:has(input:checked) { border-color: var(--border); background: var(--bg-selected); }
.start-button { display: flex; justify-content: center; align-items: center; gap: 8px; width: 100%; margin-top: 21px; padding: 12px; border: 0; border-radius: 11px; background: var(--pulsar); color: var(--on-accent); font: inherit; font-weight: 700; cursor: pointer; transition: transform .2s ease, box-shadow .2s ease; }
.start-button:hover:not(:disabled) { transform: translateY(-2px); box-shadow: 0 10px 25px rgba(224,224,224,.16); }
.start-button:disabled { background: var(--bg-subtle); border: 1px solid #4a4a4a; color: var(--text-muted); cursor: default; }
.cancel-button { display: block; margin: 9px auto 0; }
.error-note { margin-top: 10px; color: var(--el-color-danger); font-size: 12px; line-height: 1.5; }
.recent-heading { margin: 32px 0 9px; padding-top: 20px; border-top: 1px solid var(--border); font-weight: 650; font-size: 13px; }
.recent-list { display: grid; gap: 5px; max-height: 220px; overflow-y: auto; }
.recent-list button { display: flex; justify-content: space-between; gap: 8px; width: 100%; padding: 10px; border: 0; border-radius: 8px; background: transparent; color: var(--text-secondary); text-align: left; font: inherit; font-size: 12px; cursor: pointer; }
.recent-list button:hover, .recent-list button.active { background: var(--bg-selected); color: var(--text-primary); }
.recent-list button span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.recent-list button small { flex-shrink: 0; color: var(--text-secondary); }
.more-sessions { width: 100%; margin-top: 8px; padding: 8px; border: 1px solid var(--border); border-radius: 8px; background: transparent; color: var(--text-secondary); cursor: pointer; }
.subtle { color: var(--text-secondary); font-size: 12px; line-height: 1.5; }
.report-panel { min-width: 0; padding: clamp(24px, 3vw, 48px); }
.report-toolbar { display: flex; justify-content: space-between; align-items: start; gap: 18px; padding-bottom: 18px; border-bottom: 1px solid var(--border); }
.report-toolbar h2 { margin-top: 9px; font-size: clamp(19px, 2vw, 27px); line-height: 1.35; overflow-wrap: anywhere; }
.report-scope { max-width: 750px; margin: 0; color: var(--text-secondary); font-size: 12px; line-height: 1.55; overflow-wrap: anywhere; }
.report-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; flex-shrink: 0; }
.report-actions button:disabled { background: var(--bg-subtle); border-color: #4a4a4a; color: var(--text-muted); cursor: default; }
.research-progress { display: flex; align-items: center; gap: 9px; margin: 18px 0; color: var(--text-secondary); font-size: 13px; }
.loading-dot { width: 12px; height: 12px; border: 2px solid #808080; border-top-color: #f5f5f5; border-radius: 50%; animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.report-placeholder { display: grid; align-content: center; justify-items: center; min-height: 65vh; text-align: center; color: var(--text-secondary); padding: 20px; }
.report-placeholder.compact { min-height: 220px; }
.report-placeholder h2, .report-placeholder h3 { color: var(--text-primary); margin-bottom: 10px; }
.report-placeholder p { max-width: 430px; line-height: 1.7; }
.placeholder-symbol { color: var(--pulsar); font-size: 58px; margin-bottom: 16px; }
.report-content { max-width: 990px; margin: 26px auto; color: var(--text-primary); font-size: 15px; line-height: 1.78; overflow-wrap: anywhere; }
.report-content :deep(h1), .report-content :deep(h2), .report-content :deep(h3) { margin: 24px 0 11px; line-height: 1.4; }
.report-content :deep(h1) { font-size: 25px; }
.report-content :deep(h2) { font-size: 19px; color: var(--text-primary); }
.report-content :deep(h3) { font-size: 16px; }
.report-content :deep(p), .report-content :deep(ul), .report-content :deep(ol) { margin: 0 0 13px; }
.report-content :deep(ul), .report-content :deep(ol) { padding-left: 23px; }
.report-content :deep(table) { width: 100%; border-collapse: collapse; display: block; overflow-x: auto; margin: 18px 0; font-size: 13px; }
.report-content :deep(th), .report-content :deep(td) { border: 1px solid var(--border); padding: 9px 11px; min-width: 120px; text-align: left; vertical-align: top; }
.report-content :deep(th) { background: var(--bg-selected); color: var(--text-primary); }
.report-content :deep(tr:nth-child(even) td) { background: var(--bg-subtle); }
.report-content :deep(blockquote) { border-left: 3px solid var(--pulsar); padding-left: 13px; color: var(--text-secondary); }
.report-content :deep(code) { color: var(--text-primary); background: var(--bg-selected); border-radius: 4px; padding: 1px 4px; }
.report-content :deep(a) { color: var(--pulsar); }
.report-content :deep(.report-cite) { display: inline; border: 0; background: transparent; color: var(--text-primary); font: inherit; font-size: 12px; font-weight: 700; vertical-align: super; cursor: pointer; padding: 0 2px; }
.report-content :deep(.report-cite:hover) { color: var(--text-primary); text-decoration: underline; }
.evidence-check { display: flex; flex-wrap: wrap; gap: 8px 16px; max-width: 990px; margin: 24px auto; padding: 13px 15px; border: 1px solid var(--border); border-radius: 10px; background: var(--bg-selected); font-size: 12px; }
.evidence-check.warning { border-color: var(--border); border-style: dashed; background: var(--bg-selected); }
.evidence-check small { width: 100%; color: var(--text-secondary); }
.source-section { max-width: 990px; margin: 30px auto 0; padding-top: 24px; border-top: 1px solid var(--border); }
.source-section h3 { font-size: 15px; margin-bottom: 12px; }.source-section h3 span { color: var(--text-secondary); font-size: 12px; margin-left: 6px; }
.source-grid { display: grid; gap: 7px; }
.source-grid button { display: flex; align-items: center; gap: 11px; width: 100%; border: 1px solid var(--border); background: var(--bg-subtle); color: var(--text-primary); border-radius: 9px; padding: 10px 12px; text-align: left; font: inherit; cursor: pointer; }
.source-grid button:hover { border-color: var(--pulsar); }.source-index { color: var(--pulsar); font-weight: 700; }.source-detail { display: grid; gap: 3px; flex: 1; min-width: 0; }.source-detail strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }.source-detail small { color: var(--text-secondary); font-size: 11px; }
@media (max-width: 850px) { .research-layout { grid-template-columns: 1fr; }.setup-panel { border-right: 0; border-bottom: 1px solid var(--border); }.report-panel { padding: 22px 16px 40px; }.report-toolbar { flex-direction: column; }.report-actions { justify-content: start; } }
@media (max-width: 520px) { .research-brand small { display: none; }.research-topbar { padding-inline: 13px; }.research-topbar nav button { padding: 7px 8px; font-size: 11px; }.setup-panel { padding: 22px 16px 30px; } }
@media (prefers-reduced-motion: reduce) { .loading-dot { animation: none; }.start-button { transition: none; } }
</style>
