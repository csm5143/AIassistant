<template>
  <div class="chat-layout">
    <!-- Sidebar -->
    <aside :class="['sidebar', { collapsed: sidebarCollapsed }]">
      <div class="sidebar-header">
        <template v-if="!sidebarCollapsed">
          <div class="logo-sm">
            <AppLogo :size="26" />
          </div>
          <span class="brand">{{ branding.config.sidebarTitle }}</span>
        </template>
        <el-tooltip :content="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'" placement="right">
          <el-button :icon="sidebarCollapsed ? Expand : Fold" circle size="small" @click="sidebarCollapsed = !sidebarCollapsed" class="toggle-btn"/>
        </el-tooltip>
      </div>

      <div class="sidebar-body" v-show="!sidebarCollapsed">
        <!-- New session -->
        <button class="new-session-btn" @click="handleNewSession">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          <span>新对话</span>
        </button>

        <!-- Session list -->
        <div class="session-list">
          <div class="sidebar-section-label">对话历史</div>
          <div
            v-for="s in chatStore.sessions"
            :key="s.id"
            :class="['session-item', { active: chatStore.currentSessionId === s.id }]"
            @click="selectSession(s.id)"
          >
            <span class="session-bar"></span>
            <svg class="session-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/></svg>
            <span class="session-title">{{ s.title }}</span>
            <el-popconfirm title="删除此会话？" @confirm="handleDelete(s.id)" :width="200">
              <template #reference>
                <el-button :icon="Delete" circle size="small" text class="del-btn" @click.stop/>
              </template>
            </el-popconfirm>
          </div>
          <div v-if="chatStore.sessions.length === 0" class="empty-sessions">
            <p>暂无对话</p>
          </div>
        </div>
      </div>

      <div class="sidebar-footer" :class="{ 'compact-footer': sidebarCollapsed }">
        <AccountMenu :compact="sidebarCollapsed" @navigate="goTo" @logout="handleLogout" @login="showAuthModal = true" />
      </div>
    </aside>

    <!-- Main Chat Area -->
    <main class="chat-main" :class="{ 'has-conversation-outline': conversationTurns.length > 1 }">
      <RunRecovery :session-id="chatStore.currentSessionId" :streaming="chatStore.streaming || toolsBusy" @resume="resumeChatRun" @completed="refreshRunResults" @busy="toolsBusy = $event" />
      <!-- Empty State -->
      <div v-if="!chatStore.currentSessionId" class="empty-state">
        <div class="empty-hero">
          <div class="hero-orb">
            <div class="hero-ring ring-1"></div>
            <div class="hero-ring ring-2"></div>
            <div class="hero-ring ring-3"></div>
            <div class="hero-core"></div>
          </div>
        </div>
        <h2 class="gradient-text">有什么可以帮你的？</h2>
        <p class="empty-subtitle">
          点击侧栏菜单 <span class="new-session-hint">+</span> 开始对话，或试试下面的问题
        </p>
        <div class="quick-prompts">
          <button v-for="prompt in quickPrompts" :key="prompt" class="quick-prompt" @click="useQuickPrompt(prompt)">
            {{ prompt }}
          </button>
        </div>
      </div>

      <!-- Messages -->
      <div v-else class="messages-area" :class="{ 'input-is-expanded': inputExpanded || chatStore.streaming }" ref="messagesRef" tabindex="0" @click="onMessageClick" @scroll="onMessagesScroll" @wheel.passive="onMessagesWheel" @touchstart.passive="onMessagesTouchStart" @touchmove.passive="onMessagesTouchMove" @keydown="onMessagesKeydown">
        <div class="messages-list" ref="messagesListRef">
          <div
            v-for="(msg, idx) in displayedMessages"
            :key="msg.renderKey || msg.id"
            :data-turn-key="msg.role === 'user' ? msg.renderKey || msg.id : undefined"
            :class="['msg-wrapper', msg.role]"
          >
            <div :class="['msg-bubble', msg.role]">
              <div class="msg-avatar">
                <el-avatar v-if="msg.role === 'user'" :size="30" shape="circle" class="avatar-user">
                  <template #default>
                    {{ (auth.profile?.displayName || 'U').charAt(0).toUpperCase() }}
                  </template>
                </el-avatar>
                <el-avatar v-else :size="30" shape="circle" class="avatar-ai">
                  <svg viewBox="0 0 24 24" fill="none" width="14" height="14"><path d="M8 12c0-2.2 1.8-4 4-4s4 1.8 4 4-1.8 4-4 4" stroke="#fff" stroke-width="2" stroke-linecap="round"/><circle cx="12" cy="12" r="2" fill="#fff"/></svg>
                </el-avatar>
              </div>
              <div class="msg-main">
                <div class="msg-header">
                  <span class="msg-role">{{ msg.role === 'assistant' ? 'AI' : '用户' }}</span>
                  <span v-if="isStreamingMessage(msg.id)" class="streaming-badge"><span class="streaming-dot"></span>生成中</span>
                  <span v-else class="msg-time">{{ formatTime(msg.createdAt) }}</span>
                </div>
                <!-- Attachment indicator in user message -->
                <div v-if="msg.role === 'user' && msgAttachments[msg.renderKey || msg.id]" class="msg-attachment">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                  <span>{{ msgAttachments[msg.renderKey || msg.id] }}</span>
                </div>
                <!-- Uploaded images in user message -->
                <div v-if="msg.role === 'user' && (msgImages[msg.renderKey || msg.id] || msgImages[msg.id])?.length" class="msg-images">
                  <img
                    v-for="(imgUrl, i) in (msgImages[msg.renderKey || msg.id] || msgImages[msg.id])"
                    :key="i"
                    :src="imgUrl"
                    class="msg-image-thumb"
                    @click="openImageViewer(imgUrl)"
                    alt="uploaded image"
                  />
                </div>
                <div v-if="isStreamingMessage(msg.id) && toolCallStatus" class="tool-call-status" :class="'tool-' + toolCallStatus.phase">
                  <span class="tool-call-icon">{{ toolCallStatus.phase === 'call' ? '⋯' : toolCallStatus.phase === 'error' ? '!' : '✓' }}</span>
                  <span class="tool-call-text">{{ toolCallStatus.text }}</span>
                </div>
                <div class="msg-content-wrapper" :class="{ 'streaming-active': isStreamingMessage(msg.id) }">
                  <div v-if="msg.role === 'assistant' && !isStreamingMessage(msg.id) && isCollapsed[msg.renderKey || msg.id]" class="collapsed-preview" @click="toggleCollapse(msg.renderKey || msg.id)">
                    <span>{{ collapsedText(msg.content) }}</span>
                    <span class="expand-hint">点击展开完整回答</span>
                  </div>
                  <div v-else-if="msg.role === 'assistant'" class="markdown-body" v-html="renderMd(msg.content, msg.id)"></div>
                  <div v-else class="msg-text">{{ msg.content }}</div>
                  <span v-if="isStreamingMessage(msg.id)" class="typing-cursor"></span>
                </div>
                <!-- Action buttons for assistant messages -->
                <ToolArtifacts v-if="msg.role === 'assistant'" :assets="isStreamingMessage(msg.id) ? streamingArtifacts : parseToolArtifacts(msg.extra)" />
                <CitationAudit v-if="msg.role === 'assistant'" :audit="messageAudit(msg.extra)" />
                <div v-if="msg.role === 'assistant'" class="msg-actions">
                  <el-tooltip v-if="isStreamingMessage(msg.id)" content="停止生成">
                    <el-button :icon="VideoPause" circle size="small" text type="danger" @click="stopGeneration" />
                  </el-tooltip>
                  <template v-else>
                  <button class="msg-act-btn" title="复制" @click="copyMessage(msg.content)">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/></svg>
                  </button>
                  <button class="msg-act-btn" :title="isCollapsed[msg.renderKey || msg.id] ? '展开' : '折叠'" @click="toggleCollapse(msg.renderKey || msg.id)">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline :points="isCollapsed[msg.renderKey || msg.id] ? '6 9 12 15 18 9' : '6 15 12 9 18 15'"/></svg>
                  </button>
                  <button
                    v-if="visibleCitations(msg).length"
                    class="msg-act-btn msg-source-btn"
                    title="查看来源"
                    aria-label="查看来源"
                    @click="openMessageSources(msg.id)"
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 016.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 014 19.5v-15A2.5 2.5 0 016.5 2z"/><path d="M8 7h8M8 11h6"/></svg>
                    <span>来源</span>
                  </button>
                  <button class="msg-act-btn" title="重新生成" :disabled="chatStore.streaming" @click="regenerateMessage(idx)">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 102.13-9.36L1 10"/></svg>
                  </button>
                  </template>
                </div>

              </div>
            </div>
          </div>

          <section v-if="showAttachmentGuide && conversationUploads.length && !chatStore.streaming" class="file-intent-card" aria-label="文件处理建议">
            <p>文件已准备好，你想怎么处理？</p>
            <div><button v-for="suggestion in fileSuggestions" :key="suggestion.label" :disabled="toolsBusy || docUploading" @click="runToolPrompt(suggestion.prompt)">{{ suggestion.label }}</button></div>
            <span>也可以直接描述任务，例如“提取第 1–5 页”或“整理成 Word 报告”。</span>
          </section>
        </div>
      </div>

      <ConversationOutline v-if="conversationTurns.length > 1" :key="chatStore.currentSessionId || 'new'"
        :turns="conversationTurns" :active-key="conversationNavigation.activeKey.value" :streaming="chatStore.streaming"
        :class="{ 'outline-input-expanded': inputExpanded || chatStore.streaming }"
        @navigate="conversationNavigation.jumpTo" />

      <!-- One floating surface changes from a compact launcher into the composer. -->
      <div class="input-area" :class="{ 'input-expanded': inputExpanded || chatStore.streaming }" v-if="chatStore.currentSessionId" ref="inputAreaRef">
        <div class="morph-shell" :class="{
          'morph-expanded': inputExpanded || chatStore.streaming,
          'shell-focused': inputFocused,
          'shell-filled': inputText.trim().length > 0,
        }">
          <button type="button" class="morph-collapsed-bar" v-show="!inputExpanded && !chatStore.streaming"
            aria-label="展开提问输入栏" :aria-expanded="inputExpanded || chatStore.streaming" @click="expandInput">
            <span class="morph-orb" aria-hidden="true"><span class="morph-orb-core"></span></span>
            <span class="morph-ask-text">提问</span>
            <svg class="morph-expand-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 17 17 7M9 7h8v8"/></svg>
          </button>

          <!-- Expanded content (always rendered, animated via opacity + max-height) -->
          <div class="morph-expanded-body" :class="{ 'has-reasoning-advice': !!draftReasoningAdvice }" :inert="!inputExpanded && !chatStore.streaming">
            <div class="input-toolbar">
              <div class="input-toolbar-left">
                <el-select v-model="chatStore.selectedModel" placeholder="选择模型" size="small" class="model-select" :disabled="chatStore.streaming || thinkingSaving" @change="saveSelectedModel">
                  <el-option v-for="m in chatStore.models" :key="m.modelName" :label="m.name" :value="m.modelName">
                    <div class="model-option"><span>{{ m.name }}</span><span class="model-provider">{{ m.provider }}</span></div>
                  </el-option>
                </el-select>
                <ThinkingControl :model-value="chatStore.selectedThinkingEffort" :model-label="selectedModelLabel" :supported="thinkingSupported" :disabled="chatStore.streaming" :saving="thinkingSaving" :advice-enabled="reasoningAdviceEnabled" @change="saveThinkingEffort" @advice-toggle="setReasoningAdviceEnabled" />
                <span v-if="quota && quota.dailyTokenLimit !== -1" class="quota-badge" :class="{ warn: quotaWarning }">
                  {{ quota.dailyTokenUsed ? formatTokens(quota.dailyTokenUsed) : '0' }} / {{ quota.dailyTokenLimit === -1 ? '∞' : formatTokens(quota.dailyTokenLimit) }}
                </span>
              </div>
              <div class="input-toolbar-right">
                <button class="input-tool-btn memory-entry" :disabled="chatStore.streaming" @click="memoryVisible = true" aria-label="打开本聊天记忆" title="查看、修改或删除本聊天记忆"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M9 3H6a3 3 0 0 0-3 3v12a3 3 0 0 0 3 3h12a3 3 0 0 0 3-3v-3M10 14h4M7 17h7"/><path d="M14 3h7v7h-7z"/></svg></button>
                <ConversationMemory v-model="memoryVisible" :session-id="chatStore.currentSessionId" :disabled="chatStore.streaming" />
                <button class="input-tool-btn workspace-tools-entry" :disabled="chatStore.streaming || toolsBusy" @click="openWorkspaceTools" title="表格分析、报告生成、批量提取、PDF 整理" aria-label="打开实用工具">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><path d="M17.5 14v7M14 17.5h7"/></svg><span>文件</span>
                </button>
                <el-popover placement="top" :width="330" trigger="click" @show="loadScopeCollections">
                  <template #reference>
                    <button class="knowledge-scope-pill" :disabled="chatStore.streaming" :title="scopeTitle" :aria-label="`设置回答依据：${scopeTitle}`" @click.stop><span class="scope-label">{{ compactScopeLabel }}</span><span class="scope-mode"><span class="scope-mode-full">{{ answerMode === 'LOCAL' ? '仅本地' : '自动补充' }}</span><span class="scope-mode-short">{{ answerMode === 'LOCAL' ? '本地' : '补充' }}</span></span><svg class="scope-chevron" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg></button>
                  </template>
                  <div class="knowledge-scope-options">
                    <p class="scope-help scope-current">{{ scopeTitle }}</p>
                    <p class="scope-title">回答依据</p>
                    <el-radio-group v-model="answerMode" :disabled="chatStore.streaming">
                      <el-radio label="AUTO">本地优先，必要时联网补充</el-radio>
                      <el-radio label="LOCAL">仅依据本地资料</el-radio>
                    </el-radio-group>
                    <p class="scope-title">资料范围</p>
                    <el-radio-group v-model="knowledgeMode" :disabled="chatStore.streaming || !!docSession">
                      <el-radio label="AUTO">自动选择相关资料</el-radio>
                      <el-radio label="NONE">不使用知识库</el-radio>
                      <el-radio label="ALL">搜索全部资料</el-radio>
                      <el-radio label="SELECTED">指定资料范围</el-radio>
                    </el-radio-group>
                    <el-select v-if="knowledgeMode === 'SELECTED'" v-model="scopeCollectionIds" multiple placeholder="选择知识库" style="width:100%" :disabled="chatStore.streaming" :loading="scopeCollectionsLoading">
                      <el-option v-for="collection in scopeCollections" :key="collection.id" :label="collection.name" :value="collection.id" />
                    </el-select>
                    <p class="scope-help">自动补充会为缺失的公开知识查找网页来源；明确要求“只根据文档”时仍仅查本地。内部规定不使用网上通用答案替代。</p>
                    <el-button size="small" type="primary" :loading="scopeSaving" :disabled="chatStore.streaming || !docSession && knowledgeMode === 'SELECTED' && !scopeCollectionIds.length" @click="saveKnowledgeScope">应用到当前对话</el-button>
                  </div>
                </el-popover>
                <label class="input-tool-btn" title="上传图片 (支持视觉识别)">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
                  <input type="file" accept="image/png,image/jpeg,image/gif,image/webp,image/bmp" @change="handleImageUpload" multiple hidden />
                </label>
                <label class="input-tool-btn" title="上传文件，直接在聊天中问答或处理" aria-label="上传聊天文件">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48"/></svg>
                  <input type="file" accept=".pdf,.docx,.xlsx,.xls,.csv,.txt,.md,text/markdown,text/plain" multiple :disabled="chatStore.streaming || toolsBusy || docUploading" @change="handleConversationUpload" hidden />
                </label>
                <button class="input-tool-btn" @click="showExportDialog = true" title="导出对话">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                </button>
              </div>
            </div>
            <div v-if="conversationUploads.length" class="conversation-files" aria-label="当前对话文件">
              <div v-for="file in conversationUploads" :key="file.id" class="conversation-file"><span :title="file.name">{{ file.name }}</span><button :disabled="chatStore.streaming || toolsBusy || docUploading" :aria-label="`移除附件 ${file.name}`" @click="removeConversationFile(file)">×</button></div>
            </div>
            <div v-if="docSession && !toolChatMode && !conversationUploads.length" class="doc-indicator">
              <span class="doc-indicator-icon">📄</span><span class="doc-indicator-name">{{ docSession.fileName }}</span><span class="doc-indicator-chunks">{{ docSession.chunkCount }} 片段</span>
              <button class="doc-indicator-close" @click="clearDocSession" title="移除文档">&times;</button>
            </div>
            <PdfParseReport v-if="docSession && !toolChatMode" :report="docSession.parseReport" />
            <button v-if="docSession || conversationUploads.length === 1" class="doc-indicator" :disabled="docUploading || toolsBusy || chatStore.streaming" @click="openDocumentStatistics">可核验统计</button>
            <DocumentStatistics v-model="statisticsVisible" :session-id="docSession?.sessionId || null" />
            <div v-if="docUploading || documentJobError" class="doc-indicator" role="status" aria-live="polite">
              <span>{{ documentJobError || docUploadProgress || '正在准备文档问答，请稍候…' }}</span>
              <button v-if="documentJobId && docUploading" @click="cancelDocumentJob">取消处理</button>
              <button v-if="documentJobId && documentJobError" @click="retryDocumentJob">重试</button>
            </div>
            <!-- Uploaded image previews -->
            <div v-if="uploadedImages.length > 0" class="image-preview-strip">
              <div v-for="(img, i) in uploadedImages" :key="i" class="image-preview-item">
                <img :src="img.url" :alt="img.fileName" class="image-preview-thumb" />
                <span class="image-preview-name">{{ img.fileName }}</span>
                <button class="image-preview-remove" @click="removeImage(i)" title="移除图片">&times;</button>
                <span v-if="img.uploading" class="image-preview-loading">⏳</span>
              </div>
            </div>
            <ReasoningAdvice v-if="draftReasoningAdvice" :explanation="draftReasoningAdvice.explanation" :disabled="thinkingSaving || toolsBusy || docUploading" :saving="thinkingSaving" @apply="saveThinkingEffort('high')" @dismiss="reasoningAdviceDismissed = true" @disable="setReasoningAdviceEnabled(false)" />
            <div class="input-main-row">
              <el-input v-model="inputText" type="textarea" :rows="2" :autosize="{ minRows: 2, maxRows: 6 }"
                :placeholder="conversationUploads.length ? '问文件内容，或直接说要如何处理…' : docSession ? '请针对「'+docSession.fileName+'」提问...' : '输入你的问题，可粘贴图片'"
                @keydown.enter.exact.prevent="handleSend" @keydown.shift.enter="handleShiftEnter"
                @paste="onInputPaste"
                @focus="inputFocused = true" @blur="inputFocused = false" resize="none" class="chat-textarea" />
              <button v-if="chatStore.streaming" class="input-send-btn stop" @click="stopGeneration" title="停止生成">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
              </button>
              <button v-else class="input-send-btn" :class="{ ready: inputText.trim() }" :disabled="!inputText.trim() || toolsBusy || docUploading || thinkingSaving" @click="handleSend" title="发送 (Enter)">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></svg>
              </button>
            </div>
            <div class="input-footer">
              <span class="input-hint">Enter 发送  ·  Shift + Enter 换行</span>
              <span v-if="inputText.length > 0" class="input-count">{{ inputText.length }}</span>
            </div>
          </div>
        </div>
      </div>
      <!-- Return to the latest reply -->
      <Transition name="fade">
        <button v-if="showScrollBottom" class="scroll-bottom-btn" @click="scrollToBottom" title="回到底部" aria-label="回到底部">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
        </button>
      </Transition>
    </main>

    <!-- Auth Modal -->
    <WorkspaceToolbox v-model:visible="showWorkspaceTools" :session-id="chatStore.currentSessionId" :streaming="chatStore.streaming" :last-answer="lastAssistantAnswer" @files="onToolFiles" @ask="runToolPrompt" @busy="toolsBusy = $event" />
    <AuthModal v-model:visible="showAuthModal" @logged-in="onLoggedIn" />

    <el-dialog v-model="showSourceList" title="查看来源" width="min(680px, 94vw)">
      <p class="message-sources-hint">也可以点击回答中的引用编号，直接查看对应原文。</p>
      <p class="message-sources-hint">引用编号存在不代表内容支持结论。主动核验会发送本条引用原文给当前会话模型，最多增加一次模型请求。</p>
      <el-button size="small" :loading="checkingCitations" :disabled="chatStore.streaming" @click="checkMessageCitations">{{ sourceMessageAudit ? '重新核验引用' : '核验引用内容' }}</el-button>
      <CitationAudit :audit="sourceMessageAudit" />
      <div class="message-sources-list">
        <button
          v-for="cite in messageSources"
          :key="`${cite.index}-${cite.chunkId}`"
          type="button"
          class="message-source-item"
          @click="selectMessageSource(cite)"
        >
          <span class="message-source-index">[{{ cite.index }}]</span>
          <span class="message-source-info">
            <span class="message-source-file">{{ cite.sourceType === 'web' ? '🌐 ' : '📄 ' }}{{ cite.fileName }}</span>
            <span v-if="cite.sourceType === 'web'" class="message-source-location">{{ cite.url }} · {{ cite.evidenceKind === 'page_text' ? '已读取正文' : '搜索摘录' }}</span>
            <span v-else class="message-source-location"><template v-if="cite.page">第 {{ cite.page }} 页 · </template>第 {{ cite.ordinal }} 段</span>
            <span class="message-source-location">{{ citationStatus(cite) }}</span>
          </span>
          <span class="message-source-open">查看原文</span>
        </button>
      </div>
    </el-dialog>

    <SourceViewer v-model="showCiteModal" :citation="activeCitation" />

    <!-- Image Viewer Modal -->
    <el-dialog v-model="showImageViewer" title="图片预览" width="90%" top="3vh" :close-on-click-modal="true" class="image-viewer-dialog">
      <div class="image-viewer-body">
        <img :src="viewerImageUrl" class="image-viewer-img" alt="preview" />
      </div>
    </el-dialog>

    <!-- Export Dialog -->
    <ExportDialog v-model="showExportDialog" :session-id="chatStore.currentSessionId || ''" :messages="exportableMessages" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, nextTick, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { streamChat, createSseParser, type Citation, type CitationAudit as CitationAuditData, type KnowledgeScope } from '@/utils/sse'
