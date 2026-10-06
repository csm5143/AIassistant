import hljs from 'highlight.js'

/** Valid JSON objects/arrays are data: do not alter quotes or turn array values into citations. */
export function renderStructuredJson(text: string): string | null {
  const source = text.trim()
  if (!source.startsWith('{') && !source.startsWith('[')) return null
  try {
    const value = JSON.parse(source)
    if (value === null || typeof value !== 'object') return null
    const html = hljs.highlight(source, { language: 'json', ignoreIllegals: true }).value
    return `<pre class="hljs"><code>${html}</code></pre>`
  } catch {
    return null
  }
}
