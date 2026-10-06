<template>
  <div class="kb-layout">
    <aside class="kb-sidebar">
      <div class="kb-sidebar-header">
        <button class="back-btn" @click="$router.push('/chat')">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>
          <span>返回</span>
        </button>
      </div>
      <div class="kb-sidebar-title">知识库</div>
      <div class="collection-list">
        <div
          v-for="c in store.collections"
          :key="c.id"
          :class="['coll-item', { active: selectedId === c.id }]"
          @click="selectCollection(c.id)"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>
          <span class="coll-name">{{ c.name }}</span>
          <span class="coll-count">{{ c.documentCount }}</span>
          <el-button :icon="Delete" circle size="small" text @click.stop="handleDeleteColl(c.id)" class="coll-del"/>
        </div>
      </div>
      <div class="kb-sidebar-footer">
        <button class="new-coll-btn" @click="showCreate = true">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          <span>新建知识库</span>
        </button>
      </div>
    </aside>

    <main class="kb-main">
      <template v-if="selectedId">
        <div class="kb-toolbar">
          <h2>{{ selectedColl?.name }}</h2>
          <div class="kb-toolbar-actions">
            <el-button @click="$router.push('/research')">专题研究</el-button>
            <el-button :icon="FolderOpened" :disabled="folderImporting" @click="showImportPanel = true; importTab = 'folder'">导入文件夹</el-button>
            <el-button v-if="!selectedColl?.folderPath" :icon="Folder" @click="openFolderBinding">绑定文件夹</el-button>
            <el-button @click="showImportPanel = !showImportPanel" :icon="Download">批量导入</el-button>
            <el-upload
              :action="`/api/knowledge/collections/${selectedId}/documents`"
              :headers="{ Authorization: `Bearer ${auth.token}` }"
              name="file"
              multiple
              :on-success="handleUploadSuccess"
              :on-error="handleUploadError"
              :before-upload="beforeDocumentUpload"
              :show-file-list="false"
              accept=".pdf,.txt,.md,.doc,.docx,.xls,.xlsx"
            >
              <el-button type="primary" :icon="Upload" title="单个文档最大 100 MB">上传文档</el-button>
            </el-upload>
            <el-button
              v-if="checkedIds.size > 0"
              type="warning"
              :icon="Download"
              @click="showExportDialog = true"
            >
              批量导出 ({{ checkedIds.size }})
            </el-button>
          </div>
        </div>

        <div class="folder-storage">
          <template v-if="selectedColl?.folderPath">
            <div class="folder-storage-info">
              <span class="folder-mode">已绑定文件夹</span>
              <span class="folder-path" :title="selectedColl.folderPath">{{ selectedColl.folderPath }}</span>
              <span class="folder-sync-time">{{ selectedColl.folderSyncedAt ? `上次同步：${formatSyncTime(selectedColl.folderSyncedAt)}` : '尚未同步' }}</span>
            </div>
            <div class="folder-controls">
              <el-button size="small" :icon="RefreshRight" :loading="folderSyncing" :disabled="folderUnbinding" @click="handleFolderSync">同步文件夹</el-button>
              <el-button size="small" :loading="folderUnbinding" :disabled="folderSyncing" @click="handleFolderUnbind">解除绑定</el-button>
            </div>
          </template>
          <span v-else>文档默认保存到 <span class="folder-path">D:\AIassistant-env\runtime\uploads\knowledge\</span></span>
        </div>
        <div v-if="processingSummary.active || processingSummary.failed" class="processing-summary" role="status" aria-live="polite">
          <span>本知识库：{{ processingSummary.ready }} 个已完成</span>
          <span v-if="processingSummary.running">{{ processingSummary.running }} 个处理中</span>
          <span v-if="processingSummary.queued">{{ processingSummary.queued }} 个排队中</span>
          <span v-if="processingSummary.failed">{{ processingSummary.failed }} 个失败</span>
          <span class="processing-summary-hint">{{ processingSummary.active ? '排队任务会自动继续处理' : '失败文档可点击重试' }}</span>
        </div>
        <div v-if="syncResult && syncResultCollection === selectedId" class="sync-summary">
          <span>同步结果：新增 {{ syncResult.added }} · 更新 {{ syncResult.updated }} · 移除 {{ syncResult.removed }} · 未变化 {{ syncResult.unchanged }} · 跳过/失败 {{ syncResult.skipped }}</span>
          <p class="import-hint">新增和更新的文档已加入处理队列。原文件中的变更需要再次点击同步。</p>
          <details v-if="syncResult.entries.some(e => e.error)">
            <summary>查看未完成的文件</summary>
            <div v-for="entry in syncResult.entries.filter(e => e.error)" :key="entry.path">{{ entry.path }}：{{ entry.error }}</div>
          </details>
        </div>

        <!-- === Import Panel === -->
        <div v-if="showImportPanel" class="import-panel">
          <el-tabs v-model="importTab">
            <el-tab-pane label="文件夹导入" name="folder">
              <div class="import-section">
                <p class="import-hint">选择文件夹及子文件夹中的文档，复制到 D 盘默认目录。保留文件相对路径，原文件不会被修改；后续变更可通过绑定文件夹同步。</p>
                <p class="import-hint">支持 PDF、TXT、Markdown、Word、Excel，单个文件最大 100 MB，单次最多 1000 个文档。</p>
                <input ref="folderInput" type="file" webkitdirectory multiple class="folder-input" @change="handleFolderImport" />
                <el-button type="primary" :icon="FolderOpened" :disabled="folderImporting" @click="folderInput?.click()">选择文件夹</el-button>
                <el-button v-if="folderImporting" :disabled="folderImportStopped" @click="folderImportStopped = true">停止后续导入</el-button>
                <div v-if="folderImportTotal" class="folder-import-progress">
                  <el-progress :percentage="Math.round(folderImportDone / folderImportTotal * 100)" />
                  <span>{{ folderImporting ? '正在导入' : folderImportStopped ? '已停止' : '导入结束' }}：{{ folderImportDone }} / {{ folderImportTotal }} 个文件；成功上传后仍需等待文档处理。</span>
                </div>
              </div>
            </el-tab-pane>
            <el-tab-pane label="网页导入" name="url">
              <div class="import-section">
                <el-input
                  v-model="urlInput"
                  type="textarea"
                  :rows="4"
                  placeholder="粘贴网页链接，每行一个&#10;https://example.com/doc1&#10;https://example.com/doc2"
                />
                <div class="import-options">
                  <el-checkbox v-model="followLinks">跟随站内链接（爬取同域名页面）</el-checkbox>
                  <span v-if="followLinks" class="import-sub">最多爬取
                    <el-input-number v-model="maxPages" :min="1" :max="50" size="small" style="width:80px" />
                    页
                  </span>
                </div>
                <el-button type="primary" :loading="urlImporting" @click="handleUrlImport" :disabled="!urlInput.trim()">
                  {{ followLinks ? '开始爬取' : '导入网页' }}
                </el-button>
              </div>
            </el-tab-pane>
            <el-tab-pane label="粘贴文本" name="paste">
              <div class="import-section">
                <el-input v-model="pasteTitle" placeholder="标题（可选）" style="margin-bottom:10px" />
                <el-input
                  v-model="pasteContent"
                  type="textarea"
                  :rows="10"
                  placeholder="粘贴网页正文、题目、文档内容…&#10;支持 Markdown 格式&#10;适用于无法自动抓取的动态页面（如牛客网、SPA 应用）"
                />
                <el-button type="primary" :loading="pasteImporting" @click="handlePasteImport" :disabled="!pasteContent.trim()">
                  导入文本
                </el-button>
              </div>
            </el-tab-pane>
            <el-tab-pane label="批量文件" name="batch">
              <div class="import-section">
                <p class="import-hint">拖拽多个文件到下方区域，单个文档最大 100 MB</p>
                <el-upload
                  :action="`/api/knowledge/collections/${selectedId}/import-batch`"
                  :headers="{ Authorization: `Bearer ${auth.token}` }"
                  name="files"
                  multiple
                  drag
                  :on-success="handleBatchSuccess"
                  :on-error="handleUploadError"
                  :before-upload="beforeDocumentUpload"
                  accept=".pdf,.txt,.md,.doc,.docx,.xls,.xlsx"
                >
                  <el-icon :size="40"><UploadFilled /></el-icon>
                  <div>将文件拖到此处，或<em>点击选择</em></div>
                </el-upload>
              </div>
            </el-tab-pane>
          </el-tabs>

          <!-- Import results -->
          <div v-if="importResults.length > 0" class="import-results">
            <div class="import-results-header">
              <span>导入结果（{{ importSuccess }} 成功 / {{ importFailed }} 失败 / {{ importSkipped }} 跳过）</span>
              <el-button text size="small" @click="importResults = []">清除</el-button>
            </div>
            <div v-for="(r, i) in importResults" :key="i" class="import-result-item" :class="'result--' + r.status">
              <span v-if="r.status === 'imported'" class="result-icon">✓</span>
              <span v-else-if="r.status === 'skipped'" class="result-icon">–</span>
              <span v-else class="result-icon">✗</span>
              <span class="result-name">{{ r.filename || r.title || r.url }}</span>
              <span v-if="r.chars" class="result-chars">{{ r.chars }} 字</span>
              <span v-if="r.error" class="result-error">{{ r.error }}</span>
            </div>
          </div>
        </div>

        <!-- Search bar -->
        <div v-if="selectedId" class="kb-search-bar">
          <el-input
            v-model="searchQuery"
            placeholder="搜索文档名或内容…"
            :prefix-icon="Search"
            clearable
            @input="onSearchInput"
            class="search-input"
          />
          <div v-if="searchQuery" class="search-stats">
            找到 {{ filteredDocs.length }} 个文档
          </div>
        </div>

        <!-- Select all bar -->
        <div v-if="selectedId && store.documents.length > 0" class="select-bar">
          <el-checkbox
            :model-value="allChecked"
            :indeterminate="someChecked"
            @change="toggleAll"
          >
            全选（{{ checkedIds.size }}/{{ store.documents.length }}）
          </el-checkbox>
          <span v-if="checkedIds.size > 0" class="select-clear" @click="checkedIds = new Set()">清除选择</span>
        </div>

        <div class="doc-grid" v-if="filteredDocs.length > 0">
          <div v-for="doc in filteredDocs" :key="doc.id" class="doc-card" :class="{ 'doc--selected': checkedIds.has(doc.id) }" @click="previewDoc(doc)">
            <div class="doc-check" @click.stop>
              <el-checkbox
                :model-value="checkedIds.has(doc.id)"
                @change="toggleCheck(doc.id)"
              />
            </div>
            <div class="doc-icon" :class="'doc-icon--' + doc.status.toLowerCase()">
              <img :src="fileIcon(doc.filename)" alt="" class="doc-icon-img" />
            </div>
            <div class="doc-info">
              <div class="doc-name" :title="doc.filename" v-html="highlightText(doc.filename)"></div>
              <div v-if="doc.sourceRelativePath" class="doc-source-path" :title="doc.sourceRelativePath">{{ doc.sourceKind === 'FOLDER' ? '绑定 · ' : '导入 · ' }}{{ doc.sourceRelativePath }}</div>
              <div class="doc-meta">
                <span class="status-tag" :class="'status--' + doc.status.toLowerCase()">
                  <span v-if="['PROCESSING', 'VECTORIZING'].includes(doc.status)" class="status-pulse"></span>
                  <el-icon v-if="['PROCESSING', 'VECTORIZING'].includes(doc.status)" class="status-spin-icon"><Loading /></el-icon>
                  {{ statusLabel(doc) }}
                </span>
                <span v-if="doc.status === 'READY' || doc.status === 'VECTORIZING'" class="doc-meta-text">{{ doc.chunkCount || 0 }} 分块</span>
                <el-tooltip v-if="['FAILED', 'VECTOR_FAILED'].includes(doc.status) && doc.errorMessage" :content="doc.errorMessage" placement="top">
                  <span class="doc-error">错误详情</span>
                </el-tooltip>
              </div>
              <div v-if="documentProgressLabel(doc)" class="doc-progress-label">{{ documentProgressLabel(doc) }}</div>
              <el-progress v-if="documentProgressPercent(doc) !== null" class="doc-progress-bar"
                :percentage="documentProgressPercent(doc)!" :show-text="false" :stroke-width="4" />
              <PdfParseReport :report="doc.parseReportJson" />
            </div>
            <div class="doc-actions" @click.stop>
              <el-button class="doc-action doc-action--preview" :icon="View" size="small" :aria-label="`预览 ${doc.filename}`" @click="previewDoc(doc)">预览</el-button>
              <el-button class="doc-action" :icon="Download" size="small" title="选择格式并下载" :aria-label="`下载 ${doc.filename}`" @click="openSingleExport(doc)">下载</el-button>
              <el-popconfirm :title="doc.sourceKind === 'FOLDER' ? '仅移除知识库记录，原文件保留；同步后会重新收录。继续？' : '删除此文档？'" @confirm="handleDeleteDoc(doc.id)">
                <template #reference>
                  <el-button class="doc-action doc-action--delete" :icon="Delete" size="small" :aria-label="`删除 ${doc.filename}`">删除</el-button>
                </template>
              </el-popconfirm>
              <el-popconfirm v-if="doc.status === 'READY' && doc.filename.toLowerCase().endsWith('.pdf')" title="使用增强解析替换索引？旧回答中的片段链接可能失效。" @confirm="handleReparseDoc(doc.id)">
                <template #reference><el-button class="doc-action doc-action--secondary" :icon="RefreshRight" size="small">重新解析</el-button></template>
              </el-popconfirm>
              <el-button v-if="['FAILED', 'VECTOR_FAILED'].includes(doc.status)" class="doc-action doc-action--secondary" :icon="RefreshRight" size="small" @click="handleRetryDoc(doc.id)">重试处理</el-button>
            </div>
          </div>
        </div>
        <div v-else class="empty-docs">
          <template v-if="searchQuery">
            <el-icon :size="48"><Search /></el-icon>
            <p>没有匹配的文档</p>
            <p class="empty-sub">试试其他关键词</p>
          </template>
          <template v-else>
            <el-icon :size="48"><FolderOpened /></el-icon>
            <p>暂无文档，上传 PDF、TXT、Markdown 等文件</p>
          </template>
        </div>
      </template>
      <div v-else class="empty-docs">
        <el-icon :size="48"><Collection /></el-icon>
        <p>选择左侧知识库或创建新的</p>
      </div>
    </main>

    <el-dialog v-model="showFolderBinding" title="绑定资料文件夹" width="640px" :close-on-click-modal="false" :close-on-press-escape="!folderBinding" :show-close="!folderBinding">
      <p class="import-hint">从允许的本机资料目录中选择文件夹，读取其中文档建立知识库。点击“同步文件夹”更新新增、修改、移除的文件；不会删除原文件。</p>
      <div v-if="folderBrowseError" class="folder-browse-error">{{ folderBrowseError }}</div>
      <template v-if="folderListing?.enabled">
        <div class="folder-path-input">
          <el-input v-model="folderPathInput" placeholder="输入资料文件夹完整路径" :disabled="folderBinding" @keyup.enter="loadFolderDirectory(folderPathInput)" />
          <el-button :loading="folderBrowsing" :disabled="folderBinding" @click="loadFolderDirectory(folderPathInput)">打开</el-button>
        </div>
        <div class="folder-navigation">
          <el-button text :disabled="folderBinding || folderBrowsing" @click="loadFolderDirectory()">资料目录</el-button>
          <el-button v-if="folderListing.parent" text :disabled="folderBinding || folderBrowsing" @click="loadFolderDirectory(folderListing.parent!)">上一级</el-button>
          <span class="folder-path">{{ folderListing.path || '选择一个资料目录' }}</span>
        </div>
        <div class="folder-directory-list" v-loading="folderBrowsing">
          <button v-for="dir in (folderListing.path ? folderListing.directories : folderListing.roots)" :key="dir.path" :disabled="folderBinding || folderBrowsing" class="folder-directory" @click="loadFolderDirectory(dir.path)">
            <el-icon><Folder /></el-icon><span>{{ dir.name }}</span>
          </button>
          <p v-if="folderListing.path && !folderListing.directories.length" class="import-hint">此文件夹没有子文件夹，可以直接绑定当前文件夹。</p>
        </div>
      </template>
      <p v-else-if="folderListing && !folderListing.enabled" class="import-hint">尚未配置可绑定的资料目录，可先使用文件夹导入。</p>
      <template #footer>
        <el-button :disabled="folderBinding" @click="showFolderBinding = false">取消</el-button>
        <el-button type="primary" :loading="folderBinding" :disabled="!folderListing?.path || folderBrowsing || !!folderBrowseError" @click="handleFolderBind">{{ folderBinding ? '扫描并收录中…' : '绑定当前文件夹' }}</el-button>
      </template>
    </el-dialog>

    <!-- Preview Dialog -->
    <el-dialog v-model="showPreview" :title="previewDocData?.filename" width="900px" top="3vh" :close-on-click-modal="false">
      <div class="preview-container">
        <!-- PDF -->
        <iframe
          v-if="isPreviewPdf && previewBlobUrl"
          :src="previewBlobUrl"
          class="preview-frame"
          frameborder="0"
        />
        <!-- Image -->
        <div v-else-if="isPreviewImage && previewBlobUrl" class="preview-image-wrap">
          <img :src="previewBlobUrl" class="preview-image" />
        </div>
        <!-- Markdown / Text -->
        <div v-else class="preview-text-wrap">
          <div v-if="previewLoading" class="preview-loading">加载中…</div>
          <div v-else-if="previewError" class="preview-error">{{ previewError }}</div>
          <div v-else class="preview-content" v-html="previewHtml"></div>
        </div>
      </div>
    </el-dialog>

    <!-- Export Format Dialog -->
    <el-dialog v-model="showExportDialog" :title="exportMode === 'batch' ? `批量导出 ${checkedIds.size} 个文档` : `导出文档：${exportDoc?.filename}`" width="480px" :close-on-click-modal="false">
      <div class="export-body">
        <div class="export-section-label">选择导出格式</div>
        <div class="export-grid">
          <button
            v-for="fmt in exportFormats"
            :key="fmt.value"
            :class="['export-card', { selected: exportFormat === fmt.value }]"
            @click="exportFormat = fmt.value"
          >
            <img :src="fmt.icon" :alt="fmt.label" class="export-card-icon" />
            <span class="export-card-label">{{ fmt.label }}</span>
            <span class="export-card-desc">{{ fmt.desc }}</span>
          </button>
        </div>
      </div>
      <template #footer>
        <el-button @click="showExportDialog = false">取消</el-button>
        <el-button type="primary" :loading="exporting" @click="handleExport">
          {{ exporting ? '导出中…' : '确认导出' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Create Collection Dialog -->
    <el-dialog v-model="showCreate" title="新建知识库" width="420px" :close-on-click-modal="false">
      <el-form :model="createForm" label-position="top">
        <el-form-item label="名称">
          <el-input v-model="createForm.name" placeholder="输入知识库名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" :rows="3" placeholder="可选描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="handleCreateColl">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useKnowledgeStore } from '@/stores/knowledge'
import { ArrowLeft, Plus, Folder, Delete, Upload, Document, FolderOpened, Collection, Download, UploadFilled, Search, View, Loading, RefreshRight } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { importFromUrls, retryDocument, exportDocument, exportDocumentsBatch, getCollectionDocumentProgress, type KbDocument } from '@/api'
import PdfParseReport from '@/components/PdfParseReport.vue'
import api, { browseKnowledgeFolders, bindKnowledgeFolder, syncKnowledgeFolder, unbindKnowledgeFolder, type LocalFolderListing, type FolderSyncResult } from '@/api'
import { folderFilePath, folderFileError } from '@/utils/folderImport'
import { documentUploadError } from '@/utils/upload'
import MarkdownIt from 'markdown-it'
const md = new MarkdownIt({ breaks: true, linkify: true })

const auth = useAuthStore()
const store = useKnowledgeStore()
const selectedId = ref<string | null>(null)
const processingSummary = computed(() => {
  const docs = store.documents
  const queued = docs.filter(d => d.status === 'PENDING').length
  const running = docs.filter(d => d.status === 'PROCESSING' || d.status === 'VECTORIZING').length
  return {
    queued, running, active: queued + running,
    ready: docs.filter(d => d.status === 'READY').length,
    failed: docs.filter(d => d.status === 'FAILED' || d.status === 'VECTOR_FAILED').length,
  }
})
const showCreate = ref(false)
const createForm = ref({ name: '', description: '' })

// Search
const searchQuery = ref('')
const searchDebounce = ref<ReturnType<typeof setTimeout> | null>(null)

const filteredDocs = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return store.documents
  const keywords = q.split(/\s+/).filter(Boolean)
  return store.documents.filter(doc => {
    const name = doc.filename.toLowerCase()
    return keywords.some(kw => name.includes(kw))
  })
})

