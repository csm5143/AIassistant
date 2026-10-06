<template>
  <div class="markdown-body" v-html="renderedHtml"></div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import { renderStructuredJson } from '@/utils/structuredJson'

interface Props {
  content: string
  msgId?: string
}

const props = defineProps<Props>()

// ---- MarkdownIt with custom highlight + line numbers ----
const md: MarkdownIt = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: true,
  typographer: true,
  highlight(str: string, lang: string): string {
    let highlighted: string
    if (lang && hljs.getLanguage(lang)) {
      try {
        highlighted = hljs.highlight(str, { language: lang, ignoreIllegals: true }).value
      } catch {
        highlighted = md.utils.escapeHtml(str)
      }
    } else {
      try {
        highlighted = hljs.highlightAuto(str).value
      } catch {
        highlighted = md.utils.escapeHtml(str)
      }
    }

    // Wrap each line for line-number CSS counters
    const lines = highlighted.split('\n')
    const wrapped = lines
      .map((line, i) => `<span class="code-line" data-line="${i + 1}">${line || '&#8203;'}</span>`)
      .join('\n')

    return `<pre class="hljs"><code>${wrapped}</code></pre>`
  },
})

// ---- Pre/post processing for citation markers ----
function renderContent(text: string, msgId?: string): string {
  if (!text) return ''
  const structured = renderStructuredJson(text)
  if (structured !== null) return structured

  let processed = text.replace(
    /\(《[^》]+\.(md|pdf|docx?|txt|xlsx?|pptx?)\)/g,
    '⟨fn⟩$1⟨/fn⟩'
  )
  processed = processed.replace(/\[(\d+)\]/g, '⟨cite⟩$1⟨/cite⟩')

  let html = md.render(processed)

  html = html.replace(/⟨fn⟩/g, '').replace(/⟨\/fn⟩/g, '')
  html = html.replace(/⟨cite⟩(\d+)⟨\/cite⟩/g, (_, num: string) => {
    return `<sup class="cite-badge" data-cite="${num}" data-msg="${msgId || ''}">[${num}]</sup>`
  })

  return html
}

// Computed rendering — always in sync with props, no timing issues
const renderedHtml = computed(() => renderContent(props.content || '', props.msgId))
</script>

<style>
/*
 * ═══════════════════════════════════════════════════════
 *  Custom highlight.js theme — "Obsidian Type"
 *  Designed for extended code reading in chat UI.
 *  Palette: grayscale tokens on deep charcoal.
 * ═══════════════════════════════════════════════════════
 */

/* ---- Base ---- */
.markdown-body pre {
  counter-reset: line 0;
  background: var(--bg-code);
  color: var(--text-primary);
  margin: 12px 0;
  padding: 14px 16px;
  border-radius: 6px;
  border: 1px solid var(--border);
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.6;
  font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', 'Consolas', monospace;
  position: relative;

  /* Right-edge scroll hint */
  background-image:
    linear-gradient(to right, transparent 95%, rgba(255,255,255,0.04) 100%);
  background-size: 40px 100%;
  background-repeat: no-repeat;
  background-position: right center;
  background-attachment: scroll;
}

.markdown-body pre code {
  background: transparent;
  color: inherit;
  padding: 0;
  font-size: inherit;
  line-height: inherit;
  font-family: inherit;
  display: block;
}

/* ---- Line numbers ---- */
.markdown-body .code-line {
  display: block;
  min-height: 1.6em;
}

.markdown-body .code-line::before {
  counter-increment: line;
  content: counter(line);
  display: inline-block;
  width: 2.25em;
  margin-right: 1.25em;
  padding-right: 0.75em;
  text-align: right;
  color: #b0b0b0;
  user-select: none;
  -webkit-user-select: none;
  flex-shrink: 0;
}

/* ---- Token: Keywords (function, return, const, if, class, etc.) ---- */
.hljs-keyword,
.hljs-meta .hljs-keyword,
.hljs-template-tag,
.hljs-template-variable,
.hljs-selector-tag {
  color: #b0b0b0;
}

/* ---- Token: Types (TypeScript types, classes) ---- */
.hljs-type,
.hljs-title.class_,
.hljs-title.class_.inherited__,
.hljs-doctag {
  color: #b8b8b8;
}

/* ---- Token: Function names ---- */
.hljs-title.function_ {
  color: #e0e0e0;
}

/* ---- Token: Strings ---- */
.hljs-regexp,
.hljs-string,
.hljs-meta .hljs-string {
  color: #b0b0b0;
}

/* ---- Token: Numbers ---- */
.hljs-number {
  color: #cccccc;
}

/* ---- Token: Literals (true, false, null, undefined) ---- */
.hljs-literal {
  color: #b0b0b0;
}

/* ---- Token: Built-ins (console, parseInt, etc.) ---- */
.hljs-built_in,
.hljs-symbol {
  color: #b0b0b0;
}

/* ---- Token: Comments ---- */
.hljs-comment,
.hljs-code,
.hljs-formula {
  color: #b0b0b0;
  font-style: italic;
}

/* ---- Token: Variables / parameters ---- */
.hljs-variable,
.hljs-params,
.hljs-property,
.hljs-attr,
.hljs-attribute {
  color: #cccccc;
}

.hljs-variable.language_ {
  color: #b0b0b0;
}

