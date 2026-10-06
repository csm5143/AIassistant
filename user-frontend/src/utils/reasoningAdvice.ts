export type ReasoningReason = 'probability' | 'constraints' | 'sources'
export interface ReasoningAdvice { reasons: ReasoningReason[]; explanation: string }
const labels: Record<ReasoningReason, string> = {
  probability: '需要按给定条件重新确定概率或样本空间',
  constraints: '需要同时核对多个限制，避免只满足部分条件',
  sources: '需要逐项对照多份资料，处理一致之处与冲突',
}

/** Local draft advice only. Does not dispatch a request or change the selected mode. */
export function reasoningAdvice(question: string, sourceCount = 0): ReasoningAdvice | null {
  const q = question.trim().slice(0, 16000)
  if (!q) return null
  // Literal language/format operations are not requests to solve the quoted problem.
  if (/^(?:(?:请|帮我)\s*)?(?:把[“"「]|翻译[：:]|translate\b|(?:总结|摘要|改写|润色|提取|解释含义)[：:]\s*[“"「])/i.test(q)
    && /翻译|translate|总结|摘要|改写|润色|提取|解释含义/i.test(q)) return null
  if (/^(?:什么是|解释(?:一下)?|请解释)\s*(?:条件概率|概率|约束|多条件推理)(?:[？?。\s]|的含义|是什么|$)/.test(q)) return null
  const reasons: ReasoningReason[] = []
  if (/(?:条件概率|概率|probability|probabilities)/i.test(q)
    && /(?:已知|给定|至少|不放回|独立|条件下|given|at least|without replacement|conditional)/i.test(q)) reasons.push('probability')
  const conditions = q.match(/(?:至少|至多|不超过|不少于|不得|不能|必须|只有|除非|如果|不在|之前|之后|早.{0,4}天|晚.{0,4}天|至少|at least|at most|must|before|after|unless)/gi) || []
  const constraintTask = /(?:安排|排班|分配|规划|可行|有解|无解|满足|推出|证明|最优|schedule|assign|feasible|satisfy|prove)/i.test(q)
  const quantifiedLogic = /(?:所有|每个|任何).{0,35}(?:部分|有些|并非|不一定)/.test(q) && /推出|必然|能否|判断/.test(q)
  if (quantifiedLogic || constraintTask && conditions.length >= 2) reasons.push('constraints')
  const compare = /(?:比较|对比|对照|异同|冲突|矛盾|一致|综合|交叉核对|compare|contrast|conflict|reconcile)/i.test(q)
  const multipleSources = sourceCount >= 2 || /(?:两|二|多|[2-9]|two|multiple)\s*(?:份|篇|本|个|条)?\s*(?:资料|文档|报告|政策|论文|文件|引文|来源|documents?|reports?|sources?)/i.test(q)
    || /(?:资料|文档|报告|政策|论文|文件|引文)\s*[AＡ].*(?:资料|文档|报告|政策|论文|文件|引文)\s*[BＢ]/i.test(q)
  if (compare && multipleSources) reasons.push('sources')
  return reasons.length ? { reasons, explanation: reasons.map(reason => labels[reason]).join('；') } : null
}
