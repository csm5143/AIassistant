<template>
  <el-dialog v-model="visible" title="引用原文" width="min(900px, 94vw)" top="3vh" @closed="releasePdf">
    <div v-if="citation" class="source-meta">
      <strong>{{ citation.fileName }}</strong>
      <span v-if="source?.page">第 {{ source.page }} 页</span>
      <span v-else-if="source?.title">{{ source.title }}</span>
      <span v-if="citation.sourceType !== 'web'">第 {{ citation.ordinal }} 段</span>
      <span v-else>🌐 联网来源</span>
    </div>
    <template v-if="citation?.sourceType === 'web'">
      <div class="source-actions">
        <span>{{ citation.evidenceKind === 'page_text' ? '已读取网页正文 · 回答时保存的证据摘录' : '搜索服务返回摘录 · 未独立读取网页正文' }}</span>
        <a v-if="webUrl" :href="webUrl" target="_blank" rel="noopener noreferrer" class="web-original">打开网页 ↗</a>
      </div>
      <p class="source-footnote">获取时间：{{ citation.retrievedAt ? new Date(citation.retrievedAt).toLocaleString() : '未记录' }}<template v-if="citation.publishedAt"> · 发布时间：{{ citation.publishedAt }}</template></p>
      <div class="source-text" tabindex="0">{{ citation.evidenceText || citation.contentSnippet }}</div>
      <p class="source-footnote">这是回答时保存的摘录，网页后续可能更新。外部信息不代表本地文档或内部规定。</p>
    </template>
    <div v-else-if="loading" class="source-status">正在加载原文…</div>
    <el-alert v-else-if="error" :title="error" type="warning" :closable="false" />
    <template v-else-if="source">
      <div class="source-actions">
        <span>{{ source.located ? `原文摘录 · 从第 ${source.firstLine} 行开始 · 高亮为引用证据` : legacyTemporary ? '历史临时引用没有版本记录，只能显示当时保存的摘要。' : '此文档没有原文定位记录，当前显示保存的切片。请重新导入以启用定位。' }}</span>
        <el-button v-if="source.originalPdf" size="small" :loading="pdfLoading" @click="togglePdf">
          {{ showPdf ? '返回文本证据' : `查看原 PDF${source.page ? ' 第 ' + source.page + ' 页' : ''}` }}
        </el-button>
        <el-button v-if="!showPdf && source.located" size="small" @click="locate">定位证据</el-button>
      </div>
      <el-alert v-if="pdfError" :title="pdfError" type="warning" :closable="false" />
      <iframe v-if="showPdf && pdfUrl" :src="pdfUrl" class="source-pdf" title="原始 PDF 文档" />
      <div v-else ref="textPanel" class="source-text" tabindex="0"><span>{{ before }}</span><mark ref="highlight">{{ evidence }}</mark><span>{{ after }}</span></div>
      <p v-if="citation?.sourceType === 'temporary'" class="source-footnote">临时文档在 30 分钟未使用后过期，过期或删除后无法再打开原文。</p>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onUnmounted } from 'vue'
import api from '@/api'
import type { Citation } from '@/utils/sse'
import { webSourceUrl } from '@/utils/webSource'