import { recordVisit, cancelChat, refreshAccessToken, getCollections, updateSession, type KbCollection } from '@/api'
import { getUserQuota } from '@/api'
import SourceViewer from '@/components/SourceViewer.vue'
import CitationAudit from '@/components/CitationAudit.vue'
import PdfParseReport from '@/components/PdfParseReport.vue'
import DocumentStatistics from '@/components/DocumentStatistics.vue'
import ConversationMemory from '@/components/ConversationMemory.vue'
import ThinkingControl from '@/components/ThinkingControl.vue'
import ReasoningAdvice from '@/components/ReasoningAdvice.vue'
import { reasoningAdvice } from '@/utils/reasoningAdvice'
import RunRecovery from '@/components/RunRecovery.vue'
import type { RunRecord } from '@/api/runs'
import api from '@/api'
import type { UserQuota } from '@/api'
import {
  Plus, ChatDotRound, ChatLineSquare, Delete, Promotion,
  SwitchButton, Collection, Monitor, VideoPause, Expand, Fold,
  DocumentCopy, Bottom, RefreshRight,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { documentUploadError } from '@/utils/upload'
import { createChatScroll } from '@/utils/chatScroll'
import ConversationOutline from '@/components/ConversationOutline.vue'
import { buildConversationTurns } from '@/utils/conversationOutline'
import { useConversationNavigation } from '@/composables/useConversationNavigation'
import MarkdownIt from 'markdown-it'
import { renderStructuredJson } from '@/utils/structuredJson'
import { usedAnswerCitations } from '@/utils/answerCitations'
import hljs from 'highlight.js'
import AuthModal from '@/components/AuthModal.vue'
import AppLogo from '@/components/AppLogo.vue'
import AccountMenu from '@/components/AccountMenu.vue'
import ExportDialog from '@/components/ExportDialog.vue'
import WorkspaceToolbox from '@/components/WorkspaceToolbox.vue'
import ToolArtifacts from '@/components/ToolArtifacts.vue'
import { type ToolAsset, listToolFiles, uploadToolFile, removeToolFile, toolFileBlob, parseToolArtifacts, fileToolLabels } from '@/api/workspaceTools'
import { attachmentSuggestions, isDocumentQuestion, isFileActionRequest } from '@/utils/fileIntent'
import { useBrandingStore } from '@/stores/branding'
const branding = useBrandingStore()
import { useClipboard } from '@/composables/useClipboard'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const chatStore = useChatStore()
const inputText = ref('')
const showWorkspaceTools = ref(false), toolsBusy = ref(false), toolChatMode = ref(false)
const toolFiles = ref<ToolAsset[]>([]), streamingArtifacts = ref<ToolAsset[]>([])
const showAttachmentGuide = ref(false)
const conversationUploads = computed(() => toolFiles.value.filter(a => a.kind === 'upload'))
const fileSuggestions = computed(() => attachmentSuggestions(conversationUploads.value))
const lastAssistantAnswer = computed(() => [...chatStore.messages].reverse().find(m => m.role === 'assistant')?.content || '')
function onToolFiles(files: ToolAsset[]) { toolFiles.value = files; toolChatMode.value = files.some(a => a.kind === 'upload') }
async function openWorkspaceTools() {
  if (!requireAuth() || chatStore.streaming || toolsBusy.value) return
  try {
    if (!chatStore.currentSessionId || chatStore.currentSessionId === 'new') { await chatStore.newSession('文件处理'); await router.push(`/chat/${chatStore.currentSessionId}`) }
    showWorkspaceTools.value = true
  } catch { ElMessage.error('无法打开工具，请重试') }
}
async function runToolPrompt(prompt: string) {
  toolChatMode.value = true; inputText.value = prompt; inputExpanded.value = true
  await nextTick(); await handleSend()
}
watch(() => chatStore.currentSessionId, async id => {
  toolFiles.value = []; streamingArtifacts.value = []; toolChatMode.value = false; showWorkspaceTools.value = false; showAttachmentGuide.value = false
  if (!id || id === 'new' || !auth.isLoggedIn) return
  try { const { data } = await listToolFiles(id); if (id === chatStore.currentSessionId) onToolFiles(data.data) } catch { /* unavailable tools must not block ordinary chat */ }
}, { immediate: true })
const scopeCollections = ref<KbCollection[]>([])
const scopeCollectionsLoading = ref(false)
const scopeSaving = ref(false)
const knowledgeMode = ref<'AUTO' | 'NONE' | 'ALL' | 'SELECTED'>('AUTO')
const answerMode = ref<'AUTO' | 'LOCAL'>('AUTO')
const thinkingSaving = ref(false)
const selectedModelLabel = computed(() => thinkingSupported.value ? 'DeepSeek V4.1 Flash' : chatStore.models.find(m => m.modelName === chatStore.selectedModel)?.name || chatStore.selectedModel)
const thinkingSupported = computed(() => chatStore.models.find(m => m.modelName === chatStore.selectedModel)?.thinkingSupported === true)
const reasoningAdviceEnabled = ref(true), reasoningAdviceDismissed = ref(false)
try { reasoningAdviceEnabled.value = localStorage.getItem('aiassistant-reasoning-advice') !== 'off' } catch { /* Advice still works without persistent storage. */ }
function setReasoningAdviceEnabled(enabled: boolean) {
  reasoningAdviceEnabled.value = enabled; reasoningAdviceDismissed.value = false
  try { localStorage.setItem('aiassistant-reasoning-advice', enabled ? 'on' : 'off') } catch { /* Keep this page's choice. */ }
}
watch([inputText, () => chatStore.currentSessionId], () => { reasoningAdviceDismissed.value = false })
const draftReasoningAdvice = computed(() => {
  if (!reasoningAdviceEnabled.value || reasoningAdviceDismissed.value || chatStore.streaming || !thinkingSupported.value
    || !['none', 'low'].includes(chatStore.selectedThinkingEffort)) return null
  return reasoningAdvice(inputText.value, conversationUploads.value.length || (docSession.value ? 1 : 0))
})
async function saveThinkingEffort(effort: 'none' | 'low' | 'high' | 'max') {
  if (chatStore.streaming || thinkingSaving.value) return
  const id = chatStore.currentSessionId
  thinkingSaving.value = true
  try {
    if (id && id !== 'new') {
      await updateSession(id, { thinkingEffort: effort, model: chatStore.selectedModel })
      const current = chatStore.sessions.find(s => s.id === id)
      if (current) { current.thinkingEffort = effort; current.model = chatStore.selectedModel }
    }
    if (id === chatStore.currentSessionId) chatStore.selectedThinkingEffort = effort
  } catch (e: any) { ElMessage.error(e.response?.data?.message || '思考强度保存失败') }
  finally { thinkingSaving.value = false }
}
async function saveSelectedModel(model: string) {
  const id = chatStore.currentSessionId
  const previous = chatStore.sessions.find(s => s.id === id)?.model
  const effort = thinkingSupported.value ? chatStore.selectedThinkingEffort : 'none'
  thinkingSaving.value = true
  try {
    if (id && id !== 'new') {
      await updateSession(id, { model, thinkingEffort: effort })
      const current = chatStore.sessions.find(s => s.id === id)
      if (current) { current.model = model; current.thinkingEffort = effort }
    }
    chatStore.selectedThinkingEffort = effort
  } catch (e: any) { if (previous) chatStore.selectedModel = previous; ElMessage.error(e.response?.data?.message || '模型切换失败') }
  finally { thinkingSaving.value = false }
}
const scopeCollectionIds = ref<string[]>([])
const scopeBySession = ref<Record<string, KnowledgeScope>>({})
const currentKnowledgeScope = computed(() => {
  const id = chatStore.currentSessionId
  if (!id) return null
  if (scopeBySession.value[id]) return scopeBySession.value[id]
  const saved = chatStore.sessions.find(s => s.id === id)?.knowledgeRouteJson
  try { return saved ? JSON.parse(saved) as KnowledgeScope : null } catch { return null }
})
const knowledgeModeLabel = computed(() => ({ AUTO: '自动选择资料', NONE: '不使用知识库', ALL: '全部资料', SELECTED: '指定资料' }[knowledgeMode.value]))
const compactScopeLabel = computed(() => docSession.value || conversationUploads.value.length ? '对话文件' : ({ AUTO: '自动选资料', NONE: '不查资料', ALL: '全部资料', SELECTED: `已选资料${scopeCollectionIds.value.length ? ' ' + scopeCollectionIds.value.length : ''}` }[knowledgeMode.value]))
const scopeTitle = computed(() => `${conversationUploads.value.length ? conversationUploads.value.map(f => f.name).join('、') : docSession.value?.fileName || currentKnowledgeScope.value?.label || knowledgeModeLabel.value} · ${answerMode.value === 'LOCAL' ? '仅依据本地资料' : '本地优先，必要时联网补充'}`)
async function loadScopeCollections() {
  scopeCollectionsLoading.value = true
  try { scopeCollections.value = (await getCollections()).data.data || [] }
  catch { ElMessage.error('无法加载知识库') }
  finally { scopeCollectionsLoading.value = false }
}
async function saveKnowledgeScope() {
  const id = chatStore.currentSessionId
  if (!id || chatStore.streaming || scopeSaving.value) return
  scopeSaving.value = true
  try {
    await updateSession(id, { answerMode: answerMode.value, ...(!docSession.value ? { knowledgeMode: knowledgeMode.value, knowledgeSelectionJson: JSON.stringify({ collectionIds: knowledgeMode.value === 'SELECTED' ? scopeCollectionIds.value : [], documentIds: [] }) } : {}) })
    delete scopeBySession.value[id]
    await chatStore.fetchSessions()
    ElMessage.success('资料范围已更新')
  } catch (e: any) { ElMessage.error(e.response?.data?.message || e.message || '更新失败') }
  finally { scopeSaving.value = false }
}
watch(() => [chatStore.currentSessionId, chatStore.sessions.find(s => s.id === chatStore.currentSessionId)] as const, ([, session]) => {
  knowledgeMode.value = session?.knowledgeMode || 'AUTO'
  answerMode.value = session?.answerMode || 'AUTO'
  if (session) { chatStore.selectedThinkingEffort = session.thinkingEffort || 'none'; chatStore.selectedModel = session.model }
  try { scopeCollectionIds.value = session?.knowledgeSelectionJson ? JSON.parse(session.knowledgeSelectionJson).collectionIds || [] : [] }
  catch { scopeCollectionIds.value = [] }
})
const messagesRef = ref<HTMLElement>()
const messagesListRef = ref<HTMLElement>()
const sidebarCollapsed = ref(false)
let mobileBreakpoint: MediaQueryList | null = null

function onMobileBreakpointChange(event: MediaQueryListEvent) {
  sidebarCollapsed.value = event.matches
}
const showAuthModal = ref(false)
const showSourceList = ref(false)
const sourceMessageId = ref<string | null>(null)
function visibleCitations(message: { id: string; content: string }) {
  return usedAnswerCitations(message.content, chatStore.citations[message.id] || [])
}
const checkingCitations = ref(false)
function messageAudit(extra: string | null | undefined): CitationAuditData | null {
  try { const value = JSON.parse(extra || '{}').citationVerification; return Array.isArray(value?.claims) ? value : null } catch { return null }
}
const sourceMessageAudit = computed(() => messageAudit(chatStore.messages.find(m => m.id === sourceMessageId.value || m.renderKey === sourceMessageId.value)?.extra))
function citationStatus(cite: Citation): string {
  return ({supported: '模型判断支持 · 原话已定位', contradicted: '原文矛盾', insufficient: '证据不足', not_checked: '未完成核验'} as Record<string,string>)[cite.verification?.status || ''] || '尚未进行内容核验'
}
async function checkMessageCitations() {
  const messageId = chatStore.messages.find(m => m.id === sourceMessageId.value || m.renderKey === sourceMessageId.value)?.id, sessionId = chatStore.currentSessionId
  if (!messageId || !sessionId || checkingCitations.value) return
  try {
    await ElMessageBox.confirm('将把这条回答及其引用原文发送给当前会话配置的模型服务，最多增加一次模型请求并计入用量。核验是模型判断，可能有误；不会自动改写原回答。', '核验引用内容', { confirmButtonText: '确认核验', cancelButtonText: '取消', type: 'info' })
  } catch { return }
  checkingCitations.value = true
  try {
    const { data } = await api.post(`/chat/sessions/${sessionId}/messages/${messageId}/citation-check`, { consent: true })
    const result = data.data
    if (chatStore.currentSessionId === sessionId) {
      const message = chatStore.messages.find(m => m.id === messageId)
      if (message) { const extra = JSON.parse(message.extra || '{}'); extra.citations = result.citations; extra.citationVerification = result.audit; message.extra = JSON.stringify(extra); chatStore.setCitations(messageId, result.citations) }
    }
    loadQuota()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '引用核验未完成，请稍后重试') }
  finally { checkingCitations.value = false }
}
const messageSources = computed(() => {
  const message = chatStore.messages.find(msg => msg.id === sourceMessageId.value || msg.renderKey === sourceMessageId.value)
  return message ? visibleCitations(message) : []
})