/* ---- Token: Meta / annotations (TypeScript @decorator, etc.) ---- */
.hljs-meta {
  color: #b0b0b0;           /* muted grey — background noise */
}

.hljs-meta:not(.hljs-keyword):not(.hljs-string) {
  color: #b0b0b0;
}

/* ---- Token: Punctuation — reduced contrast for structure scannability ---- */
.hljs-punctuation,
.markdown-body pre .hljs-punctuation {
  color: #b8b8b8;
}

/* ---- Token: Operators ---- */
.hljs-operator,
.hljs-selector-attr,
.hljs-selector-class,
.hljs-selector-id {
  color: #d4d4d4;           /* bright enough to see */
}

/* ---- Token: Tags (JSX/HTML) ---- */
.hljs-name,
.hljs-quote,
.hljs-selector-pseudo {
  color: #b0b0b0;
}

/* ---- Token: Deletion / Insertion (diff mode) ---- */
.hljs-deletion { color: #b0b0b0; background: rgba(160,160,160, 0.12); }
.hljs-addition { color: #cccccc; background: rgba(204,204,204, 0.12); }

/* ---- Token: Emphasis / Strong in markdown ---- */
.hljs-emphasis { font-style: italic; }
.hljs-strong   { font-weight: 600; color: #e0e0e0; }

/* ---- Token: Link / URL ---- */
.hljs-link {
  color: #b8b8b8;
  text-decoration: underline;
}

/* ---- Token: Subst / section markers ---- */
.hljs-subst {
  color: #cccccc;
}

/* ---- Token: Title (headings in markdown/docs) ---- */
.hljs-title {
  color: #e0e0e0;
}

/* ---- Unrecognized / fallback ---- */
.hljs-section {
  color: #b0b0b0;
  font-weight: 600;
}

/*
 * ═══════════════════════════════════════════════════════
 *  Global markdown body styles
 * ═══════════════════════════════════════════════════════
 */

.markdown-body {
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-primary);
  word-break: break-word;
}

.markdown-body p { margin: 0 0 10px; line-height: 1.75; }
.markdown-body p:last-child { margin-bottom: 0; }

.markdown-body strong { color: var(--text-primary); font-weight: 700; }
.markdown-body em { color: var(--text-secondary); }

/* Headings */
.markdown-body h1, .markdown-body h2, .markdown-body h3,
.markdown-body h4, .markdown-body h5, .markdown-body h6 {
  margin: 24px 0 10px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--text-primary);
}
.markdown-body h1:first-child, .markdown-body h2:first-child, .markdown-body h3:first-child { margin-top: 0; }
.markdown-body h1 { font-size: 1.35em; border-bottom: 1px solid var(--horizon-soft); padding-bottom: 8px; }
.markdown-body h2 { font-size: 1.2em; border-bottom: 1px solid var(--horizon-soft); padding-bottom: 6px; }
.markdown-body h3 { font-size: 1.1em; }
.markdown-body h4 { font-size: 1em; }

/* Section spacing */

/* Inline code */
.markdown-body code {
  font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace;
  font-size: 0.9em;
}
.markdown-body :not(pre) > code {
  background: var(--bg-subtle);
  color: var(--text-primary);
  padding: 2px 6px;
  border-radius: 4px;
}

/* Lists */
.markdown-body ul, .markdown-body ol { padding-left: 22px; margin: 6px 0; }
.markdown-body li { margin: 3px 0; }
.markdown-body li > p { margin: 0; }

/* Blockquotes */
.markdown-body blockquote {
  border-left: 3px solid var(--pulsar, #e0e0e0);
  padding-left: 14px;
  margin: 10px 0;
  color: var(--text-secondary);
}

/* Links */
.markdown-body a { color: var(--citation, #8ab4f8); text-decoration: none; }
.markdown-body a:hover { color: var(--citation-hover, #b8d4ff); text-decoration: underline; }

/* Tables */
.markdown-body table {
  border-collapse: collapse; width: 100%;
  margin: 12px 0 16px; font-size: 13px;
}
.markdown-body th, .markdown-body td {
  border: 1px solid var(--border); padding: 10px 14px; text-align: left;
}
.markdown-body th { background: var(--bg-subtle); font-weight: 600; }
.markdown-body td { line-height: 1.6; }
.markdown-body tr:nth-child(even) td { background: var(--bg-subtle); }
.markdown-body tr:nth-child(even) { background: var(--bg-subtle); }

/* Images */
.markdown-body img { max-width: 100%; border-radius: 8px; margin: 8px 0; }

/* Horizontal rule */
.markdown-body hr { border: none; border-top: 1px solid var(--horizon-soft); margin: 16px 0; }

/* Citation badges */
.markdown-body .cite-badge {
  display: inline !important;
  font-size: 12px; font-weight: 600;
  line-height: 1;
  color: var(--citation, #8ab4f8);
  cursor: pointer;
  margin: 0 1px 0 3px;
  padding: 0 3px;
  border-radius: 3px;
  transition: color 0.15s, background-color 0.15s;
  text-decoration: none;
  user-select: none;
}
.markdown-body .cite-badge:hover {
  background: var(--citation-glow, rgba(138,180,248, 0.14));
  color: var(--citation-hover, #b8d4ff);
}
.markdown-body .cite-badge:focus-visible { outline: 2px solid var(--citation, #8ab4f8); outline-offset: 2px; }
</style>