interface SourceView {
  documentId: string | null; fileName: string; ordinal: number; located: boolean
  page: number | null; title: string | null; firstLine: number; startOffset: number
  text: string; highlightStart: number; highlightEnd: number; originalPdf: boolean
}
const props = defineProps<{ modelValue: boolean; citation: Citation | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const webUrl = computed(() => webSourceUrl(props.citation?.url))
const source = ref<SourceView | null>(null)
const legacyTemporary = computed(() => !!props.citation && !props.citation.sourceVersion
  && (props.citation.sourceType === 'temporary' || props.citation.chunkId.includes('-chunk-')))
const loading = ref(false)
const error = ref('')
const highlight = ref<HTMLElement>()
const textPanel = ref<HTMLElement>()
const before = computed(() => source.value?.text.slice(0, source.value.highlightStart) || '')
const evidence = computed(() => source.value?.text.slice(source.value.highlightStart, source.value.highlightEnd) || '')
const after = computed(() => source.value?.text.slice(source.value.highlightEnd) || '')
const pdfUrl = ref('')
const pdfLoading = ref(false)
const pdfError = ref('')
const showPdf = ref(false)
let requestId = 0
let controller: AbortController | null = null
let pdfController: AbortController | null = null
let objectUrl = ''

function releasePdf() {
  pdfController?.abort()
  pdfController = null
  if (objectUrl) URL.revokeObjectURL(objectUrl)
  objectUrl = ''
  pdfUrl.value = ''
  showPdf.value = false
  pdfLoading.value = false
}
async function locate() {
  await nextTick()
  if (highlight.value && textPanel.value) {
    textPanel.value.scrollTop = Math.max(0, highlight.value.offsetTop - 100)
  }
}
watch(() => [props.modelValue, props.citation] as const, async ([open, citation]) => {
  const current = ++requestId
  controller?.abort()
  releasePdf()
  source.value = null
  error.value = ''
  pdfError.value = ''
  loading.value = false
  if (!open || !citation || citation.sourceType === 'web') return
  if (legacyTemporary.value) {
    const text = citation.contentSnippet || ''
    if (!text) { error.value = '历史临时引用没有保存原文内容。'; return }
    source.value = { documentId: null, fileName: citation.fileName, ordinal: citation.ordinal, located: false,
      page: null, title: null, firstLine: 1, startOffset: 0, text, highlightStart: 0, highlightEnd: text.length, originalPdf: false }
    return
  }
  loading.value = true
  controller = new AbortController()
  // Older temporary citations encoded the session in their chunk ID.
  const legacy = citation.chunkId.match(/^(.+)-chunk-(\d+)$/)
  const session = citation.sessionId || legacy?.[1]
  const url = citation.sourceType === 'temporary' || legacy
    ? `/document-qa/sessions/${encodeURIComponent(session || '')}/chunks/${citation.ordinal}/source${citation.sourceVersion ? '?version=' + encodeURIComponent(citation.sourceVersion) : ''}`
    : `/knowledge/chunks/${encodeURIComponent(citation.chunkId)}/source`
  try {
    const { data } = await api.get(url, { signal: controller.signal })
    if (current !== requestId) return
    if (data.data.fileName !== citation.fileName) throw new Error('该引用的文档已被替换。')
    source.value = data.data
  } catch (e: any) {
    if (current !== requestId || e.code === 'ERR_CANCELED') return
    error.value = e.response?.status === 404 ? '原文已删除、临时文档已过期，或当前账户无权查看。' : (e.response?.data?.message || '原文加载失败，请重试。')
  } finally {
    if (current === requestId) { loading.value = false; await locate() }
  }
}, { immediate: true })

async function togglePdf() {
  if (showPdf.value) { showPdf.value = false; await locate(); return }
  if (pdfUrl.value) { showPdf.value = true; return }
  const id = source.value?.documentId
  if (!id) return
  const current = requestId
  const page = source.value?.page || 1
  pdfLoading.value = true
  pdfError.value = ''
  pdfController = new AbortController()
  try {
    const { data } = await api.get(`/knowledge/documents/${encodeURIComponent(id)}/file`, {
      responseType: 'blob', signal: pdfController.signal,
    })
    if (current !== requestId || !props.modelValue) return
    objectUrl = URL.createObjectURL(new Blob([data], { type: 'application/pdf' }))
    pdfUrl.value = `${objectUrl}#page=${page}`
    showPdf.value = true
  } catch (e: any) {
    if (current === requestId && e.code !== 'ERR_CANCELED') pdfError.value = '原 PDF 加载失败，仍可查看文本证据。'
  } finally { if (current === requestId) pdfLoading.value = false }
}
onUnmounted(() => { controller?.abort(); releasePdf() })
</script>

<style scoped>
.source-meta { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-bottom: 14px; }
.source-meta span, .source-actions, .source-footnote { color: var(--el-text-color-secondary); font-size: 13px; }
.source-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-bottom: 12px; }
.source-actions span { flex: 1; min-width: 240px; }
.source-text { position: relative; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; max-height: 65vh; overflow-y: auto; padding: 18px; border: 1px solid var(--el-border-color); border-radius: 10px; }
.source-text mark { background: #e0e0e0; color: #303030; border-radius: 3px; }
.source-status { padding: 40px; text-align: center; }
.source-pdf { width: 100%; height: 65vh; border: 0; }
.source-footnote { margin: 12px 0 0; }
.web-original { display: inline-flex; padding: 7px 12px; color: var(--el-color-primary); background: var(--el-color-primary-light-9); border-radius: 6px; text-decoration: none; }
</style>