const showCiteModal = ref(false)
const activeCitation = ref<Citation | null>(null)

// Inline markdown renderer — no component lifecycle issues
const chatMd = new MarkdownIt({ html: false, breaks: true, linkify: true, typographer: true })
chatMd.set({
  highlight(str: string, lang: string): string {
    if (lang && hljs.getLanguage(lang)) {
      try { return '<pre class="hljs"><code>' + hljs.highlight(str, { language: lang, ignoreIllegals: true }).value + '</code></pre>' }
      catch { /* fall through */ }
    }
    try { return '<pre class="hljs"><code>' + hljs.highlightAuto(str).value + '</code></pre>' }
    catch { return '<pre class="hljs"><code>' + chatMd.utils.escapeHtml(str) + '</code></pre>' }
  },
})

function renderMd(text: string, msgId?: string): string {
  if (!text) return ''
  const structured = renderStructuredJson(text)
  if (structured !== null) return structured
  let p = text
    .replace(/\(《[^》]+\.(md|pdf|docx?|txt|xlsx?|pptx?)\)/g, '⟨fn⟩$1⟨/fn⟩')
    .replace(/\[(\d+)\]/g, '⟨cite⟩$1⟨/cite⟩')
  let h = chatMd.render(p)
  h = h.replace(/⟨fn⟩/g, '').replace(/⟨\/fn⟩/g, '')
  h = h.replace(/⟨cite⟩(\d+)⟨\/cite⟩/g, (_, num: string) =>
    `<button type="button" class="cite-badge" aria-label="查看引用 ${num} 原文" data-cite="${num}" data-msg="${msgId || ''}">[${num}]</button>`)
  return h
}

function openMessageSources(msgId: string) {
  sourceMessageId.value = msgId
  showSourceList.value = true
}

function selectMessageSource(cite: Citation) {
  showSourceList.value = false
  openCiteModal(cite)
}

function openCiteModal(cite: Citation) {
  activeCitation.value = cite
  showCiteModal.value = true
}

function requireAuth(): boolean {
  if (!auth.isLoggedIn) {
    showAuthModal.value = true
    return false
  }
  return true
}

function goTo(path: string) {
  if (path === '/evaluations') { router.push(path); return }
  if (!requireAuth()) {
    sessionStorage.setItem('authRedirect', path)
    return
  }
  router.push(path)
}

function onLoggedIn() {
  // Refresh data after login
  chatStore.fetchSessions()
  chatStore.fetchModels()
  loadQuota()
  const id = route.params.sessionId
  if (typeof id === 'string' && id !== 'new') {
    chatStore.fetchMessages(id)
    restoreDocSession(id)
  }
  // Always stay on chat page after login — clear any pending redirect
  sessionStorage.removeItem('authRedirect')
}
const quota = ref<UserQuota | null>(null)
const abortController = ref<AbortController | null>(null)
const streamingSessionId = ref<string | null>(null)  // track which session owns the active stream
const resumedDocumentStream = ref(false)
const isCollapsed = reactive<Record<string, boolean>>({})

// Agent tool call status
const toolCallStatus = ref<{ name: string; phase: 'call' | 'result' | 'error'; text: string } | null>(null)

// Export
const showExportDialog = ref(false)
const exportableMessages = computed(() => chatStore.messages.map(m => ({
  id: m.id,
  role: m.role,
  content: m.content,
  createdAt: m.createdAt,
})))
const inputFocused = ref(false)
const inputExpanded = ref(false)
const inputAreaRef = ref<HTMLElement | null>(null)
const showScrollBottom = ref(false)
const streamMessageId = ref('')
const streamCreatedAt = ref('')
const displayedMessages = computed(() => {
  if (!chatStore.streaming) return chatStore.messages
  return [...chatStore.messages, {
    id: streamMessageId.value, sessionId: chatStore.currentSessionId || '',
    role: 'assistant' as const, content: chatStore.streamingContent,
    toolCalls: null, extra: null, tokenCount: null, createdAt: streamCreatedAt.value,
    renderKey: streamMessageId.value,
  }]
})
const isStreamingMessage = (id: string) => chatStore.streaming && id === streamMessageId.value
const chatScroll = createChatScroll(
  () => messagesRef.value,
  callback => requestAnimationFrame(callback),
  id => cancelAnimationFrame(id),
  visible => { showScrollBottom.value = visible },
)

function onDocClick(e: MouseEvent) {
  if ((e.target as Element | null)?.closest?.('.el-popper')) return
  if (inputAreaRef.value && !inputAreaRef.value.contains(e.target as Node)) {
    if (!inputText.value.trim() && !chatStore.streaming && !docSession.value && !docUploading.value && !uploadedImages.value.length && !conversationUploads.value.length) inputExpanded.value = false
  }
}

async function expandInput() {
  inputExpanded.value = true
  await nextTick()
  inputAreaRef.value?.querySelector<HTMLTextAreaElement>('textarea')?.focus()
}

watch(() => chatStore.streaming, (v) => {
  if (v) {
    inputExpanded.value = true
    streamMessageId.value = crypto.randomUUID()
    streamCreatedAt.value = new Date().toISOString()
  }
}, { flush: 'sync' })

onMounted(() => document.addEventListener('mousedown', onDocClick))
onUnmounted(() => document.removeEventListener('mousedown', onDocClick))

// Document QA
// Image upload state
interface UploadedImage {
  url: string
  fileName: string
  uploading: boolean
  blobUrl?: string  // local preview URL, revoked after upload
}
const uploadedImages = ref<UploadedImage[]>([])
const showImageViewer = ref(false)
const viewerImageUrl = ref('')
const msgImages = reactive<Record<string, string[]>>({})

const docSession = ref<{ sessionId: string; fileName: string; chunkCount: number; parseReport?: import('@/api').PdfParseReport } | null>(null)
const docAbortController = ref<AbortController | null>(null)
const docUploading = ref(false)
const docUploadProgress = ref('')
const documentJobId = ref('')
const documentJobError = ref('')
let documentJobWatch = 0
const statisticsVisible = ref(false)
const memoryVisible = ref(false)
async function openDocumentStatistics() {
  const file = conversationUploads.value[0]
  if (file && (!docSession.value || sessionStorage.getItem(`documentQaFile:${file.sessionId}`) !== file.id || needsOfficeReparse(file))) {
    if (!await prepareDocumentQuestion(file)) return
  }
  if (docSession.value) statisticsVisible.value = true
}
const msgAttachments = reactive<Record<string, string>>({})
const conversationTurns = computed(() => buildConversationTurns(displayedMessages.value, chatStore.citations, msgAttachments, msgImages))
const conversationNavigation = useConversationNavigation(messagesRef, messagesListRef, conversationTurns, () => chatScroll.pause())

async function handleConversationUpload(e: Event) {
  const input = e.target as HTMLInputElement, files = Array.from(input.files || [])
  input.value = ''
  if (!files.length || !requireAuth() || toolsBusy.value || docUploading.value || chatStore.streaming) return
  const invalid = files.find(f => documentUploadError(f) || /\.(xlsx?|csv)$/i.test(f.name) && f.size > 20 * 1024 * 1024)
  if (invalid) { ElMessage.error(documentUploadError(invalid) || '表格文件不能超过 20 MB'); return }
  toolsBusy.value = true
  let session = chatStore.currentSessionId
  try {
    if (!session || session === 'new') { await chatStore.newSession(files[0].name.slice(0, 30), chatStore.selectedModel); session = chatStore.currentSessionId!; await router.push(`/chat/${session}`) }
    for (const file of files) await uploadToolFile(session, file)
    const { data } = await listToolFiles(session)
    if (session === chatStore.currentSessionId) { onToolFiles(data.data); showAttachmentGuide.value = !inputText.value.trim(); inputExpanded.value = true; chatScroll.followLatest() }
    ElMessage.success(`已添加 ${files.length} 份文件，可以直接描述任务`)
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || e.message || '上传失败')
    if (session && session === chatStore.currentSessionId) {
      try { const { data } = await listToolFiles(session); onToolFiles(data.data); showAttachmentGuide.value = true } catch { /* preserve successful uploads */ }
    }
  } finally { toolsBusy.value = false }
}

async function removeConversationFile(file: ToolAsset) {
  if (toolsBusy.value || chatStore.streaming || docUploading.value) return
  toolsBusy.value = true
  try {
    await removeToolFile(file.sessionId, file.id)
    if (file.sessionId === chatStore.currentSessionId) onToolFiles(toolFiles.value.filter(f => f.id !== file.id))
    if (sessionStorage.getItem(`documentQaFile:${file.sessionId}`) === file.id) {
      await api.delete(`/document-qa/sessions/${file.sessionId}`)
      sessionStorage.removeItem(`documentQaFile:${file.sessionId}`)
      if (docSession.value?.sessionId === file.sessionId) docSession.value = null
    }
  } catch (e: any) { ElMessage.error(e.response?.data?.message || e.message || '移除失败') }
  finally { toolsBusy.value = false }
}

async function prepareDocumentQuestion(file: ToolAsset): Promise<boolean> {
  const uploadSessionId = file.sessionId
  if (docSession.value?.sessionId === uploadSessionId && sessionStorage.getItem(`documentQaFile:${uploadSessionId}`) === file.id && !needsOfficeReparse(file)) return true
  docUploading.value = true
  sessionStorage.setItem(`documentQaPendingFile:${uploadSessionId}`, file.id)
  try {
    const blob = await toolFileBlob(file)
    if (chatStore.currentSessionId !== uploadSessionId) return false
    const document = new File([blob], file.name, { type: file.mime })
    const loaded = await prepareDocumentUpload(document, uploadSessionId)
    if (loaded) sessionStorage.setItem(`documentQaFile:${uploadSessionId}`, file.id)
    return loaded
  } catch (e: any) { ElMessage.error(e.response?.data?.message || e.message || '无法准备文档问答'); return false }
  finally { if (chatStore.currentSessionId === uploadSessionId) docUploading.value = false }
}