// Export
const checkedIds = ref<Set<string>>(new Set())
const showExportDialog = ref(false)
const exportFormat = ref('md')
const exporting = ref(false)
const exportMode = ref<'single' | 'batch'>('batch')
const exportDoc = ref<typeof store.documents[0] | null>(null)

const exportFormats = [
  { value: 'pdf',  label: 'PDF',  desc: '排版良好的便携文档', icon: '/PDF.svg' },
  { value: 'md',   label: 'MD',   desc: '保留原始 Markdown 格式', icon: '/md.svg' },
  { value: 'docx', label: 'DOCX', desc: '可编辑的 Word 文档', icon: '/WORD.svg' },
]

function toggleCheck(id: string) {
  const next = new Set(checkedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  checkedIds.value = next
}

function openSingleExport(doc: typeof store.documents[0]) {
  exportMode.value = 'single'
  exportDoc.value = doc
  exportFormat.value = 'md'
  showExportDialog.value = true
}

async function handleExport() {
  exporting.value = true
  try {
    if (exportMode.value === 'single' && exportDoc.value) {
      const resp = await exportDocument(exportDoc.value.id, exportFormat.value)
      downloadBlob(resp.data as Blob, resp.headers)
    } else {
      const resp = await exportDocumentsBatch([...checkedIds.value], exportFormat.value)
      downloadBlob(resp.data as Blob, resp.headers)
    }
    ElMessage.success('导出成功')
    showExportDialog.value = false
    checkedIds.value = new Set()
  } catch (e: any) {
    const msg = e.response?.data?.message || e.message || '导出失败'
    ElMessage.error(msg)
  } finally {
    exporting.value = false
  }
}

function downloadBlob(blob: Blob, headers: any) {
  const disposition = (headers['content-disposition'] || '') as string
  const match = disposition.match(/filename\*?=(?:UTF-8'')?(.+)/)
  const filename = match ? decodeURIComponent(match[1]) : `export.${exportFormat.value}`
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

function onSearchInput() {
  if (searchDebounce.value) clearTimeout(searchDebounce.value)
  searchDebounce.value = setTimeout(() => {
    // Trigger chunk search for content matching (async, non-blocking)
    if (searchQuery.value.trim() && selectedId.value) {
      // The vector search is backend, but for now doc name filter works client-side
    }
  }, 300)
}

function highlightText(text: string): string {
  const q = searchQuery.value.trim()
  if (!q || !text) return escapeHtml(text)
  const keywords = q.split(/\s+/).filter(k => k.length > 0)
  let result = escapeHtml(text)
  for (const kw of keywords) {
    const escaped = escapeHtml(kw).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    result = result.replace(new RegExp(`(${escaped})`, 'gi'), '<mark class="hl">$1</mark>')
  }
  return result
}

function fileIcon(filename: string): string {
  const ext = filename?.split('.').pop()?.toLowerCase() || ''
  const map: Record<string, string> = {
    pdf:  '/PDF.svg',
    doc:  '/WORD.svg',
    docx: '/WORD.svg',
    xls:  '/Microsoft-Excel.svg',
    xlsx: '/Microsoft-Excel.svg',
    ppt:  '/PPT.svg',
    pptx: '/PPT.svg',
    md:   '/md.svg',
    txt:  '/txt.svg',
  }
  return map[ext] || '/其他文件.svg'
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

// ── Preview ──
const showPreview = ref(false)
const previewDocData = ref<typeof store.documents[0] | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
const previewHtml = ref('')
const previewBlobUrl = ref('')

const isPreviewPdf = computed(() => {
  const f = previewDocData.value?.filename?.toLowerCase() || ''
  return f.endsWith('.pdf') || (previewDocData.value?.mimeType || '').includes('pdf')
})
const isPreviewImage = computed(() => {
  const f = previewDocData.value?.filename?.toLowerCase() || ''
  return f.match(/\.(jpg|jpeg|png|gif|webp|svg|bmp)$/) || (previewDocData.value?.mimeType || '').startsWith('image/')
})

async function previewDoc(doc: typeof store.documents[0]) {
  // Clean up old blob
  if (previewBlobUrl.value) URL.revokeObjectURL(previewBlobUrl.value)
  previewDocData.value = doc
  showPreview.value = true
  previewBlobUrl.value = ''
  previewLoading.value = false
  previewError.value = ''
  previewHtml.value = ''

  // PDF/Images: fetch via auth and create blob URL
  if (isPreviewPdf.value || isPreviewImage.value) {
    await loadBlobPreview(doc.id)
    return
  }

  // Text/MD content
  previewLoading.value = true
  try {
    const resp = await fetch(`/api/knowledge/documents/${doc.id}/file`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    })
    if (!resp.ok) throw new Error('HTTP ' + resp.status)
    const text = await resp.text()
    const mime = resp.headers.get('content-type') || ''
    if (mime.includes('markdown') || doc.filename?.endsWith('.md')) {
      previewHtml.value = md.render(text)
    } else {
      previewHtml.value = `<pre class="preview-text-block">${escapeHtml(text)}</pre>`
    }
  } catch (e: any) {
    previewError.value = '无法加载: ' + (e.message || '未知错误')
  } finally {
    previewLoading.value = false
  }
}

async function loadBlobPreview(docId: string) {
  previewLoading.value = true
  try {
    const resp = await fetch(`/api/knowledge/documents/${docId}/file`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    })
    if (!resp.ok) throw new Error('HTTP ' + resp.status)
    const blob = await resp.blob()
    if (previewBlobUrl.value) URL.revokeObjectURL(previewBlobUrl.value)
    previewBlobUrl.value = URL.createObjectURL(blob)
  } catch (e: any) {
    previewError.value = '无法加载: ' + (e.message || '未知错误')
  } finally {
    previewLoading.value = false
  }
}

async function handleDeleteDoc(docId: string) {
  try {
    await api.delete(`/knowledge/documents/${docId}`)
    ElMessage.success('已删除')
    store.fetchDocuments(selectedId.value!)
    store.fetchCollections()
  } catch {
    ElMessage.error('删除失败')
  }
}

// Import panel
const showImportPanel = ref(false)
const importTab = ref('url')
const urlInput = ref('')
const followLinks = ref(false)
const maxPages = ref(10)
const urlImporting = ref(false)
const pasteTitle = ref('')
const pasteContent = ref('')
const pasteImporting = ref(false)
const importResults = ref<{ url?: string; filename?: string; title?: string; status: string; chars?: number; error?: string }[]>([])
const importSuccess = computed(() => importResults.value.filter(r => r.status === 'imported').length)
const importFailed = computed(() => importResults.value.filter(r => r.status === 'failed').length)
const importSkipped = computed(() => importResults.value.filter(r => r.status === 'skipped').length)

async function handlePasteImport() {
  const content = pasteContent.value.trim()
  if (!content || !selectedId.value) return
  pasteImporting.value = true
  try {
    const { data } = await api.post(`/knowledge/collections/${selectedId.value}/import-paste`, {
      title: pasteTitle.value.trim() || '粘贴导入 ' + new Date().toLocaleDateString(),
      content,
    })
    if (data.data) {
      importResults.value = [{ title: pasteTitle.value || '文本', status: 'imported', chars: content.length }]
      ElMessage.success('导入成功')
      pasteTitle.value = ''
      pasteContent.value = ''
      await store.fetchDocuments(selectedId.value!)
      store.fetchCollections()
      startPolling()
    }
  } catch (e: any) {
    ElMessage.error('导入失败: ' + (e.response?.data?.message || e.message))
  } finally {
    pasteImporting.value = false
  }
}

async function handleUrlImport() {
  const lines = urlInput.value.split('\n').map(l => l.trim()).filter(Boolean)
  if (lines.length === 0) return
  urlImporting.value = true
  importResults.value = []
  try {
    const { data } = await importFromUrls(selectedId.value!, {
      urls: lines,
      followLinks: followLinks.value,
      maxPages: maxPages.value,
    })
    if (data.data) {
      importResults.value = data.data.results || []
      ElMessage.success(`导入完成：${data.data.successCount} 成功 / ${data.data.failCount} 失败`)
      store.fetchDocuments(selectedId.value!)
      store.fetchCollections()
      startPolling()
    }
  } catch (e: any) {
    ElMessage.error('导入失败: ' + (e.response?.data?.message || e.message))
  } finally {
    urlImporting.value = false
  }
}

function handleBatchSuccess(response: any) {
  const results = response?.data?.data || response?.data || response
  if (Array.isArray(results)) {
    importResults.value = results.map((r: any) => ({
      filename: r.filename, status: r.status === 'imported' ? 'imported' : 'failed', error: r.error,
    }))
  }
  ElMessage.success('批量上传完成')
  store.fetchDocuments(selectedId.value!)
  store.fetchCollections()
  startPolling()
}

const selectedColl = computed(() => store.collections.find(c => c.id === selectedId.value))

const folderInput = ref<HTMLInputElement | null>(null)
const folderImporting = ref(false)
const folderImportStopped = ref(false)
const folderImportDone = ref(0)
const folderImportTotal = ref(0)
const showFolderBinding = ref(false)
const folderListing = ref<LocalFolderListing | null>(null)
const folderPathInput = ref('')
const folderBrowsing = ref(false)
const folderBrowseError = ref('')
const folderBinding = ref(false)
const folderTargetId = ref<string | null>(null)
const folderSyncing = ref(false)
const folderUnbinding = ref(false)
const syncResult = ref<FolderSyncResult | null>(null)
const syncResultCollection = ref<string | null>(null)
let folderBrowseRequest = 0
function formatSyncTime(value: string) { return value.replace('T', ' ').slice(0, 19) }
function folderError(e: any): string { return e.response?.data?.message || e.message || '操作失败' }
async function refreshFolderCollection(id: string) {
  await store.fetchCollections()
  if (selectedId.value === id) { await store.fetchDocuments(id); startPolling() }
}
async function handleFolderImport(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  const id = selectedId.value
  input.value = ''
  if (!id || !files.length || folderImporting.value) return
  if (files.filter(f => !folderFileError(f)).length > 1000) { ElMessage.error('单次最多导入1000个文档，请选择较小的文件夹'); return }
  folderImporting.value = true
  folderImportStopped.value = false
  folderImportDone.value = 0
  folderImportTotal.value = files.length
  importResults.value = []
  let success = 0
  try {
    for (const file of files) {
      if (folderImportStopped.value) break
      const path = folderFilePath(file)
      const error = folderFileError(file)
      if (error) importResults.value.push({ filename: path, status: 'skipped', error })
      else {
        const form = new FormData()
        form.append('file', file)
        form.append('relativePath', path)
        try {
          await api.post(`/knowledge/collections/${id}/documents`, form, { timeout: 300000 })
          importResults.value.push({ filename: path, status: 'imported' })
          success++
        } catch (e: any) {
          importResults.value.push({ filename: path, status: 'failed', error: folderError(e) })
          if (e.response?.status === 401 || e.response?.status === 403 || e.response?.status === 404) { folderImportStopped.value = true; break }
        }
      }
      folderImportDone.value++
    }
    ElMessage.success(`已导入 ${success} 个文档，正在处理`)
    await refreshFolderCollection(id)
  } catch (e: any) { ElMessage.error(folderError(e)) }
  finally { folderImporting.value = false }
}
async function loadFolderDirectory(path?: string) {
  const request = ++folderBrowseRequest
  folderBrowsing.value = true
  folderBrowseError.value = ''
  try {
    const { data } = await browseKnowledgeFolders(path?.trim() || undefined)
    if (request !== folderBrowseRequest) return
    folderListing.value = data.data
    folderPathInput.value = data.data.path || ''
  } catch (e: any) { if (request === folderBrowseRequest) folderBrowseError.value = folderError(e) }
  finally { if (request === folderBrowseRequest) folderBrowsing.value = false }
}
async function openFolderBinding() {
  folderTargetId.value = selectedId.value
  folderListing.value = null
  folderPathInput.value = ''
  showFolderBinding.value = true
  await loadFolderDirectory()
}
async function handleFolderBind() {
  const id = folderTargetId.value, path = folderListing.value?.path
  if (!id || !path || folderBinding.value) return
  folderBinding.value = true
  try {
    const { data } = await bindKnowledgeFolder(id, path)
    syncResult.value = data.data; syncResultCollection.value = id
    showFolderBinding.value = false
    ElMessage.success(`已绑定，新增 ${data.data.added} 个文档`)
  } catch (e: any) { ElMessage.error(folderError(e)) }
  finally { folderBinding.value = false; await refreshFolderCollection(id).catch(() => {}) }
}
async function handleFolderSync() {
  const id = selectedId.value
  if (!id || folderSyncing.value) return
  folderSyncing.value = true
  try {
    const { data } = await syncKnowledgeFolder(id)
    syncResult.value = data.data; syncResultCollection.value = id
    ElMessage.success('同步完成，新增和更新文档已加入处理队列')
  } catch (e: any) { ElMessage.error(folderError(e)) }
  finally { folderSyncing.value = false; await refreshFolderCollection(id).catch(() => {}) }
}
async function handleFolderUnbind() {
  const id = selectedId.value
  if (!id || folderUnbinding.value) return
  try {
    await ElMessageBox.confirm('将已收录的绑定文件复制到 D 盘默认目录并保留知识库内容。原文件仍保留在资料文件夹。', '解除文件夹绑定', { confirmButtonText: '解除并保存副本', cancelButtonText: '取消' })
  } catch { return }
  folderUnbinding.value = true
  try {
    await unbindKnowledgeFolder(id)
    syncResult.value = null
    ElMessage.success('已解除绑定，文档副本保存到 D 盘')
  } catch (e: any) { ElMessage.error(folderError(e)) }
  finally { folderUnbinding.value = false; await refreshFolderCollection(id).catch(() => {}) }
}


const allChecked = computed(() =>
  store.documents.length > 0 && checkedIds.value.size === store.documents.length
)
const someChecked = computed(() =>
  checkedIds.value.size > 0 && checkedIds.value.size < store.documents.length
)

function toggleAll() {
  if (checkedIds.value.size === store.documents.length) {
    checkedIds.value = new Set()
  } else {
    checkedIds.value = new Set(store.documents.map(d => d.id))
  }
}

onMounted(() => store.fetchCollections())

function selectCollection(id: string) {
  stopPolling()
  store.clearDocuments()
  selectedId.value = id
  checkedIds.value = new Set()
  store.fetchDocuments(id)
}

async function handleCreateColl() {
  if (!createForm.value.name.trim()) return
  const coll = await store.addCollection(createForm.value.name, createForm.value.description)
  showCreate.value = false
  createForm.value = { name: '', description: '' }
  selectedId.value = coll.id
  store.fetchDocuments(coll.id)
  ElMessage.success('创建成功')
}

async function handleDeleteColl(id: string) {
  await store.removeCollection(id)
  if (selectedId.value === id) { stopPolling(); store.clearDocuments(); selectedId.value = null }
  ElMessage.success('已删除')
}

async function handleUploadSuccess(response: any) {
  ElMessage.success('上传成功，正在处理中...')
  if (selectedId.value) {
    await store.fetchDocuments(selectedId.value)
    store.fetchCollections()
    startPolling()
  }
}
function beforeDocumentUpload(file: File) {
  const error = documentUploadError(file)
  if (error) { ElMessage.error(error); return false }
  return true
}

function handleUploadError(error: Error) {
  let message = error.message || '上传失败'
  try { message = JSON.parse(message).message || message } catch { /* plain text error */ }
  ElMessage.error(message)
}

function statusLabel(doc: KbDocument) {
  if (doc.status === 'PENDING') return '排队中'
  if (doc.status === 'PROCESSING') {
    if (doc.progressStage === 'CHUNKING') return '生成分块中'
    if (doc.progressStage === 'ENHANCING') return '增强识别中'
    return '提取文字中'
  }
  if (doc.status === 'VECTORIZING') return '生成向量中'
  const map: Record<string, string> = { READY: '已完成', FAILED: '处理失败', VECTOR_FAILED: '向量失败' }
  return map[doc.status] || doc.status
}

function documentProgressLabel(doc: KbDocument): string | null {
  if (doc.status === 'PROCESSING' && doc.progressStage === 'CHUNKING') return '正在整理文档分块…'
  if (doc.status === 'PROCESSING' && doc.progressStage === 'ENHANCING' && doc.totalPages && doc.totalPages > 0)
    return `已增强识别 ${Math.min(doc.totalPages, Math.max(0, doc.processedPages || 0))} / ${doc.totalPages} 页`
  if (doc.status === 'PROCESSING' && doc.totalPages && doc.totalPages > 0) {
    const done = Math.min(doc.totalPages, Math.max(0, doc.processedPages || 0))
    return done === doc.totalPages ? `已提取 ${done} 页，正在完成解析…` : `已提取 ${done} / ${doc.totalPages} 页`
  }
  if (doc.status === 'VECTORIZING' && doc.chunkCount && doc.chunkCount > 0)
    return `已生成 ${Math.min(doc.chunkCount, Math.max(0, doc.vectorizedCount || 0))} / ${doc.chunkCount} 个向量`
  return null
}

function documentProgressPercent(doc: KbDocument): number | null {
  if (doc.status === 'PROCESSING' && ['PARSING', 'ENHANCING'].includes(doc.progressStage || '') && doc.totalPages && doc.totalPages > 0)
    return Math.min(99, Math.round(Math.max(0, doc.processedPages || 0) / doc.totalPages * 100))
  if (doc.status === 'VECTORIZING' && doc.chunkCount && doc.chunkCount > 0)
    return Math.min(99, Math.round(Math.max(0, doc.vectorizedCount || 0) / doc.chunkCount * 100))
  return null
}

async function handleRetryDoc(id: string) {
  try {
    await retryDocument(id)
    ElMessage.success('已重新开始处理')
    if (selectedId.value) await store.fetchDocuments(selectedId.value)
    startPolling()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '重试失败')
  }
}

