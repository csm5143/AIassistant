const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const source = fs.readFileSync(path.join(__dirname, '../src/utils/sse.ts'), 'utf8')
const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
const exportsObject = {}
new Function('exports', 'require', code)(exportsObject, id => id === '@/api' ? { refreshAccessToken: () => Promise.reject() } : require(id))
const received = []
const parser = exportsObject.createSseParser((type, data) => received.push({ type, data }))
const encoder = new TextEncoder()
for (const part of ['event: token\r\ndata: 你', '好\r\ndata: 第二行\r', '\n\r\nevent: done\n', 'data: ok\n\n']) {
  parser.feed(encoder.encode(part))
}
parser.finish()
assert.deepEqual(received, [
  { type: 'token', data: '你好\n第二行' },
  { type: 'done', data: 'ok' },
])
console.log('SSE parser chunk boundary and multiline tests passed')

async function verifyDispatch() {
  const actual = { tokens: [], replacements: [], tools: [], citations: [], errors: [] }
  const metadata = '{"embeddingRequests":0,"enabled":true,"directReply":false}'
  const frames = [
    ['memory_context', metadata], ['request_stats', metadata], ['future_diagnostic', metadata],
    ['retrieval_stats', '{}'], ['research_stats', '{}'], ['usage', '{}'],
    ['token', '你好'], ['token', '{"answer":42}'],
    ['message', '旧版正文'],
    ['replace', '最终回答'],
    ['tool_call', '{"name":"search","arguments":"今天"}'],
    ['tool_result', '{"name":"search","result":"完成","status":"success"}'],
    ['citations', '[{"index":1}]'], ['done', 'ok'],
    ['token', '结束后不应显示'],
  ]
  const bytes = encoder.encode(frames.map(([event, data]) => `event: ${event}\r\ndata: ${data}\r\n\r\n`).join(''))
  const originalFetch = global.fetch
  global.fetch = async () => new Response(new ReadableStream({
    start(controller) {
      // Split UTF-8 characters and event boundaries as a real network may do.
      for (let i = 0; i < bytes.length; i += 7) controller.enqueue(bytes.slice(i, i + 7))
      controller.close()
    },
  }), { status: 200 })
  try {
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Stream did not complete')), 2000)
      exportsObject.streamChat('fixture', '问题', 'test-only', {
        onToken: text => actual.tokens.push(text),
        onReplace: text => actual.replacements.push(text),
        onToolCall: tool => actual.tools.push(tool),
        onToolResult: tool => actual.tools.push(tool),
        onCitations: citations => actual.citations.push(citations),
        onError: error => { clearTimeout(timeout); reject(new Error(error)) },
        onDone: () => { clearTimeout(timeout); resolve() },
      })
    })
    assert.deepEqual(actual.tokens, ['你好', '{"answer":42}', '旧版正文'])
    assert.deepEqual(actual.replacements, ['最终回答'])
    assert.equal(actual.tools.length, 2)
    assert.deepEqual(actual.citations, [[{ index: 1 }]])
    console.log('SSE dispatch: diagnostics hidden, JSON answers and named callbacks preserved')
  } finally {
    global.fetch = originalFetch
  }
}
verifyDispatch().catch(error => { console.error(error); process.exitCode = 1 })
