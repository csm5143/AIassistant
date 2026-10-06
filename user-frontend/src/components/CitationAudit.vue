<template>
  <section v-if="audit?.claims?.length" class="citation-audit" aria-label="引用内容核验结果">
    <p>引用核验：模型判断支持 {{ audit.supported }} 项 · {{ audit.rejected }} 项矛盾或证据不足 · {{ audit.unchecked }} 项未完成</p>
    <small>只检查带编号引用的文本行；模型判断可能有误，原话另经逐字定位。原回答保留。</small>
    <details>
      <summary>查看结论与原话</summary>
      <div v-for="claim in audit.claims" :key="claim.id" class="audit-claim">
        <strong>{{ labels[claim.status] || '未完成核验' }}</strong>
        <p>{{ claim.text }}</p>
        <small>{{ claim.reason }}</small>
        <blockquote v-for="(quote, index) in claim.quotes" :key="index">[{{ quote.index }}] {{ quote.text }}</blockquote>
      </div>
    </details>
  </section>
</template>
<script setup lang="ts">
import type { CitationAudit } from '@/utils/sse'
defineProps<{ audit: CitationAudit | null }>()
const labels: Record<string,string> = { supported: '模型判断支持 · 原话已定位', contradicted: '原文矛盾', insufficient: '证据不足', not_checked: '未完成核验' }
</script>
<style scoped>
.citation-audit { margin: 10px 0; padding: 12px 14px; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-tertiary); color: var(--text-primary); font-size: 13px; line-height: 1.65; }
.citation-audit p { margin: 0 0 5px; }.citation-audit small { color: var(--text-secondary); }.citation-audit details { margin-top: 8px; }.citation-audit summary { cursor: pointer; }.audit-claim { padding: 10px 0; border-top: 1px solid var(--border); }.audit-claim blockquote { margin: 8px 0; padding-left: 12px; border-left: 2px solid var(--text-secondary); white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