async function handleReparseDoc(id: string) {
  try {
    await api.post(`/knowledge/documents/${id}/reparse`)
    ElMessage.success('已加入增强解析队列')
    if (selectedId.value) await store.fetchDocuments(selectedId.value)
    startPolling()
  } catch (e: any) { ElMessage.error(e.response?.data?.message || '重新解析失败') }
}

// --- Polling ---
let pollTimer: ReturnType<typeof setInterval> | null = null
let pollInFlight = false

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    if (!selectedId.value) { stopPolling(); return }
    if (!processingSummary.value.active) { stopPolling(); return }
    if (pollInFlight) return
    pollInFlight = true
    const collectionId = selectedId.value
    try {
      const { data } = await getCollectionDocumentProgress(collectionId)
      if (selectedId.value !== collectionId) return
      const progress = data.data || []
      const byId = new Map(store.documents.map(d => [d.id, d]))
      let refreshDocuments = progress.length !== store.documents.length
      let terminalTransition = false
      for (const current of progress) {
        const doc = byId.get(current.id)
        if (!doc) { refreshDocuments = true; continue }
        if (doc.status !== current.status && ['READY', 'FAILED', 'VECTOR_FAILED'].includes(current.status)) {
          terminalTransition = true
          refreshDocuments = true
        }
        doc.status = current.status
        doc.progressStage = current.progressStage
        doc.processedPages = current.processedPages
        doc.totalPages = current.totalPages
        doc.vectorizedCount = current.vectorizedCount
        doc.chunkCount = current.chunkCount
        doc.errorMessage = current.errorMessage
      }
      if (refreshDocuments) await store.fetchDocuments(collectionId)
      if (terminalTransition) await store.fetchCollections()
      if (!processingSummary.value.active) stopPolling()
    } catch { /* Next poll retries transient network failures. */ }
    finally { pollInFlight = false }
  }, 2000)
}

