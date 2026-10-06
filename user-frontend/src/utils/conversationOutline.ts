export interface OutlineMessage {
  id: string
  renderKey?: string
  role: string
  content: string
  extra?: string | null
}

export interface ConversationTurn {
  key: string
  number: number
  question: string
  summary: string
  files: string[]
  images: string[]
  answered: boolean
}

/** Small text previews only; never render message HTML or call a model. */
export function outlineSnippet(content: string, limit: number): string {
  const text = content.slice(0, 2400)
    .split(/\n(?:---\s*\n)?\s*(?:\*\*)?(?:参考来源|References?|Reference Sources?)(?:\*\*)?\s*\n/i)[0]
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/\[\d+\]/g, '')
    .replace(/<[^>]*>/g, '')
    .replace(/&#(x[\da-f]+|\d+);/gi, (entity, value: string) => {
      const n = value[0].toLowerCase() === 'x' ? parseInt(value.slice(1), 16) : Number(value)
      return n > 0 && n <= 0x10ffff && !(n >= 0xd800 && n <= 0xdfff) ? String.fromCodePoint(n) : entity
    })
    .replace(/&(amp|lt|gt|quot|apos|nbsp);/g, (_, key: string) => ({ amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' }[key]!))
    .replace(/```[^\n]*\n/g, '').replace(/```/g, '')
    .replace(/(?:^|\n)\s*(?:#{1,6}\s+|>\s*|[-*+]\s+)/g, ' ')
    .replace(/(^|\s)_{1,2}([^\n]+?)_{1,2}(?=\s|$|[.,!?，。！？])/g, '$1$2')
    .replace(/[*`]|~~/g, '').replace(/\s+/g, ' ').trim()
  const characters = Array.from(text)
  return characters.length > limit ? characters.slice(0, limit).join('') + '…' : text
}

export function buildConversationTurns(
  messages: readonly OutlineMessage[],
  citations: Record<string, readonly { fileName: string; sourceType?: string }[]> = {},
  attachments: Record<string, string> = {},
  images: Record<string, string[]> = {},
): ConversationTurn[] {
  const turns: ConversationTurn[] = []
  let current: ConversationTurn | undefined
  for (const message of messages) {
    if (message.role === 'user') {
      const key = message.renderKey || message.id
      let extra: { images?: unknown; fileName?: unknown } = {}
      try { extra = message.extra ? JSON.parse(message.extra) || {} : {} } catch { /* legacy metadata */ }
      const file = attachments[key] || attachments[message.id] || (typeof extra.fileName === 'string' ? extra.fileName : '')
      const thumbnails = images[key] || images[message.id] || (Array.isArray(extra.images) ? extra.images : [])
      current = {
        key, number: turns.length + 1,
        question: outlineSnippet(message.content, 180) || file || '图片提问',
        summary: '', files: file ? [file] : [],
        images: thumbnails.filter((url): url is string => typeof url === 'string' && Boolean(url.trim())).slice(0, 2),
        answered: false,
      }
      turns.push(current)
    } else if (message.role === 'assistant' && current) {
      if (message.content.trim()) {
        current.summary = outlineSnippet(message.content, 220)
        current.answered = true
      }
      for (const cite of citations[message.id] || []) {
        if (cite.sourceType !== 'web' && cite.fileName && !current.files.includes(cite.fileName)) current.files.push(cite.fileName)
      }
    }
  }
  return turns
}

export interface TurnAnchor { key: string; top: number }

/** Keep the same turn selected throughout its answer, including long answers. */
export function activeTurnAt(anchors: readonly TurnAnchor[], top: number, atBottom = false): string | null {
  if (!anchors.length) return null
  if (atBottom) return anchors[anchors.length - 1].key
  let low = 0, high = anchors.length - 1
  while (low < high) {
    const mid = Math.ceil((low + high) / 2)
    if (anchors[mid].top <= top) low = mid
    else high = mid - 1
  }
  return anchors[low].key
}
