const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const source = fs.readFileSync(path.join(__dirname, '../src/utils/researchReport.ts'), 'utf8')
const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const report = {}
new Function('exports', code)(report)
const citations = [{ index: 1 }, { index: 4 }]
assert.deepEqual(report.citationAudit('结论 [4][1][4]，另一结论 [99]。', citations), {
  used: [1, 4], unknown: [99], registered: 2,
})
assert.deepEqual(report.citationAudit('没有标注的结论。', citations).used, [])
assert.equal(report.stripReferenceTail('结论 [1]。\n\n**参考来源**\n[1] paper.pdf · 第 2 页\n'), '结论 [1]。')
assert.equal(report.stripReferenceTail('## 参考来源的可信度\n需要人工检查 [1]。'), '## 参考来源的可信度\n需要人工检查 [1]。')
console.log('Research citation validity and reference-tail checks passed')