function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

// Watch for processing documents and start polling
watch(() => store.documents, (docs) => {
  const hasProcessing = docs.some(d => ['PENDING', 'PROCESSING', 'VECTORIZING'].includes(d.status))
  if (hasProcessing && selectedId.value) startPolling()
}, { deep: true })

onUnmounted(() => { stopPolling(); folderImportStopped.value = true; ++folderBrowseRequest })
</script>

<style scoped>
.kb-layout { display: flex; height: 100vh; }
.kb-sidebar {
  width: 260px; background: var(--bg-sidebar); color: var(--text-primary); display: flex; flex-direction: column;
}
.kb-sidebar-header { padding: 10px; border-bottom: 1px solid var(--horizon); }
.back-btn {
  display: flex; align-items: center; gap: 6px;
  padding: 7px 10px; background: transparent; border: none; border-radius: var(--radius-sm);
  color: var(--twilight); font-family: var(--font-body); font-size: 13px; cursor: pointer;
  transition: all var(--duration-fast) var(--ease-out);
}
.back-btn:hover { background: rgba(255,255,255,0.05); color: var(--starlight); }
.kb-sidebar-title {
  padding: 16px 16px 6px; font-family: var(--font-display); font-size: 17px;
  font-weight: 400; color: var(--starlight);
}
.collection-list { flex: 1; overflow-y: auto; padding: 8px; }
.coll-item {
  display: flex; align-items: center; gap: 8px; padding: 9px 10px; border-radius: 7px;
  cursor: pointer; transition: all var(--duration-fast) var(--ease-out);
  color: var(--twilight); font-size: 13px;
}
.coll-item:hover { background: rgba(255,255,255,0.06); color: var(--starlight); }
.coll-item.active { background: var(--pulsar-glow); color: var(--starlight); }
.coll-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.coll-count { font-size: 11px; color: var(--text-secondary); }
.coll-del { opacity: 0; color: var(--text-secondary) !important; }
.coll-item:hover .coll-del, .coll-item:focus-within .coll-del { opacity: 1; }
.kb-sidebar-footer { padding: 10px; border-top: 1px solid var(--horizon); }
.new-coll-btn {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  width: 100%; padding: 9px 16px;
  background: transparent; color: var(--twilight);
  border: 1px solid var(--control-border); border-radius: var(--radius-sm);
  cursor: pointer; font-family: var(--font-body); font-size: 13px; font-weight: 500;
  transition: all var(--duration-fast) var(--ease-out);
}
.new-coll-btn:hover {
  color: var(--starlight); border-color: var(--aurora); border-style: solid; background: var(--aurora-glow);
}
.new-coll-btn:active { transform: scale(0.97); }

