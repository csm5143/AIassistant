<template>
  <nav ref="navRoot" class="conversation-outline" aria-label="当前对话导航" @mouseleave="scheduleClose" @focusout="onFocusOut" @keydown.esc.stop="closePreview">
    <span class="outline-heading" :title="`对话导航 · ${turns.length} 轮，悬停预览，点击跳转`" aria-hidden="true">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 6h16M4 12h10M4 18h16" /></svg>
    </span>
    <div ref="track" class="outline-track" @scroll="onTrackScroll">
      <button v-for="turn in turns" :key="turn.key" type="button"
        class="outline-tick" :class="{ active: turn.key === activeKey, previewing: turn.key === previewKey }"
        :data-outline-key="turn.key" :aria-label="`第 ${turn.number} 轮：${turn.question}`"
        :aria-current="turn.key === activeKey ? 'step' : undefined"
        :tabindex="turn.key === (activeKey || turns[0]?.key) ? 0 : -1"
        @mouseenter="showPreview(turn, $event)" @focus="showPreview(turn, $event)"
        @click="navigate(turn.key)" @keydown="onTickKey($event, turn)">
        <span class="outline-tick-line"></span>
      </button>
    </div>
    <span class="outline-count" :title="`当前第 ${activeNumber} 轮，共 ${turns.length} 轮`">{{ activeNumber }}/{{ turns.length }}</span>
    <Teleport to="body">
      <section v-if="preview" ref="panel" class="outline-preview" role="dialog"
        :aria-label="`第 ${preview.number} 轮对话预览`" :style="panelStyle"
        @mouseenter="cancelClose" @mouseleave="scheduleClose" @focusout="onFocusOut" @keydown.esc.stop="closePreview">
        <div class="outline-preview-meta"><span>第 {{ preview.number }} 轮</span><span v-if="streaming && preview.key === turns[turns.length - 1]?.key">正在生成</span></div>
        <p class="outline-preview-question">{{ preview.question }}</p>
        <p class="outline-preview-answer">{{ preview.summary || (streaming && preview.key === turns[turns.length - 1]?.key ? '正在生成回答…' : '暂无回答') }}</p>
        <div v-if="preview.images.length" class="outline-preview-images">
          <img v-for="url in preview.images" :key="url" :src="url" alt="此轮提问的图片" loading="lazy" />
        </div>
        <div v-if="preview.files.length" class="outline-preview-files">
          <span v-for="file in preview.files.slice(0, 2)" :key="file" class="outline-preview-file" :title="file">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8zM14 2v6h6" /></svg><span>{{ file }}</span>
          </span>
          <span v-if="preview.files.length > 2" class="outline-more-files">另 {{ preview.files.length - 2 }} 个来源</span>
        </div>
        <button type="button" class="outline-preview-jump" @click="navigate(preview.key)">跳到此处 <span aria-hidden="true">↗</span></button>
      </section>
    </Teleport>
  </nav>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { ConversationTurn } from '@/utils/conversationOutline'
const props = defineProps<{ turns: ConversationTurn[]; activeKey: string | null; streaming: boolean }>()
const emit = defineEmits<{ navigate: [key: string] }>()
const navRoot = ref<HTMLElement>()
const track = ref<HTMLElement>()
const panel = ref<HTMLElement>()
const previewKey = ref<string | null>(null)
const preview = computed(() => props.turns.find(turn => turn.key === previewKey.value))
const activeNumber = computed(() => props.turns.find(turn => turn.key === props.activeKey)?.number || 1)
const panelStyle = ref({ left: '0px', top: '0px', width: '336px' })
let closeTimer: ReturnType<typeof setTimeout> | undefined
let triggerRect: DOMRect | undefined
function cancelClose() { if (closeTimer) clearTimeout(closeTimer); closeTimer = undefined }
function closePreview() { cancelClose(); previewKey.value = null }
function scheduleClose() {
  cancelClose()
  closeTimer = setTimeout(() => {
    closeTimer = undefined
    const active = document.activeElement as HTMLElement | null
    const focusedKey = active?.closest<HTMLElement>('[data-outline-key]')?.dataset.outlineKey
    // Mouse movement must not dismiss the preview being read with the keyboard.
    if ((focusedKey === previewKey.value && active && navRoot.value?.contains(active)) || (active && panel.value?.contains(active))) return
    closePreview()
  }, 180)
}
function onFocusOut(event: FocusEvent) {
  const target = event.relatedTarget as Node | null
  if (!target || (!navRoot.value?.contains(target) && !panel.value?.contains(target))) scheduleClose()
}
function placePreview() {
  if (!triggerRect || !preview.value) return
  const width = Math.min(336, window.innerWidth - 24)
  const height = panel.value?.getBoundingClientRect().height || 280
  panelStyle.value = {
    left: `${Math.max(12, Math.min(triggerRect.right + 12, window.innerWidth - width - 12))}px`,
    top: `${Math.max(12, Math.min(triggerRect.top - 40, window.innerHeight - height - 12))}px`,
    width: `${width}px`,
  }
}
function showPreview(turn: ConversationTurn, event: MouseEvent | FocusEvent) {
  cancelClose()
  triggerRect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  previewKey.value = turn.key
  placePreview()
  nextTick(placePreview)
}
function onTrackScroll() {
  if (!previewKey.value || !track.value) return
  const button = Array.from(track.value.querySelectorAll<HTMLElement>('.outline-tick')).find(item => item.dataset.outlineKey === previewKey.value)
  if (!button) { closePreview(); return }
  const rect = button.getBoundingClientRect()
  const bounds = track.value.getBoundingClientRect()
  if (rect.bottom <= bounds.top || rect.top >= bounds.bottom) { closePreview(); return }
  triggerRect = rect
  placePreview()
}
function navigate(key: string) { closePreview(); emit('navigate', key) }
function onTickKey(event: KeyboardEvent, turn: ConversationTurn) {
  const delta = event.key === 'ArrowDown' ? 1 : event.key === 'ArrowUp' ? -1 : 0
  if (!delta && event.key !== 'Home' && event.key !== 'End') return
  event.preventDefault()
  const index = props.turns.findIndex(item => item.key === turn.key)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? props.turns.length - 1 : Math.max(0, Math.min(props.turns.length - 1, index + delta))
  track.value?.querySelectorAll<HTMLButtonElement>('.outline-tick')[next]?.focus()
}
async function keepActiveVisible() {
  await nextTick()
  const key = props.activeKey
  const el = track.value
  const button = Array.from(el?.querySelectorAll<HTMLElement>('.outline-tick') || []).find(item => item.dataset.outlineKey === key)
  if (!el || !button) return
  if (button.offsetTop < el.scrollTop) el.scrollTop = button.offsetTop
  else if (button.offsetTop + button.offsetHeight > el.scrollTop + el.clientHeight) el.scrollTop = button.offsetTop + button.offsetHeight - el.clientHeight
}
watch(() => [props.activeKey, props.turns.length], keepActiveVisible, { immediate: true })
const trackObserver = new ResizeObserver(() => { void keepActiveVisible(); onTrackScroll() })
watch(track, el => { trackObserver.disconnect(); if (el) trackObserver.observe(el) }, { flush: 'post' })
const panelObserver = new ResizeObserver(placePreview)
watch(panel, el => { panelObserver.disconnect(); if (el) panelObserver.observe(el) }, { flush: 'post' })
onMounted(() => window.addEventListener('resize', closePreview))
onUnmounted(() => { cancelClose(); panelObserver.disconnect(); trackObserver.disconnect(); window.removeEventListener('resize', closePreview) })
</script>