function needsOfficeReparse(file: ToolAsset) {
  return /\.(xlsx|docx)$/i.test(file.name) && !docSession.value?.parseReport?.engine?.includes('escaped-cells-v1')
}

async function prepareDocumentUpload(file: File, uploadSessionId: string): Promise<boolean> {
  const form = new FormData(); form.append('file', file)
  form.append('sessionId', uploadSessionId)
  const uploadId = crypto.randomUUID()
  form.append('uploadId', uploadId)
  docUploadProgress.value = '正在上传文档…'
  documentJobError.value = ''
  documentJobId.value = uploadId
  sessionStorage.setItem(`documentQaJob:${uploadSessionId}`, uploadId)
  try {
    const { data } = await api.post('/document-qa/jobs', form, {
      timeout: 1800000,
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: event => {
        if (chatStore.currentSessionId !== uploadSessionId) return
        if (event.total && event.loaded < event.total) docUploadProgress.value = `正在上传文档 ${Math.round(event.loaded / event.total * 100)}%`
        else docUploadProgress.value = '资料已上传，正在解析…'
      }
    })
    if (chatStore.currentSessionId !== uploadSessionId) return false
    return await waitForDocumentJob(data.data.id, uploadSessionId)
  } catch (e: any) {
    documentJobError.value = '上传连接中断，可刷新页面检查任务；资料尚未接收完成时需要重新上传。'
    ElMessage.error('上传失败: ' + (e.response?.data?.message || e.message))
    return false
  } finally {
    if (chatStore.currentSessionId === uploadSessionId) docUploadProgress.value = ''
  }
}

async function waitForDocumentJob(id: string, sessionId: string): Promise<boolean> {
  const watch = ++documentJobWatch
  documentJobId.value = id; docUploading.value = true; documentJobError.value = ''
  let failures = 0
  try {
    while (watch === documentJobWatch && route.params.sessionId === sessionId) {
      try {
        const { data } = await api.get(`/document-qa/jobs/${id}`, { timeout: 5000 })
        if (watch !== documentJobWatch || route.params.sessionId !== sessionId) return false
        const job = data.data
        failures = 0
        const count = job.total > 0 ? ` ${job.completed}/${job.total}` : ''
        const labels: Record<string,string> = { QUEUED:'任务已保存，正在排队…', PARSING:'正在解析文档…', PARSING_NATIVE:`正在读取文字页${count}`, PARSING_ENHANCED:`正在还原复杂页面${count}`, EMBEDDING:`正在准备资料检索${count}`, SAVING:'正在保存文档…' }
        docUploadProgress.value = labels[job.phase] || '正在恢复文档处理…'
        if (job.status === 'COMPLETE') {
          docSession.value = { ...job.result, sessionId }
          const fileId=sessionStorage.getItem(`documentQaPendingFile:${sessionId}`)
          if(fileId)sessionStorage.setItem(`documentQaFile:${sessionId}`,fileId)
          sessionStorage.removeItem(`documentQaPendingFile:${sessionId}`);sessionStorage.removeItem(`documentQaJob:${sessionId}`)
          documentJobId.value=''
          if(job.result.parseReport?.warnings?.length)ElMessage.warning('文档存在解析提醒，请展开解析详情查看')
          return true
        }
        if (job.status === 'FAILED' || job.status === 'CANCELLED') {documentJobError.value=job.error || (job.status === 'CANCELLED' ? '任务已取消，可重试' : '文档处理失败，可重试'); return false}
      } catch (e:any) {
        if (watch !== documentJobWatch || route.params.sessionId !== sessionId) return false
        if (e.response?.status === 404) { documentJobError.value='任务不存在或已过期，请重新上传';documentJobId.value='';return false }
        docUploadProgress.value='连接暂时中断，后台任务仍会保留，正在重新连接…'
        if (++failures >= 12) {documentJobError.value='连接暂时不可用，刷新页面可继续查看已保存的任务';return false}
      }
      await new Promise(resolve=>setTimeout(resolve,1000))
    }
    return false
  } finally { if(watch===documentJobWatch){docUploading.value=false;docUploadProgress.value=''} }
}

async function cancelDocumentJob() {
  if(!documentJobId.value)return
  try{await api.post(`/document-qa/jobs/${documentJobId.value}/cancel`)}catch(e:any){ElMessage.error(e.response?.data?.message||'取消失败')}
}
async function retryDocumentJob() {
  const id=documentJobId.value, sessionId=chatStore.currentSessionId
  if(!id||!sessionId||docUploading.value)return
  try{await api.post(`/document-qa/jobs/${id}/retry`);await waitForDocumentJob(id,sessionId)}catch(e:any){ElMessage.error(e.response?.data?.message||'重试失败')}
}

async function clearDocSession() {
  if (docAbortController.value) { docAbortController.value.abort(); docAbortController.value = null }
  const id = docSession.value?.sessionId
  if (!id) return
  try {
    await api.delete(`/document-qa/sessions/${id}`)
  } catch (e: any) {
    ElMessage.error('移除文档失败: ' + (e.response?.data?.message || e.message))
    return
  }
  docSession.value = null
  sessionStorage.removeItem(`documentQaFile:${id}`)
  ElMessage.info('文档已移除')
}

async function restoreDocSession(id: string) {
  documentJobWatch++
  documentJobId.value='';documentJobError.value='';docUploading.value=false;docUploadProgress.value=''
  statisticsVisible.value=false
  docSession.value = null
  if (!auth.isLoggedIn) return
  try {
    const { data } = await api.get(`/document-qa/sessions/${id}`)
    if (route.params.sessionId === id) docSession.value = data.data
  } catch (e: any) {
    if (e.response?.status !== 404) console.warn('恢复临时文档失败', e)
  }
  try {
    const { data }=await api.get('/document-qa/jobs',{params:{sessionId:id}})
    if(route.params.sessionId!==id)return
    const job=data.data[0]
    if(job && job.status!=='COMPLETE') {
      if(job.status==='QUEUED'||job.status==='RUNNING')void waitForDocumentJob(job.id,id)
      else{documentJobId.value=job.id;documentJobError.value=job.error||(job.status==='CANCELLED'?'任务已取消，可重试':'文档处理失败，可重试')}
    } else if(job?.status==='COMPLETE' && docSession.value && sessionStorage.getItem(`documentQaPendingFile:${id}`)) {
      sessionStorage.setItem(`documentQaFile:${id}`,sessionStorage.getItem(`documentQaPendingFile:${id}`)!);sessionStorage.removeItem(`documentQaPendingFile:${id}`);sessionStorage.removeItem(`documentQaJob:${id}`)
    }
  } catch { /* Task history is optional when restoring a previously prepared document. */ }
}

// ── Image upload & management ──

async function handleImageUpload(e: Event) {
  const files = (e.target as HTMLInputElement).files
  if (!files || files.length === 0) return
  for (const file of Array.from(files)) {
    const blobUrl = URL.createObjectURL(file)
    const placeholder: UploadedImage = { url: blobUrl, fileName: file.name, uploading: true, blobUrl }
    uploadedImages.value.push(placeholder)
    const idx = uploadedImages.value.length - 1
    try {
      const fd = new FormData(); fd.append('file', file)
      const { data } = await api.post('/chat/images/upload', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      URL.revokeObjectURL(blobUrl)
      uploadedImages.value[idx] = { url: data.data.url, fileName: data.data.fileName, uploading: false }
    } catch (e: any) {
      URL.revokeObjectURL(blobUrl)
      uploadedImages.value.splice(idx, 1)
      ElMessage.error('图片上传失败: ' + (e.response?.data?.message || e.message))
    }
  }
  (e.target as HTMLInputElement).value = ''
}

function removeImage(idx: number) {
  const img = uploadedImages.value[idx]
  if (img?.blobUrl) URL.revokeObjectURL(img.blobUrl)
  uploadedImages.value.splice(idx, 1)
}

function openImageViewer(url: string) {
  viewerImageUrl.value = url
  showImageViewer.value = true
}

// Paste image handler
function onInputPaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  for (const item of Array.from(items)) {
    if (item.type.startsWith('image/')) {
      e.preventDefault()
      const file = item.getAsFile()
      if (file) {
        const blobUrl = URL.createObjectURL(file)
        const placeholder: UploadedImage = { url: blobUrl, fileName: 'pasted-image.' + (item.type.split('/')[1] || 'png'), uploading: true, blobUrl }
        uploadedImages.value.push(placeholder)
        const idx = uploadedImages.value.length - 1
        const fd = new FormData(); fd.append('file', file)
        api.post('/chat/images/upload', fd, {
          headers: { 'Content-Type': 'multipart/form-data' },
        }).then(({ data }) => {
          URL.revokeObjectURL(blobUrl)
          uploadedImages.value[idx] = { url: data.data.url, fileName: data.data.fileName, uploading: false }
        }).catch((e: any) => {
          URL.revokeObjectURL(blobUrl)
          uploadedImages.value.splice(idx, 1)
          ElMessage.error('图片上传失败')
        })
      }
    }
  }
}
const shellClass = computed(() => ({
  'shell-focused': inputFocused.value,
  'shell-filled': inputText.value.trim().length > 0,
}))

function onMessagesScroll() { chatScroll.onScroll(); conversationNavigation.update() }
function scrollToBottom() { chatScroll.followLatest() }
function onMessagesWheel(event: WheelEvent) {
  if (event.deltaY < 0) chatScroll.pause()
}
let touchY = 0
function onMessagesTouchStart(event: TouchEvent) { touchY = event.touches[0]?.clientY || 0 }
function onMessagesTouchMove(event: TouchEvent) {
  const nextY = event.touches[0]?.clientY ?? touchY
  if (nextY > touchY) chatScroll.pause()
  touchY = nextY
}
function onMessagesKeydown(event: KeyboardEvent) {
  if (event.target !== messagesRef.value) return
  if (['ArrowUp', 'PageUp', 'Home'].includes(event.key)) chatScroll.pause()
}
const contentObserver = new ResizeObserver(() => { chatScroll.update(); conversationNavigation.update(true) })
watch([messagesRef, messagesListRef], ([viewport, list]) => {
  contentObserver.disconnect()
  if (viewport) contentObserver.observe(viewport)
  if (list) contentObserver.observe(list)
  chatScroll.update()
}, { flush: 'post' })
onUnmounted(() => { contentObserver.disconnect(); chatScroll.dispose() })
const { copy } = useClipboard()

const quickPrompts = [
  '帮我写一个 Python 快速排序',
  '解释一下什么是 TypeScript 泛型',
  '推荐几个周末旅游的地方',
]

const quotaWarning = computed(() => {
  if (!quota.value) return false
  const ratio = quota.value.dailyTokenUsed / quota.value.dailyTokenLimit
  return ratio >= 0.8
})

