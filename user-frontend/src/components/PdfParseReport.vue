<template>
  <details v-if="value" class="pdf-parse-report" @click.stop>
    <summary>{{ value.warnings?.length ? '⚠ 解析提醒' : '解析详情' }} · {{ value.totalPages }} 页</summary>
    <div>文字提取 {{ value.nativePages }} 页 · 增强解析 {{ value.enhancedPages }} 页 · 扫描识别 {{ value.ocrPages }} 页</div>
    <div v-if="value.tables || value.images">保留 {{ value.tables }} 个表格 · 发现 {{ value.images }} 张图像</div>
    <div>图表 API 调用 {{ value.visionCalls }} 次 · {{ value.visionTokens }} token · 命中缓存 {{ value.cacheHits }} 次</div>
    <div v-if="value.visionCalls || value.images" class="parse-hint">AI 图表描述已单独标注，具体数值请结合原页核验。</div>
    <ul v-if="value.warnings?.length"><li v-for="warning in value.warnings" :key="warning">{{ warning }}</li></ul>
  </details>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { PdfParseReport } from '@/api'
const props = defineProps<{ report?: PdfParseReport | string | null }>()
const value = computed<PdfParseReport | null>(() => {
  if (!props.report) return null
  try { return typeof props.report === 'string' ? JSON.parse(props.report) : props.report } catch { return null }
})
</script>
<style scoped>
.pdf-parse-report { font-size: 12px; color: var(--text-secondary, #808080); margin-top: 6px; line-height: 1.7; overflow-wrap: anywhere; }
summary { cursor: pointer; }
.parse-hint { margin-top: 4px; }
ul { margin: 4px 0; padding-left: 18px; color: #b0b0b0; }
</style>
