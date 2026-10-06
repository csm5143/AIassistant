export interface ConversationFile { id: string; name: string }

// This only selects the existing full-document QA path. The model still decides
// which file operation to run and asks for missing arguments.
export function isFileActionRequest(text: string): boolean {
  return /合并|拆分|分拆|旋转|提取.{0,12}(页|字段|信息)|抽取|汇总.{0,12}(表|字段)|导出|下载|生成.{0,15}(报告|文档|文件|word|pdf|excel|图表)|(?:制作|写|做).{0,12}(报告|表格)|整理.{0,12}(成|报告|表格)|去重|按.{0,8}(月|年|类别).{0,8}统计|\b(merge|split|rotate|extract|export|download|deduplicate)\b|\b(create|generate|make)\b.{0,40}\b(report|docx|pdf|xlsx|chart)\b/i.test(text)
}
export function isDocumentQuestion(text: string): boolean {
  return !isFileActionRequest(text) && /[?？]|什么|为何|为什么|如何|多少|哪些|解释|总结|概括|翻译|阅读|介绍|分析|比较|对比|评价|是否|有没有|是什么|\b(what|why|how|when|where|which|summari[sz]e|translate|explain|compare|describe|analy[sz]e)\b/i.test(text)
}
export function attachmentSuggestions(files: ConversationFile[]) {
  const names = files.map(f => `「${f.name}」`).join('、')
  if (files.some(f => /\.(xlsx?|csv)$/i.test(f.name))) return [
    { label: '分析表格', prompt: `请分析这些文件中的表格：${names}。先检查列名与数据，选择有意义的统计，生成 Excel 和图表。` },
    { label: '检查数据', prompt: `请检查 ${names} 的表格结构、缺失值与重复记录，先说明问题，不修改文件。` },
  ]
  return [
    { label: '总结内容', prompt: `请总结 ${names} 的主要内容，保留关键依据。` },
    { label: '提取信息', prompt: `我想从 ${names} 提取信息。请先询问我需要哪些字段，再执行。` },
    ...(files.length > 1 && files.every(f => /\.pdf$/i.test(f.name))
      ? [{ label: '合并 PDF', prompt: `请按上传顺序将 ${names} 合并成一个可下载的 PDF。` }]
      : files.length === 1 && /\.pdf$/i.test(files[0].name)
        ? [{ label: '整理页面', prompt: `我想整理 ${names} 的页面。请先询问我要提取、拆分还是旋转，以及页码范围。` }]
        : []),
  ]
}