function formatTime(dateStr: string) {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const now = new Date()
  const isToday = date.toDateString() === now.toDateString()

  if (isToday) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function formatTokens(n: number) {
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K'
  return n.toString()
}

function collapsedText(content: string) {
  // Strip markdown syntax for clean preview
  let text = content
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/[*_~`>|]/g, '')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/\n{2,}/g, '\n')
    .trim()
  return text.slice(0, 300) + (text.length > 300 ? '…' : '')
}

function onMessageClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  if (target.classList.contains('cite-badge')) {
    const num = target.dataset.cite
    const msgId = target.dataset.msg
    if (!num || !msgId) return
    // Expand the message if collapsed
    const message = chatStore.messages.find(msg => msg.id === msgId)
    const renderKey = message?.renderKey || msgId
    if (isCollapsed[renderKey]) isCollapsed[renderKey] = false
    // Find matching citation and open modal
    const cites = message ? visibleCitations(message) : []
    if (cites) {
      const cite = cites.find(c => String(c.index) === num)
      if (cite) {
        openCiteModal(cite)
        return
      }
    }
    ElMessage.warning('该引用没有保存来源信息。')

  }
}

function toggleCollapse(id: string) {
  isCollapsed[id] = !isCollapsed[id]
}

// Collapse is user-triggered only — click the expand/collapse button on any message

async function copyMessage(content: string) {
  const success = await copy(content)
  if (success) {
    ElMessage.success('已复制到剪贴板')
  } else {
    ElMessage.error('复制失败')
  }
}

async function loadQuota() {
  if (!auth.profile?.id) return
  try {
    const { data } = await getUserQuota(auth.profile.id)
    quota.value = data.data
  } catch { /* ignore */ }
}

function stopGeneration() {
  if (docAbortController.value) {
    const session=streamingSessionId.value;if(session)void fetch(`/api/document-qa/sessions/${session}/cancel`,{method:'POST',headers:{Authorization:`Bearer ${auth.token}`}}).catch(()=>{})
    docAbortController.value.abort()
    docAbortController.value = null
  }
  if (abortController.value) {
    const sessionId = streamingSessionId.value
    if (sessionId) {
      if (resumedDocumentStream.value) void fetch(`/api/document-qa/sessions/${sessionId}/cancel`, {method:'POST',headers:{Authorization:`Bearer ${auth.token}`}}).catch(()=>{})
      else void cancelChat(sessionId).catch(() => {})
    }
    abortController.value.abort()
    abortController.value = null
  }
  streamingSessionId.value = null
  resumedDocumentStream.value = false
  if (chatStore.streaming) {
    // Save whatever was generated so far
    if (chatStore.streamingContent.length > 0) {
      chatStore.addAssistantMessage(chatStore.streamingContent + '\n\n*[已停止]*', streamMessageId.value)
    }
    chatStore.streaming = false
    chatStore.streamingContent = ''
    ElMessage.info('已停止生成')
  }
}

async function regenerateMessage(idx: number) {
  if (idx < 1) return
  const userMsg = chatStore.messages[idx - 1]
  if (userMsg.role !== 'user') return

  // Remove current and previous assistant message
  chatStore.messages.splice(idx - 1, 2)

  // Resend the request
  inputText.value = userMsg.content
  await handleSend()
}

function useQuickPrompt(prompt: string) {
  handleNewSession().then(() => {
    inputText.value = prompt
    handleSend()
  })
}

async function handleShiftEnter(e: KeyboardEvent) {
  const textarea = e.target as HTMLTextAreaElement
  const start = textarea.selectionStart
  const end = textarea.selectionEnd
  const value = inputText.value
  inputText.value = value.substring(0, start) + '\n' + value.substring(end)
  nextTick(() => {
    textarea.selectionStart = textarea.selectionEnd = start + 1
  })
}

onMounted(() => {
  mobileBreakpoint = window.matchMedia('(max-width: 720px)')
  sidebarCollapsed.value = mobileBreakpoint.matches
  mobileBreakpoint.addEventListener('change', onMobileBreakpointChange)
  // Record guest visit (non-blocking)
  recordVisit({
    userId: auth.profile?.id || 'anonymous',
    page: 'chat',
    referrer: document.referrer || 'direct'
  }).catch(() => {})

  if (auth.isLoggedIn) {
    chatStore.fetchSessions()
    chatStore.fetchModels()
    loadQuota()
  }
})
onUnmounted(() => mobileBreakpoint?.removeEventListener('change', onMobileBreakpointChange))

watch(() => route.params.sessionId, async (id) => {
  if (streamingSessionId.value && streamingSessionId.value !== id) stopGeneration()
  chatScroll.reset()
  if (id && id !== 'new') {
    const sessionId = id as string
    restoreDocSession(sessionId)
    // A newly created session already belongs to this send; do not clear its messages.
    if (chatStore.currentSessionId !== sessionId) await chatStore.fetchMessages(sessionId)
    await nextTick()
    if (route.params.sessionId === sessionId) chatScroll.update()
  } else {
    docSession.value = null
  }
}, { immediate: true })

watch(
  () => [chatStore.messages.length, chatStore.streamingContent, chatStore.streaming, inputExpanded.value],
  () => chatScroll.update(),
  { flush: 'post' },
)

// Parse image URLs from message extra field on load
watch(
  () => chatStore.messages,
  (msgs) => {
    for (const msg of msgs) {
      if (msg.role === 'user' && msg.extra && !msgImages[msg.id]) {
        try {
          const extra = JSON.parse(msg.extra)
          if (extra.images && Array.isArray(extra.images)) {
            msgImages[msg.id] = extra.images
          }
        } catch {}
      }
    }
  },
  { immediate: true, deep: true }
)

async function handleNewSession() {
  if (!requireAuth()) return
  try {
    await chatStore.newSession()
    router.push(`/chat/${chatStore.currentSessionId}`)
    if (mobileBreakpoint?.matches) sidebarCollapsed.value = true
  } catch (e: any) {
    ElMessage.error('创建会话失败: ' + (e.response?.data?.message || e.message))
  }
}

async function selectSession(id: string) {
  // Abort any active stream from a different session before switching
  if (streamingSessionId.value && streamingSessionId.value !== id) {
    stopGeneration()
  }
  router.push(`/chat/${id}`)
  if (mobileBreakpoint?.matches) sidebarCollapsed.value = true
}

async function handleDelete(id: string) {
  await chatStore.removeSession(id)
  if (chatStore.currentSessionId === id) {
    router.push('/chat')
  }
}

async function resumeChatRun(run: RunRecord) {
  if(chatStore.streaming || toolsBusy.value || thinkingSaving.value)return
  await handleSend(run)
}
async function refreshRunResults() {
  const id=chatStore.currentSessionId;if(!id || chatStore.streaming)return
  await chatStore.fetchMessages(id)
  try{const{data}=await listToolFiles(id);if(id===chatStore.currentSessionId)onToolFiles(data.data)}catch{}
  void loadQuota()
}
async function handleSend(resumeRun?: RunRecord | MouseEvent) {
  if (!requireAuth()) return
  const recovery=resumeRun && 'kind' in resumeRun ? resumeRun : undefined
  const text = recovery ? recovery.question : inputText.value.trim()
  if (!text || chatStore.streaming || toolsBusy.value || docUploading.value || thinkingSaving.value) return
  const sentSession = chatStore.currentSessionId
  const singleDocument = conversationUploads.value.length === 1 && /\.(pdf|docx|txt|md)$/i.test(conversationUploads.value[0].name) ? conversationUploads.value[0] : null
  if (!recovery && singleDocument && isDocumentQuestion(text)) {
    if (!await prepareDocumentQuestion(singleDocument) || chatStore.currentSessionId !== sentSession) return
    toolChatMode.value = false
  } else if (conversationUploads.value.length || isFileActionRequest(text)) toolChatMode.value = true
  showAttachmentGuide.value = false
  streamingArtifacts.value = []
  if(!recovery)inputText.value = ''
  chatScroll.followLatest()

  // Document QA mode
  if (!recovery && docSession.value && !toolChatMode.value) {
    const msgId = crypto.randomUUID()
    const docName = docSession.value.fileName
    const sentSessionId = docSession.value.sessionId

    // Capture images for doc QA too
    const docImageUrls = uploadedImages.value.filter(img => !img.uploading).map(img => img.url)
    const extra = docImageUrls.length > 0 ? JSON.stringify({ images: docImageUrls }) : null

    chatStore.messages.push({
      id: msgId, sessionId: sentSessionId, role: 'user', content: text,
      toolCalls: null, extra, tokenCount: null,
      createdAt: new Date().toISOString(),
    } as any)
    msgAttachments[msgId] = docName
    if (docImageUrls.length > 0) msgImages[msgId] = [...docImageUrls]
    uploadedImages.value = []

    chatStore.streaming = true
    chatStore.streamingContent = ''
    streamingSessionId.value = sentSessionId
    const controller = new AbortController()
    docAbortController.value = controller
    let docPendingCitations: Citation[] | null = null

    const docBody: Record<string, unknown> = { sessionId: sentSessionId, message: text }
    if (docImageUrls.length > 0) docBody.images = docImageUrls

    const requestDoc = (access: string) => fetch('/api/document-qa/chat', {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${access}` },
      body: JSON.stringify(docBody),
      signal: controller.signal,
    })
    requestDoc(auth.token).then(async initial => {
      const resp = initial.status === 401 ? await requestDoc(await refreshAccessToken()) : initial
      if (!resp.ok) throw new Error((await resp.json().catch(() => ({}))).message || '文档问答失败')
      const reader = resp.body?.getReader(); if (!reader) throw new Error('服务器未返回流式响应')
      let terminal = false
      const parser = createSseParser((eventType, d) => {
        if (terminal || controller.signal.aborted || chatStore.currentSessionId !== sentSessionId) return
        if (eventType === 'token') chatStore.streamingContent += d
        else if (eventType === 'replace') chatStore.streamingContent = d
        else if (eventType === 'done') {
          terminal = true
          const msgId = chatStore.addAssistantMessage(chatStore.streamingContent, streamMessageId.value)
          if (docPendingCitations) chatStore.setCitations(msgId, docPendingCitations)
          chatStore.streaming = false; chatStore.streamingContent = ''; streamingSessionId.value = null; toolCallStatus.value = null
          docAbortController.value = null
          chatStore.fetchMessages(sentSessionId, { preserve: true })
        } else if (eventType === 'citations') {
          try { docPendingCitations = JSON.parse(d) } catch {}
        } else if (eventType === 'statistics_required') {
          statisticsVisible.value = true
        } else if (eventType === 'tool_call' || eventType === 'tool_result') {
          try {
            const tool = JSON.parse(d)
            toolCallStatus.value = { name: tool.name, phase: eventType === 'tool_call' ? 'call' : 'result', text: eventType === 'tool_call' ? `正在${toolCallLabel(tool.name)}…` : `${toolCallLabel(tool.name)}已完成` }
          } catch { /* invalid tool metadata */ }
        } else if (eventType === 'error' || eventType === 'guard_block') {
          terminal = true
          chatStore.streaming = false; streamingSessionId.value = null; toolCallStatus.value = null
          ElMessage.error(d || '文档问答出错')
        }
      })
      while (true) {
        const { done, value } = await reader.read(); if (done) break
        parser.feed(value)
      }
      parser.finish()
      if (!terminal && !controller.signal.aborted) throw new Error('连接中断，请重试')
    }).catch(err => {
      if (controller.signal.aborted || docAbortController.value !== controller) return
      if (err.name !== 'AbortError') ElMessage.error(err.message)
      chatStore.streaming = false
      streamingSessionId.value = null
    })
    return
  }

  if (!chatStore.currentSessionId || chatStore.currentSessionId === 'new') {
    try {
      await chatStore.newSession(text.slice(0, 30) || '新对话', chatStore.selectedModel)
      router.push(`/chat/${chatStore.currentSessionId}`)
    } catch (e: any) {
      ElMessage.error('创建会话失败')
      return
    }
  }

  // Capture image URLs before sending
  const imageUrls = recovery ? [] : uploadedImages.value.filter(img => !img.uploading).map(img => img.url)

  if(!recovery)chatStore.addUserMessage(text)
  chatStore.streaming = true
  chatStore.streamingContent = ''

  // Store image URLs for display in the message bubble
  if (imageUrls.length > 0) {
    const lastMsg = chatStore.messages[chatStore.messages.length - 1]
    if (lastMsg) msgImages[lastMsg.id] = [...imageUrls]
  }
  if(!recovery)uploadedImages.value = []

  // Capture the session ID this stream belongs to.
  // All SSE callbacks check this to prevent race conditions
  // when the user switches sessions mid-stream.
  const sentSessionId = chatStore.currentSessionId!
  streamingSessionId.value = sentSessionId

  // Safety timeout: auto-stop after 3 minutes if stuck
  resumedDocumentStream.value = recovery?.kind === 'DOC'
  const safetyTimer = setTimeout(() => {
    if (chatStore.streaming && chatStore.currentSessionId === sentSessionId) {
      stopGeneration()
      ElMessage.warning('回答超时，已自动停止')
    }
  }, 3 * 60 * 1000)

  // Citations arrive before done event (before assistant message exists).
  // Buffer them here and attach when addAssistantMessage() creates the msg.
  let pendingCitations: Citation[] | null = null

  abortController.value = streamChat(
    sentSessionId,
    text,
    auth.token,
    {
      model: chatStore.selectedModel,
      resumeRunId: recovery?.id,
      resumeKind: recovery?.kind,
      images: imageUrls.length > 0 ? imageUrls : undefined,
      onToken(t) {
        // Ignore tokens if user switched to a different session
        if (chatStore.currentSessionId !== sentSessionId || streamingSessionId.value !== sentSessionId) return
        chatStore.streamingContent += t
      },
      onReplace(content) {
        if (chatStore.currentSessionId === sentSessionId) chatStore.streamingContent = content
      },
      onDone() {
        clearTimeout(safetyTimer)
        // Ignore if user switched to a different session
        if (chatStore.currentSessionId !== sentSessionId) {
          streamingSessionId.value = null
          abortController.value = null
          pendingCitations = null
          return
        }
        const msgId = chatStore.addAssistantMessage(chatStore.streamingContent, streamMessageId.value)
        if(streamingArtifacts.value.length) { const msg = chatStore.messages.find(m => m.id === msgId); if(msg) msg.extra = JSON.stringify({ artifacts: streamingArtifacts.value }) }
        const capturedCitations = pendingCitations
        if (capturedCitations) {
          chatStore.setCitations(msgId, capturedCitations)
          pendingCitations = null
        }
        chatStore.streaming = false
        chatStore.streamingContent = ''
        streamingSessionId.value = null
        toolCallStatus.value = null
        chatStore.fetchSessions()
        // Delayed refresh: sync message IDs with backend (for export/knowledge-base features)
        // 300ms delay gives backend time to commit DB transaction
        setTimeout(() => {
          if (chatStore.currentSessionId !== sentSessionId) return
          chatStore.fetchMessages(sentSessionId, { preserve: !recovery })
        }, 300)
        loadQuota()
        abortController.value = null
      },
      onKnowledgeScope(scope) {
        if (chatStore.currentSessionId !== sentSessionId || streamingSessionId.value !== sentSessionId) return
        scopeBySession.value[sentSessionId] = scope
        if (scope.notice) ElMessage.info(scope.notice)
      },
      onCitations(cites) {
        // Buffer citations — assistant message isn't created yet (onDone hasn't fired)
        pendingCitations = cites
      },
      onToolCall(tool) {
        if (chatStore.currentSessionId !== sentSessionId) return
        const label = toolCallLabel(tool.name)
        toolCallStatus.value = { name: tool.name, phase: 'call', text: `正在执行 ${label}...` }
      },
      onToolResult(result) {
        if (chatStore.currentSessionId !== sentSessionId) return
        const artifacts = parseToolArtifacts(result.result)
        for(const asset of artifacts) if(!streamingArtifacts.value.some(a => a.id === asset.id)) streamingArtifacts.value.push(asset)
        if(artifacts.length) void listToolFiles(sentSessionId).then(({ data }) => { if(chatStore.currentSessionId === sentSessionId) onToolFiles(data.data) }).catch(() => {})
        const label = toolCallLabel(result.name)
        const failed = result.status === 'failed' || result.result.startsWith('工具执行失败')
        toolCallStatus.value = { name: result.name, phase: failed ? 'error' : 'result', text: failed ? `${label}未完成，请查看说明` : `${label}已完成` }
        // Auto-dismiss after 3 seconds
        setTimeout(() => {
          if (toolCallStatus.value?.name === result.name) {
            toolCallStatus.value = null
          }
        }, 3000)
      },
      onError(msg) {
        clearTimeout(safetyTimer)
        pendingCitations = null
        if (chatStore.currentSessionId !== sentSessionId) {
          streamingSessionId.value = null
          abortController.value = null
          return
        }
        chatStore.streaming = false
        chatStore.streamingContent = ''
        toolCallStatus.value = null
        streamingSessionId.value = null
        ElMessage.error(msg || '对话出错')
        chatStore.addAssistantMessage('[错误] ' + msg)
        abortController.value = null
      },
    }
  )

function toolCallLabel(name: string): string {
  const map: Record<string, string> = {
    ...fileToolLabels,
    knowledgeSearch: '知识库搜索',
    calculator: '计算器',
    currentTime: '当前时间',
    webSearch: '联网搜索',
    readWebPage: '读取网页证据',
    imageRecognition: '识别图片',
    ocrExtract: '提取文字',
    summarizeUrl: '网页摘要',
  }
  return map[name] || name
}
}

function handleLogout() {
  if (auth.token) {
    void fetch('/api/auth/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${auth.token}` },
      body: JSON.stringify({ refreshToken: auth.refreshToken }),
    }).catch(() => {})
  }
  auth.logout()
  chatStore.sessions = []
  chatStore.messages = []
  chatStore.currentSessionId = null
  chatStore.streaming = false
  chatStore.streamingContent = ''
  chatStore.citations = {}
  showSourceList.value = false
  sourceMessageId.value = null
  showCiteModal.value = false
  activeCitation.value = null
  router.push('/')
}
</script>

<style>
@import 'highlight.js/styles/github-dark.css';

