import type { Citation } from '@/utils/sse'

/** Preserve original reference numbers; unused retrieval candidates never enter the source menu. */
export function usedAnswerCitations(answer: string, candidates: Citation[] = []): Citation[] {
  if (!answer || !candidates.length) return []
  try {
    const value = JSON.parse(answer)
    if (value !== null && typeof value === 'object') return []
  } catch { /* ordinary prose */ }
  const prose = answer
    .replace(/^[ \t]*(?:`{3,}|~{3,})[^\r\n]*\r?\n[\s\S]*?^[ \t]*(?:`{3,}|~{3,})[ \t]*(?:\r?\n|$)/gm, '')
    .replace(/(`+)[\s\S]*?\1/g, '')
  const refs = new Set<number>()
  for (const match of prose.matchAll(/(?<![\\!])\[(\d+)\](?!\()/g)) refs.add(Number(match[1]))
  return candidates.filter(cite => refs.has(cite.index))
}
