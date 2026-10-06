import { nextTick, onUnmounted, ref, watch, type ComputedRef, type Ref } from 'vue'
import { activeTurnAt, type ConversationTurn, type TurnAnchor } from '@/utils/conversationOutline'

export function useConversationNavigation(
  viewport: Ref<HTMLElement | undefined>,
  list: Ref<HTMLElement | undefined>,
  turns: ComputedRef<ConversationTurn[]>,
  pauseFollowing: () => void,
) {
  const activeKey = ref<string | null>(null)
  let anchors: TurnAnchor[] = []
  let dirty = true
  let frame: number | null = null

  function measure() {
    const el = viewport.value
    if (!el || !list.value) { anchors = []; return }
    const top = el.getBoundingClientRect().top
    const valid = new Set(turns.value.map(turn => turn.key))
    anchors = Array.from(list.value.querySelectorAll<HTMLElement>('[data-turn-key]'))
      .filter(node => valid.has(node.dataset.turnKey!))
      .map(node => ({ key: node.dataset.turnKey!, top: node.getBoundingClientRect().top - top + el.scrollTop }))
    dirty = false
  }

  function update(remeasure = false) {
    dirty ||= remeasure
    if (frame !== null) return
    frame = requestAnimationFrame(() => {
      frame = null
      const el = viewport.value
      if (dirty) measure()
      activeKey.value = el ? activeTurnAt(anchors,
        el.scrollTop + Math.min(120, el.clientHeight * 0.2),
        el.scrollHeight - el.scrollTop - el.clientHeight <= 8) : null
    })
  }

  async function jumpTo(key: string) {
    // Cancel pending stream follow before changing the scroll position.
    pauseFollowing()
    await nextTick()
    measure()
    const el = viewport.value
    const anchor = anchors.find(item => item.key === key)
    if (!el || !anchor) return
    el.scrollTo({ top: Math.max(0, anchor.top - 20),
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
    update()
  }

  // Text growth doesn't change keys. Layout changes are measured by the existing
  // ResizeObserver, while ordinary scrolling uses cached offsets + binary search.
  watch(() => turns.value.map(turn => turn.key).join('\u0000'), () => update(true), { flush: 'post' })
  watch([viewport, list], () => update(true), { flush: 'post' })
  onUnmounted(() => { if (frame !== null) cancelAnimationFrame(frame) })
  return { activeKey, update, jumpTo }
}