.knowledge-scope-pill { display: flex; align-items: center; gap: 6px; max-width: 180px; min-width: 70px; border: 1px solid var(--horizon, #ddd); border-radius: 12px; padding: 4px 8px; background: transparent; color: var(--twilight, #606060); font: inherit; font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: pointer; }
.knowledge-scope-pill:disabled { opacity: .6; cursor: default; }
.scope-label { min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.scope-mode { flex-shrink: 0; }
.knowledge-scope-options .el-radio-group { display: flex; flex-direction: column; align-items: flex-start; margin-bottom: 10px; }
.scope-title { margin: 0 0 8px; font-weight: 600; }
.scope-help { font-size: 12px; line-height: 1.6; color: #b0b0b0; margin: 12px 0; }
@media(max-width:700px) { .knowledge-scope-pill { max-width: 115px; } }

</style>
<style scoped>
.input-tool-btn.workspace-tools-entry { display: inline-flex; align-items: center; gap: 6px; width: auto; min-width: 58px; padding: 0 8px; flex-shrink: 0; white-space: nowrap; color: #e0e0e0; font-size: 12px; }
.workspace-tools-entry svg { flex-shrink: 0; }
.conversation-files { display: flex; flex-wrap: wrap; gap: 6px; padding: 6px 16px; max-height: 94px; overflow-y: auto; }
.conversation-file { display: inline-flex; align-items: center; gap: 6px; min-width: 0; max-width: 100%; padding: 4px 6px 4px 9px; border-radius: 8px; border: 1px solid #3c3c3c; background: #222; font-size: 12px; color: #ddd; }
.conversation-file > span { max-width: 200px; min-width: 0; white-space: nowrap; text-overflow: ellipsis; overflow: hidden; }
.conversation-file button { flex-shrink: 0; width: 22px; height: 22px; border: 0; border-radius: 5px; background: transparent; color: #b8b8b8; font-size: 16px; cursor: pointer; }
.conversation-file button:hover { background: #3c3c3c; color: #fff; }
.file-intent-card { margin: 24px 0 32px 40px; padding: 14px 0; color: #ddd; }
.file-intent-card p { margin: 0 0 10px; font-size: 14px; }
.file-intent-card > div { display: flex; flex-wrap: wrap; gap: 8px; }
.file-intent-card button { padding: 7px 12px; background: #202020; border: 1px solid #484848; border-radius: 9px; color: #eee; cursor: pointer; font-size: 12px; }
.file-intent-card button:hover { border-color: #999; background: #303030; }
.file-intent-card > span { display: block; margin-top: 10px; font-size: 12px; color: #b0b0b0; line-height: 1.6; }
.conversation-file button:focus-visible, .file-intent-card button:focus-visible, .knowledge-scope-pill:focus-visible, .input-tool-btn:focus-visible { outline: 2px solid #b8b8b8; outline-offset: 2px; }
.tool-files-indicator { display: block; margin: 8px 12px 0; padding: 6px 9px; border: 1px solid #666; border-radius: 7px; background: #242424; color: #f0f0f0; font-size: 12px; cursor: pointer; text-align: left; }
.chat-layout {
  display: flex;
  height: 100vh;
}

/* ---- Sidebar ---- */
.sidebar {
  width: 300px;
  background: var(--void);
  color: #fff;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  transition: width 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
}
.sidebar.collapsed { width: 56px; }

.sidebar-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 17px 14px 13px;
  border-bottom: 1px solid rgba(255,255,255,0.07);
}
.brand { font-weight: 600; font-size: 16px; flex: 1; white-space: nowrap; }
.toggle-btn {
  flex-shrink: 0;
  color: rgba(255,255,255,0.7) !important;
  background: rgba(255,255,255,0.06) !important;
  border: 1px solid rgba(255,255,255,0.1) !important;
}
.toggle-btn:hover { background: rgba(255,255,255,0.14) !important; }

/* Section label */
.sidebar-section-label {
  font-size: 10px; font-weight: 600; text-transform: uppercase;
  letter-spacing: 0.08em; color: rgba(255,255,255,0.2);
  padding: 6px 12px 4px;
  user-select: none;
}

/* Body — scrollable region above footer */
.sidebar-body {
  flex: 1; display: flex; flex-direction: column;
  padding: 8px 10px; overflow-y: auto;
}

.new-session-btn {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  width: 100%; padding: 10px 12px; margin-bottom: 6px;
  background: rgba(255,255,255,0.05);
  color: var(--twilight);
  border: 1px solid var(--control-border);
  border-radius: 8px; cursor: pointer;
  font-family: var(--font-body); font-size: 13px; font-weight: 500;
  transition: all 0.2s;
}
.new-session-btn:hover {
  color: var(--starlight); border-color: var(--pulsar);
  border-style: solid; background: var(--pulsar-glow);
}
.new-session-btn:active { transform: scale(0.97); }

.session-list { flex: 1; overflow-y: auto; padding-top: 2px; }
.session-item {
  position: relative;
  display: flex; align-items: center; gap: 8px;
  padding: 8px 10px; margin-bottom: 1px;
  border-radius: 7px; cursor: pointer;
  transition: all 0.15s;
  color: var(--twilight); font-size: 13px;
}
.session-item:hover { background: rgba(255,255,255,0.05); color: var(--starlight); }
.session-item.active {
  background: rgba(224,224,224, 0.12);
  color: var(--starlight);
}
/* Left accent bar on active session */
.session-bar {
  position: absolute; left: 0; top: 6px; bottom: 6px;
  width: 3px; border-radius: 0 3px 3px 0;
  background: transparent; transition: background 0.15s;
}
.session-item.active .session-bar { background: var(--pulsar); }
.session-icon { flex-shrink: 0; opacity: 1; transition: opacity 0.15s; }
.session-item.active .session-icon { opacity: 1; color: var(--pulsar); }
.session-item:hover .session-icon { opacity: 0.8; }
.session-title {
  flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 13px;
}
.del-btn { opacity: 0; transition: opacity 0.15s; color: var(--text-secondary); }
.session-item:hover .del-btn, .session-item:focus-within .del-btn { opacity: 1; }
.empty-sessions {
  text-align: center; padding: 32px 16px;
  color: var(--twilight); font-size: 13px;
}

/* Footer */
.sidebar-footer {
  padding: 8px 10px 10px;
  margin-top: auto;
  border-top: 1px solid var(--horizon);
  display: flex; flex-direction: column; gap: 2px;
}
.sidebar-footer.compact-footer { padding: 8px 6px; }
.nav-btn {
  display: flex; align-items: center; gap: 8px;
  width: 100%; padding: 9px 10px;
  background: transparent; border: none; border-radius: 7px;
  color: var(--twilight); font-family: var(--font-body);
  font-size: 13px; cursor: pointer;
  transition: all 0.15s; text-align: left;
}
.nav-btn:hover { background: rgba(255,255,255,0.05); color: var(--starlight); }
.nav-btn:active { transform: scale(0.98); }

.user-row {
  display: flex; align-items: center; gap: 8px;
  margin-top: 6px; padding-top: 10px;
  border-top: 1px solid rgba(255,255,255,0.08);
  font-size: 13px; color: var(--twilight);
}
.user-avatar {
  width: 28px; height: 28px; border-radius: 50%;
  background: var(--pulsar); color: var(--on-accent);
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 600; flex-shrink: 0;
}
.user-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.logout-btn {
  display: flex; align-items: center; justify-content: center;
  width: 28px; height: 28px; background: transparent;
  border: none; border-radius: 50%; color: var(--twilight);
  cursor: pointer; transition: all 0.15s;
}
.logout-btn:hover { background: var(--flare-glow); color: var(--flare); opacity: 1; }

.sidebar-login-btn {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  width: 100%; padding: 10px 0; margin-top: 4px;
  background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.12);
  border-radius: 8px; color: var(--starlight);
  font-family: var(--font-body); font-size: 14px; font-weight: 500;
  cursor: pointer; transition: all 0.2s;
}
.sidebar-login-btn:hover { background: var(--pulsar); border-color: var(--pulsar); color: var(--on-accent); transform: translateY(-1px); }
.sidebar-login-btn:active { transform: scale(0.97); }

/* ---- Main Chat ---- */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--bg-main);
  min-width: 0;
  position: relative;
}

/* Return to the latest reply */
.scroll-bottom-btn {
  position: fixed;
  bottom: 140px; right: 28px;
  z-index: 50;
  width: 38px; height: 38px;
  border-radius: 50%;
  border: 1px solid var(--horizon-soft);
  background: var(--bg-card);
  color: var(--twilight);
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 2px 10px rgba(0,0,0,0.06);
  transition: all 0.2s;
}
.scroll-bottom-btn:hover {
  color: var(--pulsar);
  border-color: var(--pulsar);
  box-shadow: 0 4px 16px rgba(224,224,224, 0.15);
  transform: translateY(-2px);
}

/* ---- Empty State ---- */
.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  gap: 10px;
  padding: 40px;
}

/* Hero orb — animated concentric rings */
.empty-hero {
  margin-bottom: 16px;
  position: relative;
  width: 120px; height: 120px;
  display: flex; align-items: center; justify-content: center;
}
.hero-orb {
  position: relative;
  width: 100%; height: 100%;
}
.hero-ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 1.5px solid transparent;
}
.hero-ring.ring-1 {
  animation: orbExpand 4s ease-in-out infinite;
  border-color: rgba(224,224,224, 0.12);
}
.hero-ring.ring-2 {
  animation: orbExpand 4s ease-in-out 0.8s infinite;
  border-color: rgba(224,224,224, 0.18);
}
.hero-ring.ring-3 {
  animation: orbExpand 4s ease-in-out 1.6s infinite;
  border-color: rgba(224,224,224, 0.24);
}
.hero-core {
  position: absolute;
  inset: 32%;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--pulsar), var(--aurora));
  animation: coreBreathe 3s ease-in-out infinite;
  box-shadow: 0 0 30px rgba(224,224,224, 0.25);
}

@keyframes orbExpand {
  0%, 100% { transform: scale(0.3); opacity: 1; }
  60%      { transform: scale(1); opacity: 0.15; }
}
@keyframes coreBreathe {
  0%, 100% { transform: scale(0.9); box-shadow: 0 0 20px rgba(224,224,224, 0.15); }
  50%      { transform: scale(1.08); box-shadow: 0 0 40px rgba(224,224,224, 0.35); }
}

.empty-state h2 {
  font-size: 28px; font-weight: 600;
  margin: 0;
}
.empty-state h2.gradient-text {
  background: linear-gradient(135deg, var(--pulsar) 0%, var(--aurora) 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}
.empty-subtitle {
  font-size: 14px; color: var(--twilight); margin: 0 0 8px;
}
.new-session-hint {
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px;
  background: var(--pulsar-glow);
  border: 1px dashed var(--pulsar);
  border-radius: 6px;
  color: var(--pulsar);
  font-weight: 700;
  font-size: 13px;
  vertical-align: middle;
}

/* Quick prompts */
.quick-prompts {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: center;
  margin-top: 16px;
  max-width: 620px;
}
.quick-prompt {
  padding: 10px 20px;
  background: var(--bg-card);
  border: 1px solid var(--horizon-soft);
  border-radius: 24px;
  font-size: 13px;
  color: var(--text-primary);
  cursor: pointer;
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  font-family: inherit;
}
.quick-prompt:hover {
  background: var(--pulsar);
  color: #fff;
  border-color: var(--pulsar);
  transform: translateY(-3px);
  box-shadow: 0 8px 20px rgba(224,224,224, 0.2);
}
.quick-prompt:active { transform: translateY(-1px) scale(0.97); }

/* ---- Messages ---- */
.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 24px 24px 80px;
  display: flex;
  flex-direction: column;
  scroll-behavior: auto;
}
.messages-list {
  display: flex;
  flex-direction: column;
  gap: 24px;
  flex-shrink: 0;
}
.has-conversation-outline .messages-area { padding-left: 52px; }
.outline-input-expanded { bottom: 212px; }

/* When input is expanded, add extra bottom padding so messages clear the toolbar */
.messages-area.input-is-expanded {
  padding-bottom: 200px;
}

.msg-wrapper { display: flex; }
.msg-wrapper.assistant { justify-content: flex-start; }

.msg-wrapper.user { justify-content: flex-end; }

.msg-bubble {
  display: flex;
  gap: 10px;
  max-width: 88%;
  /* Keep bubbles stable during saving and history synchronization. */
}
.msg-bubble.user { flex-direction: row-reverse; }

.msg-avatar { flex-shrink: 0; padding-top: 4px; }

.avatar-user {
  background: linear-gradient(135deg, #a0a0a0, #808080) !important;
  color: #fff !important; font-weight: 600;
  border-radius: 50% !important;
  width: 30px !important; height: 30px !important;
  font-size: 12px !important;
}
.avatar-ai {
  background: linear-gradient(135deg, var(--pulsar), var(--aurora)) !important;
  border-radius: 50% !important;
  width: 30px !important; height: 30px !important;
}

.msg-main { flex: 1; min-width: 0; }

.msg-header {
  min-height: 20px;
  display: flex; align-items: center; gap: 8px;
  margin-bottom: 4px; font-size: 11px;
  padding: 0 4px;
}
.msg-role { font-weight: 600; color: var(--text-secondary); }
.msg-time { color: var(--twilight); opacity: 0.6; font-size: 10px; }

/* Document attachment in user message */
.msg-attachment {
  display: inline-flex; align-items: center; gap: 6px;
  margin: 0 4px 6px; padding: 4px 10px;
  background: rgba(224,224,224, 0.08); border-radius: 6px;
  font-size: 12px; color: var(--pulsar);
}

.msg-content-wrapper {
  position: relative;
  background: var(--bg-card);
  border: 1px solid var(--horizon-soft);
  border-radius: 14px;
  padding: 14px 18px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.03);
  transition: all 0.2s;
}

.msg-bubble.user .msg-content-wrapper {
  position: relative;
  background: linear-gradient(135deg, var(--pulsar), var(--pulsar-deep));
  color: #fff;
  border: none;
  border-radius: 14px 4px 14px 14px;
}

.msg-content-wrapper:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.06); }

.msg-bubble.user .msg-header .msg-role { color: rgba(255,255,255,0.7); }
.msg-bubble.user .msg-header .msg-time { color: rgba(255,255,255,0.5); }

.msg-text { font-size: 14px; line-height: 1.6; }

/* User message overrides for MarkdownRenderer */
.msg-bubble.user :deep(.markdown-body) { color: #fff; }
.msg-bubble.user :deep(.markdown-body a) { color: #e0e0e0; }
.msg-bubble.user :deep(.markdown-body blockquote) { border-color: rgba(255,255,255,0.3); color: rgba(255,255,255,0.7); }
.msg-bubble.user :deep(.markdown-body :not(pre) > code) { background: rgba(255,255,255,0.15); color: #e0e0e0; }
.msg-bubble.user :deep(.markdown-body pre) { background: rgba(0,0,0,0.25); border-color: rgba(255,255,255,0.1); }

.msg-bubble.user .msg-text {
  color: var(--text-primary);
}

/* Collapsed state */
.collapsed-preview {
  position: relative; cursor: pointer;
  padding: 10px 12px; border-radius: 6px;
  background: var(--bg-subtle); border: 1px dashed var(--horizon-soft);
  font-size: 13px; color: var(--twilight); line-height: 1.5;
  max-height: 120px; overflow: hidden;
  transition: all 0.2s;
}
.collapsed-preview::after {
  content: ''; position: absolute; bottom: 0; left: 0; right: 0; height: 40px;
  background: linear-gradient(transparent, var(--bg-subtle));
}
.collapsed-preview:hover { border-color: var(--pulsar); }
.expand-hint {
  display: block;
  color: var(--pulsar);
  font-size: 12px; font-weight: 500;
  margin-top: 8px;
  font-weight: 500;
}

/* Message actions */
.msg-actions {
  min-height: 28px;
  display: flex;
  gap: 2px;
  margin-top: 6px;
  opacity: 1;
  transition: opacity var(--duration-fast) var(--ease-out);
}
.msg-bubble.assistant:hover .msg-actions,
.msg-content-wrapper:hover .msg-actions,
.msg-actions:focus-within {
  opacity: 1;
}
.msg-act-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 28px; height: 28px; border-radius: 6px;
  background: transparent; border: none; cursor: pointer;
  color: var(--twilight); transition: all var(--duration-fast) var(--ease-out);
}
.msg-act-btn:hover {
  background: var(--bg-subtle); color: var(--pulsar);
}
.msg-act-btn:active { transform: scale(0.92); }
.msg-actions .el-button {
  color: var(--twilight);
}
.msg-actions .el-button:hover {
  color: var(--pulsar);
  background: var(--bg-subtle);
}

/* Sources are opened on demand, outside the message flow. */
.msg-source-btn { width: auto; gap: 4px; padding: 0 7px; font-size: 12px; }
.message-sources-hint { margin: 0 0 12px; color: var(--twilight); font-size: 13px; }
.message-sources-list { max-height: 60vh; overflow-y: auto; }
.message-source-item {
  display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; align-items: center; gap: 10px;
  width: 100%; padding: 12px 8px; border: 0; border-radius: 6px;
  text-align: left; background: transparent; color: var(--text-primary); cursor: pointer;
}
.message-source-item:hover { background: var(--bg-subtle); }
.message-source-item:focus-visible { outline: 2px solid var(--pulsar); outline-offset: -2px; }
.message-source-index { color: var(--citation); font-size: 13px; font-weight: 600; }
.message-source-info { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.message-source-file { overflow-wrap: anywhere; font-size: 13px; }
.message-source-location { color: var(--twilight); font-size: 12px; }
.message-source-open { color: var(--citation); font-size: 12px; white-space: nowrap; }

/* ---- Streaming badge — refined breathing pill ---- */
.streaming-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 500;
  color: var(--pulsar);
  background: var(--pulsar-glow);
  padding: 1px 10px;
  border-radius: 10px;
  letter-spacing: 0.02em;
  animation: badge-breathe 2.2s ease-in-out infinite;
}

.streaming-dot {
  display: inline-block;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--pulsar);
  animation: dot-glow 1.8s ease-in-out infinite;
}

@keyframes badge-breathe {
  0%, 100% { opacity: 0.65; }
  50%      { opacity: 1; }
}

@keyframes dot-glow {
  0%, 100% { box-shadow: 0 0 2px var(--pulsar); }
  50%      { box-shadow: 0 0 6px var(--pulsar); }
}

/* Subtle shimmer on streaming message card */
.streaming-active {
  position: relative;
  overflow: hidden;
}

.streaming-active::after {
  content: '';
  position: absolute;
  top: 0; left: -100%; bottom: 0;
  width: 60%;
  background: linear-gradient(
    90deg,
    transparent 0%,
    rgba(224,224,224, 0.03) 30%,
    rgba(224,224,224, 0.06) 50%,
    rgba(224,224,224, 0.03) 70%,
    transparent 100%
  );
  animation: shimmer-sweep 2.8s ease-in-out infinite;
  pointer-events: none;
}

@keyframes shimmer-sweep {
  0%   { left: -100%; }
  100% { left: 120%; }
}

/* ---- Typing cursor — CSS bar with soft glow ---- */
.typing-cursor {
  position: absolute;
  right: 7px;
  bottom: 7px;
  display: inline-block;
  width: 2px;
  height: 1.15em;
  background: var(--pulsar);
  border-radius: 1px;
  vertical-align: text-bottom;
  margin-left: 1px;
  box-shadow: 0 0 5px rgba(224,224,224, 0.35);
  animation: cursor-fade 1.2s cubic-bezier(0.4, 0, 0.2, 1) infinite;
}

@keyframes cursor-fade {
  0%, 100% { opacity: 0.2; }
  40%      { opacity: 1; }
}

/* ---- Tool call status ---- */
.tool-call-status {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  padding: 7px 12px;
  border-radius: 8px;
  font-size: 12px;
  color: var(--text-secondary);
  animation: toolStatusIn 0.25s ease-out;
}
.tool-call-status.tool-call {
  background: var(--pulsar-glow);
  border: 1px solid rgba(224,224,224, 0.2);
}
.tool-call-status.tool-result {
  background: var(--aurora-glow);
  border: 1px solid rgba(160,160,160, 0.2);
}
.tool-call-icon { font-size: 13px; flex-shrink: 0; }
.tool-call-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
@keyframes toolStatusIn {
  from { opacity: 0; transform: translateY(-4px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* ---- Export dialog ---- */
.export-dialog :deep(.el-dialog__header) { padding-bottom: 0; }
.export-header {
  display: flex; align-items: center; gap: 8px;
  font-size: 16px; font-weight: 600; color: var(--text-primary);
}
.export-body { padding: 4px 0 0; }
.export-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.export-card {
  display: flex; flex-direction: column;
  align-items: center; gap: 4px;
  padding: 18px 12px 14px;
  border: 1.5px solid var(--horizon-soft);
  border-radius: 10px;
  background: var(--bg-card);
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  font-family: inherit;
}
.export-card:hover {
  border-color: var(--pulsar);
  background: rgba(224,224,224, 0.03);
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(224,224,224, 0.08);
}
.export-card.selected {
  border-color: var(--pulsar);
  background: var(--pulsar-glow);
  box-shadow: 0 0 0 3px rgba(224,224,224, 0.1);
}
.export-card:active { transform: scale(0.96); }
.export-card-icon { width: 32px; height: 32px; object-fit: contain; }
.export-card-label { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.export-card-desc { font-size: 11px; color: var(--twilight); }
.export-footer { display: flex; justify-content: flex-end; gap: 8px; }

/* ---- Input ---- */
/* ---- Input Area — always absolute, morphs between pill and full bar ---- */
.input-area {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  align-items: center;
  pointer-events: none;
  transition: padding 0.35s cubic-bezier(0.22, 0.61, 0.36, 1),
              background 0.35s ease,
              box-shadow 0.35s ease;
}

/* Collapsed: floating pill with fade-to-transparent backdrop */
.input-area:not(.input-expanded) {
  padding: 0 16px 20px;
  background: linear-gradient(to top, var(--bg-main) 0%, var(--bg-main) 38%, transparent 100%);
  box-shadow: none;
}

/* Expanded: full-width bar anchored to bottom */
.input-area.input-expanded {
  padding: 12px 16px max(18px, env(safe-area-inset-bottom));
  background: var(--bg-main);
  box-shadow: 0 -4px 24px rgba(0, 0, 0, 0.05);
}

.input-area .morph-shell {
  pointer-events: auto;
}

.input-area:not(.input-expanded) .morph-shell {
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08), 0 0 0 1px rgba(224,224,224, 0.06);
}

/* Single-layer input shell — no nested borders */
.input-shell {
  width: 100%;
  max-width: 820px;
  background: var(--bg-card);
  border: 1px solid var(--horizon-soft);
  border-radius: 10px;
  transition: border-color 0.25s ease, box-shadow 0.25s ease;
  overflow: hidden;
}
.input-shell.shell-focused {
  border-color: var(--pulsar);
  box-shadow: 0 0 0 3px rgba(224,224,224, 0.08);
}
.input-shell.shell-filled {
  border-color: rgba(224,224,224, 0.22);
}

/* Main row: textarea + send button side by side */
.input-main-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 4px 10px 4px 14px;
}

/* ── Textarea: zero chrome, flat inside shell ── */
.chat-textarea :deep(.el-textarea__inner) {
  border: none !important;
  box-shadow: none !important;
  padding: 6px 0 !important;
  min-height: 40px !important;
  font-size: 14px;
  line-height: 1.6;
  background: transparent;
  color: var(--text-primary);
  caret-color: var(--pulsar);
  resize: none;
}
.chat-textarea :deep(.el-textarea__inner::placeholder) {
  color: var(--twilight);
  opacity: 0.55;
}
.chat-textarea :deep(.el-textarea__inner:focus) {
  box-shadow: none !important;
  border: none !important;
}

/* ── Send / stop button ── */
.input-send-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  margin-bottom: 4px;
  border-radius: 8px;
  border: none;
  background: var(--bg-subtle);
  color: var(--twilight);
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.input-send-btn.ready {
  background: var(--pulsar);
  color: #fff;
  box-shadow: 0 2px 8px rgba(224,224,224, 0.3);
}
.input-send-btn.ready:hover {
  transform: scale(1.08);
  box-shadow: 0 4px 16px rgba(224,224,224, 0.4);
}
.input-send-btn:disabled {
  opacity: 1;
  background: var(--bg-subtle);
  color: var(--text-muted);
  border: 1px solid var(--border);
  cursor: default;
}
.input-send-btn:active:not(:disabled) { transform: scale(0.92); }
.input-send-btn.stop {
  background: #333333;
  color: #fff;
}
.input-send-btn.stop:hover {
  transform: scale(1.08);
  background: #606060;
}

/* ── Footer: persistent shortcut hints ── */
.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 14px 8px;
}
.input-hint {
  font-size: 10px;
  color: var(--twilight);
  opacity: 0.5;
  user-select: none;
}
.input-count {
  font-size: 10px;
  color: var(--twilight);
  opacity: 0.45;
  font-variant-numeric: tabular-nums;
}

/* ---- Single-element morphing shell ---- */
.morph-shell {
  position: relative;
  background: var(--bg-card);
  border: 1px solid var(--horizon-soft);
  border-radius: 22px;
  width: fit-content;
  max-width: 820px;
  margin: 0 auto;
  cursor: pointer;
  transition:
    width 0.38s cubic-bezier(0.22,0.61,0.36,1),
    max-width 0.38s cubic-bezier(0.22,0.61,0.36,1),
    border-radius 0.38s cubic-bezier(0.22,0.61,0.36,1),
    border-color 0.2s,
    box-shadow 0.2s;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.03);
}
.morph-shell:hover { box-shadow: 0 2px 12px rgba(224,224,224,0.06); }

/* Expanded state */
.morph-shell.morph-expanded {
  width: 100%;
  border-radius: 12px;
  border-color: var(--horizon-soft);
  cursor: default;
}
.morph-shell.morph-expanded.shell-focused { border-color: var(--pulsar); box-shadow: 0 0 0 3px rgba(224,224,224,0.08); }

/* Collapsed bar — always present, fades out on expand */
.morph-collapsed-bar {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 8px 6px 12px;
  transition: opacity 0.25s cubic-bezier(0.4,0,0.2,1);
  user-select: none;
}
.morph-expanded .morph-collapsed-bar { opacity: 0; pointer-events: none; position: absolute; }

.morph-orb {
  width: 20px; height: 20px; border-radius: 50%; flex-shrink: 0;
  background: conic-gradient(var(--pulsar), var(--aurora), #cccccc, var(--pulsar));
  animation: orbSpin 6s linear infinite;
}
@keyframes orbSpin { to { transform: rotate(360deg); } }

.morph-ask-text { font-size: 13px; font-weight: 500; color: var(--text-secondary); white-space: nowrap; }
.morph-pill-model {
  font-size: 11px; color: var(--twilight); padding: 2px 8px;
  background: var(--bg-subtle); border-radius: 10px; white-space: nowrap;
}
.morph-model-name { max-width: 100px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: inline-block; vertical-align: middle; }

/* Expanded body — fades in */
.morph-expanded-body {
  opacity: 0;
  max-height: 0;
  overflow: hidden;
  transition: opacity 0.25s cubic-bezier(0.4,0,0.2,1) 0.1s;
}
.morph-expanded .morph-expanded-body {
  opacity: 1;
  max-height: 600px;
  transition: opacity 0.3s cubic-bezier(0.16,1,0.3,1) 0.15s;
}

/* ---- Input toolbar ---- */
.input-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 6px 12px 2px;
}
.input-toolbar-left, .input-toolbar-right { display: flex; align-items: center; gap: 4px; }
.model-select { width: 165px; }
.model-select :deep(.el-input__wrapper) {
  background: transparent !important; box-shadow: none !important; border: none !important; padding: 0 2px;
}
.model-select :deep(.el-input__inner) { font-size: 12px; color: var(--twilight); font-weight: 500; }
.model-option { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.model-provider { font-size: 11px; color: #999; }

.quota-badge { font-size: 10px; color: var(--twilight); opacity: 0.6; white-space: nowrap; }
.quota-badge.warn { color: var(--flare); opacity: 0.85; }

.input-tool-btn {
  display: flex; align-items: center; justify-content: center;
  width: 30px; height: 30px; border-radius: 7px;
  border: none; background: transparent; color: var(--twilight);
  cursor: pointer; transition: all 0.15s; position: relative;
}
.input-tool-btn:hover { background: var(--bg-subtle); color: var(--text-primary); }
.input-tool-btn:active { transform: scale(0.93); }
.input-tool-btn input[type="file"] { position: absolute; inset: 0; opacity: 0; cursor: pointer; }

/* Document indicator */
.doc-indicator {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 14px; margin: 0 12px 4px;
  background: var(--pulsar-glow); border: 1px solid rgba(224,224,224, 0.2);
  border-radius: 8px; font-size: 12px;
}
.doc-indicator-icon { font-size: 14px; }
.doc-indicator-name { font-weight: 600; color: var(--pulsar); }
.doc-indicator-chunks { color: var(--twilight); margin-left: auto; }
.doc-indicator-close {
  background: none; border: none; font-size: 18px; cursor: pointer;
  color: var(--twilight); padding: 0 2px; line-height: 1;
}

/* Image preview strip */
.image-preview-strip {
  display: flex; gap: 8px; flex-wrap: wrap;
  padding: 4px 12px; margin: 0 12px 4px;
}
.image-preview-item {
  position: relative; width: 64px; height: 64px;
  border-radius: 8px; overflow: hidden;
  border: 2px solid var(--border-light);
  background: var(--bg-tertiary);
}
.image-preview-thumb {
  width: 100%; height: 100%; object-fit: cover;
}
.image-preview-name {
  position: absolute; bottom: 0; left: 0; right: 0;
  background: rgba(0,0,0,0.6); color: #fff;
  font-size: 9px; padding: 1px 4px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.image-preview-remove {
  position: absolute; top: 0; right: 0;
  background: rgba(0,0,0,0.6); color: #fff;
  border: none; font-size: 14px; cursor: pointer;
  width: 18px; height: 18px; display: flex; align-items: center; justify-content: center;
  border-radius: 0 0 0 6px;
}
.image-preview-loading {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  background: rgba(0,0,0,0.3); font-size: 20px;
}

/* Images in user messages */
.msg-images {
  display: flex; gap: 6px; flex-wrap: wrap;
  margin-bottom: 6px;
}
.msg-image-thumb {
  width: 80px; height: 80px; object-fit: cover;
  border-radius: 8px; cursor: pointer;
  border: 1px solid var(--border-light);
  transition: transform 0.15s;
}
.msg-image-thumb:hover { transform: scale(1.05); }

/* Image viewer */
.image-viewer-body {
  display: flex; justify-content: center; align-items: center;
  max-height: 80vh; overflow: auto;
}
.image-viewer-img {
  max-width: 100%; max-height: 80vh; object-fit: contain;
  border-radius: 8px;
}

/* Markdown body — inline in chat (replaces MarkdownRenderer component) */
:deep(.markdown-body) { font-size: 14px; line-height: 1.7; color: var(--text-primary); word-break: break-word; }
:deep(.markdown-body p) { margin: 0 0 10px; line-height: 1.75; }
:deep(.markdown-body p:last-child) { margin-bottom: 0; }
:deep(.markdown-body strong) { font-weight: 700; }
:deep(.markdown-body h1), :deep(.markdown-body h2), :deep(.markdown-body h3),
:deep(.markdown-body h4), :deep(.markdown-body h5), :deep(.markdown-body h6) { margin: 24px 0 10px; font-weight: 600; line-height: 1.4; }
:deep(.markdown-body h1:first-child), :deep(.markdown-body h2:first-child), :deep(.markdown-body h3:first-child) { margin-top: 0; }
:deep(.markdown-body h1) { font-size: 1.35em; border-bottom: 1px solid var(--horizon-soft); padding-bottom: 8px; }
:deep(.markdown-body h2) { font-size: 1.2em; border-bottom: 1px solid var(--horizon-soft); padding-bottom: 6px; }
:deep(.markdown-body h3) { font-size: 1.1em; }
:deep(.markdown-body ul), :deep(.markdown-body ol) { padding-left: 22px; margin: 6px 0; }
:deep(.markdown-body li) { margin: 3px 0; }
:deep(.markdown-body blockquote) { border-left: 3px solid var(--pulsar); padding-left: 14px; margin: 10px 0; color: var(--text-secondary); }
:deep(.markdown-body a) { color: var(--citation); text-decoration: none; }
:deep(.markdown-body a:hover) { color: var(--citation-hover); text-decoration: underline; }
:deep(.markdown-body code) { font-family: 'JetBrains Mono','Fira Code',monospace; font-size: 0.9em; }
:deep(.markdown-body :not(pre) > code) { background: #f5f5f5; color: var(--pulsar-deep); padding: 2px 6px; border-radius: 4px; }
:deep(.markdown-body pre) { background: #202020; color: #d4d4d4; margin: 12px 0; padding: 14px 16px; border-radius: 6px; overflow-x: auto; font-size: 13px; line-height: 1.55; }
:deep(.markdown-body pre code) { background: transparent; padding: 0; font-size: inherit; }
:deep(.markdown-body table) { border-collapse: collapse; width: 100%; margin: 12px 0 16px; font-size: 13px; }
:deep(.markdown-body th), :deep(.markdown-body td) { border: 1px solid var(--border); padding: 10px 14px; text-align: left; }
:deep(.markdown-body th) { background: #f5f5f5; font-weight: 600; }
:deep(.markdown-body tr:nth-child(even) td) { background: #ffffff; }
:deep(.markdown-body img) { max-width: 100%; border-radius: 8px; margin: 8px 0; }
:deep(.markdown-body hr) { border: none; border-top: 1px solid var(--horizon-soft); margin: 16px 0; }
:deep(.cite-badge) { display: inline !important; vertical-align: super; font-family: inherit; font-size: 12px; font-weight: 600; line-height: 1; color: var(--citation); cursor: pointer; margin: 0 1px 0 3px; padding: 0 3px; border: 0; background: transparent; border-radius: 3px; transition: color 0.15s, background-color 0.15s; }
:deep(.cite-badge:focus-visible) { outline: 2px solid var(--citation); outline-offset: 2px; }
:deep(.cite-badge:hover) { background: var(--citation-glow); color: var(--citation-hover); }

/* Animation */
@keyframes messageIn {
  from { opacity: 0; transform: translateY(12px) scale(0.97); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}


/* Responsive */
@media (max-width: 768px) {
  .msg-bubble { max-width: 90%; }
  .quick-prompts { padding: 0 20px; }
}


.knowledge-scope-pill { display: flex; align-items: center; gap: 6px; max-width: 180px; min-width: 70px; border: 1px solid var(--horizon, #ddd); border-radius: 12px; padding: 4px 8px; background: transparent; color: var(--twilight, #606060); font: inherit; font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: pointer; }
.knowledge-scope-pill:disabled { opacity: .6; cursor: default; }
.knowledge-scope-options .el-radio-group { display: flex; flex-direction: column; align-items: flex-start; margin-bottom: 10px; }
.scope-title { margin: 0 0 8px; font-weight: 600; }
.scope-help { font-size: 12px; line-height: 1.6; color: #b0b0b0; margin: 12px 0; }
@media(max-width:700px) { .knowledge-scope-pill { max-width: 115px; } }
/* Dark conversation canvas: calm surfaces, high-contrast type, quiet accent. */
.chat-layout { background: var(--bg-main); }
.sidebar { background: var(--bg-sidebar); border-right: 1px solid var(--horizon); }
.sidebar-section-label { color: var(--text-muted); }
.session-item.active { background: var(--bg-selected); color: var(--starlight); }
.session-item.active .session-bar { background: var(--pulsar); }
.session-item:hover, .nav-btn:hover { background: var(--bg-subtle); }
.session-item.active:hover { background: var(--bg-selected); }
.session-icon, .nav-btn svg, .new-session-btn svg, .input-tool-btn svg, .msg-actions svg {
  transition: transform 230ms var(--ease-out), color 230ms var(--ease-out);
}
.session-item:hover .session-icon, .nav-btn:hover svg, .new-session-btn:hover svg,
.input-tool-btn:hover svg, .msg-actions button:hover svg { transform: translateY(-2px) scale(1.08); }
.chat-main { background: var(--bg-main); }
.messages-area { padding-bottom: 100px; }
.messages-area.input-is-expanded { padding-bottom: 230px; }
.messages-list { gap: 28px; }
.msg-content-wrapper { background: var(--bg-card); border-color: var(--border-light); border-radius: 17px; box-shadow: 0 6px 18px rgba(0,0,0,.08); }
.msg-content-wrapper:hover { border-color: var(--border); box-shadow: 0 8px 22px rgba(0,0,0,.12); }
.msg-bubble.user .msg-content-wrapper { background: var(--bg-tertiary); border: 1px solid var(--border); color: var(--text-primary); border-radius: 17px 5px 17px 17px; }
.msg-bubble.user :deep(.markdown-body) { color: var(--text-primary); }
.msg-bubble.user :deep(.markdown-body :not(pre) > code) { background: var(--bg-selected); color: var(--text-primary); }
.msg-bubble.user .msg-header .msg-role { color: var(--text-secondary); }
.msg-bubble.user .msg-header .msg-time { color: var(--twilight); opacity: 1; }
.msg-time { opacity: 1; }
:deep(.markdown-body) { font-size: 15px; line-height: 1.8; color: var(--text-primary); }
:deep(.markdown-body :not(pre) > code) { background: var(--bg-selected); color: var(--text-primary); }
:deep(.markdown-body pre) { background: var(--bg-code); color: var(--text-primary); border-color: var(--border); }
:deep(.markdown-body th) { background: var(--bg-selected); }
:deep(.markdown-body tr:nth-child(even) td) { background: var(--bg-subtle); }
:deep(.markdown-body a) { color: var(--citation); text-decoration: underline; text-underline-offset: 3px; }
.avatar-user { background: #333333 !important; color: #ffffff; }
.avatar-ai { background: #444444 !important; color: #ffffff; }
.quick-prompt:hover { color: #202020; }
.empty-state h2.gradient-text { background: linear-gradient(110deg, var(--starlight) 15%, var(--pulsar) 100%); -webkit-background-clip: text; background-clip: text; }

/* The entire composer floats; no full-width opaque footer or fade mask. */
.input-area, .input-area:not(.input-expanded), .input-area.input-expanded {
  background: transparent;
  box-shadow: none;
  padding: 0 18px max(18px, env(safe-area-inset-bottom));
  transition: none;
}
.morph-shell, .input-area:not(.input-expanded) .morph-shell {
  width: 126px;
  max-width: min(780px, calc(100vw - 36px));
  border-radius: 999px;
  border: 1px solid var(--control-border);
  background: var(--bg-input);
  box-shadow: 0 12px 34px rgba(0,0,0,.26), 0 2px 8px rgba(0,0,0,.12);
  cursor: default;
  transition: width 480ms cubic-bezier(.2,.9,.2,1), border-radius 420ms cubic-bezier(.2,.9,.2,1), border-color 220ms ease, box-shadow 280ms ease, background-color 280ms ease;
}
.morph-shell:hover, .input-area:not(.input-expanded) .morph-shell:hover {
  border-color: #a0a0a0;
  box-shadow: 0 15px 38px rgba(0,0,0,.3), 0 0 0 3px rgba(224,224,224,.06);
}
.morph-shell.morph-expanded {
  width: min(780px, 100%);
  max-width: min(780px, calc(100vw - 36px));
  border-radius: 22px;
  background: var(--bg-input);
  border-color: var(--control-border);
  box-shadow: 0 16px 42px rgba(0,0,0,.3), 0 0 0 1px rgba(224,224,224,.04);
}
.morph-shell.morph-expanded.shell-focused { border-color: #999b9d; box-shadow: 0 16px 42px rgba(0,0,0,.32), 0 0 0 3px rgba(224,224,224,.08); }
.morph-collapsed-bar {
  width: 100%; min-height: 42px; padding: 6px 12px 6px 8px;
  display: flex; justify-content: space-between; align-items: center; gap: 8px;
  color: var(--text-primary); border: 0; background: transparent;
  font: inherit; cursor: pointer;
}
.morph-collapsed-bar:hover .morph-expand-icon { transform: translate(2px,-2px); color: var(--pulsar); }
.morph-ask-text { font-size: 13px; font-weight: 600; color: var(--text-primary); }
.morph-expand-icon { color: #b0b0b0; flex-shrink: 0; transition: transform 220ms var(--ease-out), color 220ms; }
.morph-orb {
  display: grid; place-items: center;
  width: 25px; height: 25px;
  background: conic-gradient(from 30deg, #f5f5f5, #b8b8b8, #f5f5f5, #f5f5f5);
  animation: orbSpin 8s linear infinite;
  box-shadow: 0 0 12px rgba(224,224,224,.18);
}
.morph-orb-core { width: 13px; height: 13px; border-radius: 50%; background: #202020; box-shadow: inset 0 0 0 2px rgba(245,245,245,.25); }
.morph-expanded-body { width: 100%; transition: opacity 180ms ease, max-height 440ms cubic-bezier(.2,.9,.2,1); }
.morph-expanded .morph-expanded-body { max-height: min(72vh, 620px); transition: opacity 220ms ease 100ms, max-height 460ms cubic-bezier(.2,.9,.2,1); }
.morph-expanded .morph-expanded-body.has-reasoning-advice { overflow-y: auto; overscroll-behavior: contain; scrollbar-width: thin; }
.input-toolbar { gap: 10px; padding: 12px 16px 4px; }
.input-toolbar-left, .input-toolbar-right { min-width: 0; }
.input-toolbar-right { flex-wrap: nowrap; gap: 4px; }
.model-select { width: 162px; }
.model-select :deep(.el-input__inner) { color: var(--text-secondary); }
.input-tool-btn { width: 32px; height: 32px; flex-shrink: 0; color: var(--text-secondary); }
.input-tool-btn:hover { color: var(--pulsar); }
.input-tool-btn:disabled { opacity: .5; cursor: default; }
.knowledge-scope-pill { max-width: 190px; min-width: 0; height: 32px; padding: 0 8px; gap: 7px; border: 0; border-radius: 8px; color: var(--text-secondary); background: transparent; font-size: 12px; }
.knowledge-scope-pill:hover { background: var(--bg-selected); color: var(--text-primary); }
.scope-mode { padding-left: 7px; border-left: 1px solid var(--border); font-size: 11px; color: var(--text-muted); }
.scope-chevron { flex-shrink: 0; color: var(--text-muted); }
.scope-mode-short { display: none; }
.scope-current { margin-top: 0; padding-bottom: 10px; border-bottom: 1px solid #3c3c3c; overflow-wrap: anywhere; }
.chat-textarea :deep(.el-textarea__inner) { color: var(--text-primary); font-size: 15px; min-height: 48px !important; }
.chat-textarea :deep(.el-textarea__inner::placeholder) { color: var(--text-secondary); opacity: 1; }
.input-send-btn { background: var(--bg-selected); color: var(--text-secondary); border: 1px solid var(--control-border); border-radius: 11px; }
.input-send-btn.ready { background: var(--pulsar); color: var(--on-accent); box-shadow: 0 5px 18px var(--pulsar-glow); }
.input-send-btn.ready:hover { box-shadow: 0 8px 25px rgba(224,224,224,.28); }
.input-footer { padding-bottom: 10px; }
.input-hint, .input-count { color: var(--text-muted); opacity: 1; }
.scroll-bottom-btn { bottom: 105px; background: var(--bg-tertiary); border-color: var(--border); color: var(--text-primary); }
.scroll-bottom-btn:hover { box-shadow: 0 10px 24px rgba(0,0,0,.3); }
.scope-help { color: var(--text-secondary); }

@media (max-width: 720px) {
  .sidebar:not(.collapsed) { position: absolute; z-index: 80; inset: 0 auto 0 0; width: min(300px, calc(100vw - 56px)); box-shadow: 18px 0 48px rgba(0,0,0,.48); }
  .sidebar.collapsed { width: 56px; }
  .sidebar.collapsed .sidebar-header { justify-content: center; padding-inline: 8px; }
  .has-conversation-outline .messages-area, .messages-area { padding-inline: 12px; }
  .msg-bubble { max-width: 100%; gap: 7px; }
  .msg-content-wrapper { padding: 12px 13px; }
  :deep(.conversation-outline) { display: none; }
  .input-area, .input-area:not(.input-expanded), .input-area.input-expanded { padding-inline: 10px; padding-bottom: max(10px, env(safe-area-inset-bottom)); }
  .morph-shell.morph-expanded { width: 100%; max-width: calc(100vw - 20px); }
  .input-toolbar { flex-wrap: wrap; align-items: flex-start; }
  .input-toolbar-right { margin-left: 0; width: 100%; flex-wrap: nowrap; justify-content: space-between; }
  .input-tool-btn.workspace-tools-entry { min-width: 32px; width: 32px; padding: 0; }
  .workspace-tools-entry span { display: none; }
  .knowledge-scope-pill { flex: 1; max-width: none; min-width: 0; }
  .file-intent-card { margin-left: 0; }
  .messages-area.input-is-expanded { padding-bottom: 255px; }
  .scroll-bottom-btn { bottom: 100px; right: 15px; }
}

@media (prefers-reduced-motion: reduce) {
  .morph-orb { animation: none; }
  .morph-shell, .morph-expanded-body, .morph-expand-icon { transition-duration: 0.01ms !important; }
}
@media (max-width: 440px) {
  .knowledge-scope-pill { justify-content: center; gap: 4px; padding-inline: 5px; }
  .knowledge-scope-pill .scope-label, .scope-mode-full { display: none; }
  .scope-mode-short { display: inline; }
  .knowledge-scope-pill .scope-mode { border: 0; padding-left: 0; font-size: 12px; }
}
</style>