.kb-main { flex: 1; min-width: 0; min-height: 0; overflow-y: auto; padding: 24px; }
.kb-toolbar {
  display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px;
}
.kb-toolbar h2 { font-size: 20px; font-weight: 600; }
.kb-toolbar-actions { display: flex; gap: 10px; flex-wrap: wrap; justify-content: flex-end; }

/* Search */
.kb-search-bar { margin-bottom: 18px; }
.search-input { max-width: 400px; }
.search-stats { font-size: 12px; color: var(--twilight); margin-top: 6px; }

.select-bar {
  display: flex; align-items: center; gap: 16px;
  margin-bottom: 12px; padding: 6px 0;
  font-size: 13px;
}
.select-clear { color: var(--pulsar); cursor: pointer; font-size: 12px; }
.select-clear:hover { text-decoration: underline; }

/* Highlight mark — Observatory precision */
:deep(mark.hl) {
  background: linear-gradient(180deg, transparent 55%, rgba(160,160,160, 0.25) 55%);
  color: inherit;
  padding: 0 1px;
  border-radius: 1px;
  transition: all 0.2s var(--ease-out);
}
:deep(mark.hl:hover) {
  background: linear-gradient(180deg, transparent 40%, rgba(160,160,160, 0.45) 40%);
}

/* Import panel */
.import-panel {
  margin-bottom: 24px; padding: 20px;
  background: var(--bg-card); border: 1px solid var(--horizon-soft); border-radius: var(--radius);
}
.import-section { display: flex; flex-direction: column; gap: 12px; padding-top: 8px; }
.import-options { display: flex; align-items: center; gap: 12px; font-size: 13px; }
.import-sub { color: var(--twilight); font-size: 12px; display: flex; align-items: center; gap: 6px; }
.import-hint { font-size: 13px; color: var(--twilight); margin: 0; }