<style scoped>
.conversation-outline { position: absolute; z-index: 35; left: 6px; top: 24px; bottom: 104px; width: 32px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; }
.outline-heading { flex-shrink: 0; color: var(--twilight); height: 20px; display: flex; align-items: center; }
.outline-track { position: relative; width: 32px; min-height: 0; max-height: min(42vh, calc(100% - 56px)); overflow-y: auto; overscroll-behavior: contain; scrollbar-width: none; display: flex; flex-direction: column; align-items: center; padding: 4px 0; }
.outline-track::-webkit-scrollbar { display: none; }
.outline-tick { flex-shrink: 0; width: 32px; height: 22px; border: 0; border-radius: 5px; background: transparent; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.outline-tick-line { height: 2px; width: 12px; background: #a0a0a0; border-radius: 2px; transition: width .15s, background .15s; }
.outline-tick:hover, .outline-tick.previewing { background: var(--pulsar-glow); }
.outline-tick.active .outline-tick-line { width: 24px; height: 3px; background: var(--pulsar-deep); }
.outline-tick.previewing .outline-tick-line { width: 20px; background: var(--pulsar); }
.outline-tick:focus-visible { outline: 2px solid var(--pulsar); outline-offset: 1px; }
.outline-count { flex-shrink: 0; height: 20px; display: flex; align-items: center; font-size: 10px; color: var(--twilight); font-variant-numeric: tabular-nums; white-space: nowrap; }
.outline-preview { position: fixed; z-index: 1100; padding: 14px; background: var(--bg-card); border: 1px solid var(--horizon-soft); border-radius: 12px; box-shadow: var(--shadow-lg); color: var(--text-primary); max-height: calc(100vh - 24px); overflow-y: auto; }
.outline-preview-meta { display: flex; justify-content: space-between; gap: 8px; font-size: 11px; color: var(--twilight); margin-bottom: 7px; }
.outline-preview-question { font-size: 13px; line-height: 1.55; font-weight: 600; overflow-wrap: anywhere; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.outline-preview-answer { margin-top: 8px; font-size: 12px; line-height: 1.65; color: var(--text-secondary); overflow-wrap: anywhere; display: -webkit-box; -webkit-line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden; }
.outline-preview-files { display: flex; flex-direction: column; gap: 5px; margin-top: 9px; }
.outline-preview-file { display: flex; align-items: center; gap: 5px; color: var(--pulsar); font-size: 11px; min-width: 0; }
.outline-preview-file svg { flex-shrink: 0; }
.outline-preview-file span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.outline-more-files { color: var(--twilight); font-size: 11px; }
.outline-preview-images { display: flex; gap: 7px; margin-top: 10px; }
.outline-preview-images img { width: 82px; height: 62px; border-radius: 6px; object-fit: cover; border: 1px solid var(--horizon-soft); }
.outline-preview-jump { display: flex; justify-content: space-between; align-items: center; width: 100%; margin-top: 12px; padding-top: 10px; border: 0; border-top: 1px solid var(--horizon-soft); background: transparent; color: var(--pulsar); font: inherit; font-size: 12px; cursor: pointer; }
.outline-preview-jump:focus-visible { outline: 2px solid var(--pulsar); outline-offset: 3px; }
@media (pointer: coarse) { .outline-tick { height: 36px; } }
@media (prefers-reduced-motion: reduce) { .outline-tick-line { transition: none; } }
</style>
