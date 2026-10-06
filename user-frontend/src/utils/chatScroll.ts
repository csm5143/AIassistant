// Follow new content only while the reader stays at the bottom.
export interface ScrollViewport {
  scrollTop: number
  scrollHeight: number
  clientHeight: number
}

export function createChatScroll(
  viewport: () => ScrollViewport | undefined,
  requestFrame: (callback: () => void) => number,
  cancelFrame: (id: number) => void,
  showReturn: (visible: boolean) => void,
) {
  let following = true
  let frame: number | null = null
  let lastTop = 0
  let lastHeight = 0
  let lastClientHeight = 0
  const nearBottom = (el: ScrollViewport) => el.scrollHeight - el.clientHeight - el.scrollTop <= 80
  const refreshButton = () => {
    const el = viewport()
    showReturn(Boolean(el && !following && !nearBottom(el)))
  }
  function pause() {
    following = false
    if (frame !== null) cancelFrame(frame)
    frame = null
    refreshButton()
  }
  function update() {
    refreshButton()
    if (!following || frame !== null) return
    frame = requestFrame(() => {
      frame = null
      const el = viewport()
      if (!following || !el) return
      // No smooth animation queue on every token. At most one write per frame.
      el.scrollTop = Math.max(0, el.scrollHeight - el.clientHeight)
      lastTop = el.scrollTop
      lastHeight = el.scrollHeight
      lastClientHeight = el.clientHeight
      refreshButton()
    })
  }
  function onScroll() {
    const el = viewport()
    if (!el) return
    const layoutChanged = el.scrollHeight !== lastHeight || el.clientHeight !== lastClientHeight
    // Native scroll anchoring/clamping during a resize is not a request to pause.
    if (!layoutChanged && el.scrollTop < lastTop - 1) pause()
    else if (!layoutChanged && el.scrollTop > lastTop + 1 && nearBottom(el)) following = true
    lastTop = el.scrollTop
    lastHeight = el.scrollHeight
    lastClientHeight = el.clientHeight
    if (following && layoutChanged) update()
    refreshButton()
  }
  function followLatest() {
    following = true
    const el = viewport()
    lastTop = el?.scrollTop || 0
    lastHeight = el?.scrollHeight || 0
    lastClientHeight = el?.clientHeight || 0
    update()
  }
  function dispose() {
    if (frame !== null) cancelFrame(frame)
    frame = null
  }
  function reset() {
    dispose()
    following = true
    lastTop = 0
    const el = viewport()
    lastHeight = el?.scrollHeight || 0
    lastClientHeight = el?.clientHeight || 0
    update()
  }
  return { update, onScroll, pause, followLatest, reset, dispose }
}