.import-results { margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--horizon-soft); }
.import-results-header { display: flex; justify-content: space-between; align-items: center; font-size: 13px; font-weight: 500; margin-bottom: 8px; }
.import-result-item { display: flex; align-items: center; gap: 8px; padding: 4px 0; font-size: 12px; }
.result-icon { width: 16px; text-align: center; flex-shrink: 0; }
.result--imported .result-icon { color: var(--aurora); }
.result--failed .result-icon { color: var(--flare); }
.result-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.result-chars { color: var(--twilight); flex-shrink: 0; }
.result-error { color: var(--flare); font-size: 11px; }

.doc-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 280px), 1fr)); gap: 12px; align-items: start; }
.doc-card {
  display: grid; grid-template-columns: 18px 32px minmax(0, 1fr); gap: 12px;
  padding: 16px; min-width: 0; background: var(--bg-card);
  border: 1px solid var(--horizon-soft); border-radius: var(--radius);
  transition: all var(--duration-fast) var(--ease-out); cursor: pointer;
}
.doc-card:hover { box-shadow: var(--shadow-md); transform: translateY(-1px); border-color: var(--pulsar); }
.doc-card.doc--selected { border-color: var(--pulsar); background: var(--pulsar-glow); box-shadow: 0 0 0 2px rgba(224,224,224, 0.15); }
.doc-check { display: flex; align-items: flex-start; padding-top: 2px; }
.doc-icon { display: flex; align-items: flex-start; justify-content: center; padding-top: 3px; }
.doc-icon-img { width: 32px; height: 32px; object-fit: contain; }
.doc-info { min-width: 0; }
.doc-actions {
  grid-column: 1 / -1; display: flex; flex-wrap: wrap; align-items: center; gap: 8px;
  padding-top: 12px; border-top: 1px solid var(--horizon-soft); cursor: default;
}
.doc-actions :deep(.el-button) {
  margin: 0; min-height: 32px; padding: 7px 11px; border-radius: 7px;
  font-size: 12px; font-weight: 500; color: var(--text-primary);
  background: var(--bg-card); border-color: var(--control-border);
}
.doc-actions :deep(.el-button:hover) { color: var(--pulsar); border-color: #808080; background: #303030; }
.doc-actions :deep(.doc-action--preview) { color: var(--pulsar); background: #303030; border-color: #909090; }
.doc-actions :deep(.doc-action--delete) { color: #e0e0e0; background: #282828; border-color: #808080; }
.doc-actions :deep(.doc-action--delete:hover) { color: #e0e0e0; background: #3c3c3c; border-color: #808080; }
.doc-actions :deep(.doc-action--secondary) { flex-basis: 100%; border-style: dashed; }
.doc-actions :deep(.el-button:focus-visible) { outline: 2px solid var(--pulsar); outline-offset: 2px; }
.doc-name {
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  font-weight: 500; font-size: 14px; line-height: 1.5; margin-bottom: 6px;
  overflow: hidden; overflow-wrap: anywhere;
}
.doc-meta { display: flex; gap: 8px; align-items: center; font-size: 12px; color: var(--text-secondary); flex-wrap: wrap; }
.doc-meta-text { color: var(--twilight); }
.doc-error { color: var(--flare); cursor: help; text-decoration: underline dotted; }
.doc-progress-label { margin-top: 7px; font-size: 11px; color: var(--twilight); }
.doc-progress-bar { margin-top: 5px; max-width: 190px; }
.processing-summary {
  display: flex; align-items: center; flex-wrap: wrap; gap: 8px 16px;
  margin: -4px 0 16px; padding: 9px 13px; border: 1px solid var(--horizon-soft);
  border-radius: var(--radius-sm); background: var(--bg-card); font-size: 12px;
  color: var(--text-secondary);
}
.processing-summary-hint { margin-left: auto; color: var(--twilight); }

/* Status tags */
.status-tag {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 2px 10px; border-radius: 12px; font-size: 11px; font-weight: 600;
}
.status--pending { background: #282828; color: var(--text-secondary); }
.status--processing { background: var(--pulsar-glow); color: var(--pulsar); }
.status--vectorizing { background: var(--pulsar-glow); color: var(--pulsar); }
.status--ready { background: var(--aurora-glow); color: var(--aurora); }
.status--failed { background: var(--flare-glow); color: var(--flare); }
.status--vector_failed { background: var(--flare-glow); color: var(--flare); }

.status-pulse {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--pulsar);
  animation: pulse 1.2s infinite ease-in-out;
}
.status-spin-icon {
  font-size: 12px;
  animation: spin 2s linear infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.7); }
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Document icon status colors */
.doc-icon { transition: all var(--duration-fast); }
.doc-icon--pending { color: #b0b0b0; }
.doc-icon--processing { color: var(--pulsar); animation: iconGlow 1.5s infinite; }
.doc-icon--vectorizing { color: var(--pulsar); animation: iconGlow 1.5s infinite; }
.doc-icon--ready { color: var(--aurora); }
.doc-icon--failed { color: var(--flare); }
.doc-icon--vector_failed { color: var(--flare); }
@keyframes iconGlow {
  0%, 100% { filter: drop-shadow(0 0 2px var(--pulsar)); }
  50% { filter: drop-shadow(0 0 8px var(--pulsar)); }
}

/* Export dialog */
.export-body { padding: 4px 0 0; }
.export-section-label {
  font-size: 12px; font-weight: 600; color: var(--twilight);
  margin-bottom: 12px; text-transform: uppercase; letter-spacing: 0.04em;
}
.export-grid {
  display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 10px;
}
.export-card {
  display: flex; flex-direction: column; align-items: center; gap: 4px;
  padding: 16px 12px 12px;
  border: 1.5px solid var(--horizon-soft); border-radius: 10px;
  background: var(--bg-card); cursor: pointer;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  font-family: inherit;
}
.export-card:hover {
  border-color: var(--pulsar); background: rgba(224,224,224, 0.03);
  transform: translateY(-2px); box-shadow: 0 4px 12px rgba(224,224,224, 0.08);
}
.export-card.selected {
  border-color: var(--pulsar); background: var(--pulsar-glow);
  box-shadow: 0 0 0 3px rgba(224,224,224, 0.1);
}
.export-card:active { transform: scale(0.96); }
.export-card-icon { width: 32px; height: 32px; object-fit: contain; }
.export-card-label { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.export-card-desc { font-size: 11px; color: var(--twilight); text-align: center; }

.empty-docs {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  flex: 1; height: 60vh; color: var(--text-secondary); gap: 12px; font-size: 14px;
}
.empty-sub { font-size: 13px; }

/* Preview dialog */
.preview-container { min-height: 400px; max-height: 75vh; overflow: auto; }
.preview-frame { width: 100%; height: 75vh; border: none; border-radius: var(--radius-sm); background: #fff; }
.preview-image-wrap { text-align: center; }
.preview-image { max-width: 100%; max-height: 70vh; border-radius: var(--radius-sm); }
.preview-text-wrap { min-height: 200px; }
.preview-loading, .preview-error { text-align: center; padding: 60px 0; color: var(--twilight); }
.preview-error { color: var(--flare); }
.preview-content { line-height: 1.8; max-width: 100%; overflow-x: auto; }
.preview-content :deep(pre) { background: var(--bg-subtle); border-radius: var(--radius-sm); padding: 16px; overflow-x: auto; font-size: 13px; }
.preview-content :deep(img) { max-width: 100%; }
.preview-text-block { white-space: pre-wrap; word-break: break-word; font-size: 13px; line-height: 1.7; color: var(--text-primary); max-height: 70vh; overflow-y: auto; }

.folder-storage { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 16px; background: var(--surface); border: 1px solid var(--horizon-soft); border-radius: 8px; margin-bottom: 16px; font-size: 13px; color: var(--text-secondary); flex-wrap: wrap; }
.folder-storage-info { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; min-width: 0; }
.folder-mode { color: var(--pulsar); font-weight: 500; }
.folder-path { overflow-wrap: anywhere; }
.folder-sync-time { color: #b0b0b0; }
.folder-controls { display: flex; flex-shrink: 0; gap: 8px; }
.sync-summary { padding: 12px 16px; border: 1px solid #e0e0e0; border-radius: 8px; margin-bottom: 16px; font-size: 13px; }
.sync-summary details { margin-top: 8px; overflow-wrap: anywhere; }
.folder-input { display: none; }
.folder-import-progress { margin-top: 16px; font-size: 13px; color: #b0b0b0; }
.folder-path-input, .folder-navigation { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.folder-navigation { flex-wrap: wrap; font-size: 13px; }
.folder-directory-list { min-height: 80px; max-height: 280px; overflow: auto; border: 1px solid #e0e0e0; border-radius: 8px; padding: 8px; }
.folder-directory { display: flex; align-items: center; gap: 8px; width: 100%; padding: 10px; background: transparent; color: inherit; border: 0; border-radius: 6px; cursor: pointer; text-align: left; font: inherit; }
.folder-directory:hover { background: var(--bg-subtle); }
.folder-browse-error { margin-bottom: 12px; color: #b0b0b0; overflow-wrap: anywhere; }
.doc-source-path { font-size: 12px; color: #b0b0b0; max-width: 220px; white-space: nowrap; text-overflow: ellipsis; overflow: hidden; margin-bottom: 6px; }
.import-results { max-height: 360px; overflow: auto; }
.result--skipped { color: #b0b0b0; }
@media (max-width: 1100px) { .kb-toolbar { align-items: flex-start; flex-direction: column; gap: 12px; } .kb-toolbar-actions { justify-content: flex-start; } }
@media (max-width: 700px) {
  .kb-layout { height: 100dvh; flex-direction: column; }
  .kb-sidebar { width: 100%; flex-shrink: 0; border-bottom: 1px solid var(--border-light); }
  .kb-sidebar-header { padding: 4px 10px; }
  .kb-sidebar-title { padding: 8px 16px; }
  .collection-list { display: flex; flex: none; gap: 6px; overflow-x: auto; padding: 0 10px 8px; }
  .coll-item { min-width: 140px; max-width: 240px; flex-shrink: 0; margin-bottom: 0; }
  .kb-sidebar-footer { padding: 6px 10px; }
  .new-coll-btn { width: auto; padding: 7px 12px; }
  .kb-main { padding: 20px 16px; }
  .kb-toolbar-actions { gap: 8px; }
  .kb-toolbar-actions :deep(.el-button) { margin-left: 0; }
  .folder-storage { padding: 10px 12px; }
}
</style>

<!-- Non-scoped: highlight marks rendered via v-html -->
<style>
mark.hl {
  background: linear-gradient(180deg, transparent 58%, rgba(160,160,160, 0.28) 58%);
  color: inherit;
  padding: 0 2px;
  border-radius: 2px;
  transition: all 0.2s ease;
  cursor: default;
}
mark.hl:hover {
  background: linear-gradient(180deg, transparent 42%, rgba(160,160,160, 0.5) 42%);
}
</style>
