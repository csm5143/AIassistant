<template>
  <el-dialog v-model="visible" title="可核验文档统计" width="min(960px, 95vw)" append-to-body :close-on-click-modal="false" @closed="result = null">
    <p class="scope">先确认表格和条件。统计扫描所选表格的全部已解析记录，范围不包含未选表格，也不能证明 OCR 无遗漏。</p>
    <div v-if="loading">正在读取表格…</div>
    <template v-else>
      <label>统计表格（多选表头须完全一致）</label>
      <div class="tables"><label v-for="table in tables" :key="table.id"><input type="checkbox" :value="table.id" v-model="plan.tableIds" @change="result = null"> {{ table.id }} · {{ table.title || '表格' }} · {{ table.rowCount }} 行 <span v-if="table.issues.length">（有解析异常）</span><small>表头：{{ table.headers.join(' | ') }}</small></label></div>
      <p v-if="!tables.length">未识别到明确表头的表格。请提供 CSV、Excel 或结构清晰的表格，不能据此确认文档没有记录。</p>
      <div v-if="headers.length" class="controls">
        <label>运算<select v-model="plan.operation"><option v-for="(label, value) in operations" :key="value" :value="value">{{ label }}</option></select></label>
        <label v-if="plan.operation !== 'COUNT'">统计字段<select v-model="plan.valueField"><option v-for="h in headers" :key="h" :value="h">{{ h }}</option></select></label>
        <label>按字段去重<select v-model="plan.distinctBy"><option value="">不去重，每行计入</option><option v-for="h in headers" :key="h" :value="h">{{ h }}</option></select></label>
        <label>分组字段<select v-model="group"><option value="">不分组</option><option v-for="h in headers" :key="h" :value="h">{{ h }}</option></select></label>
        <label>原文金额单位<select v-model="valueUnit"><option value="">未声明，按原文区分</option><option>GBP</option><option>USD</option><option>CNY</option><option>EUR</option></select></label>
      </div>
      <div v-for="(filter, index) in plan.filters" :key="index" class="filter">
        <select v-model="filter.field" aria-label="筛选字段"><option v-for="h in headers" :key="h" :value="h">{{ h }}</option></select>
        <select v-model="filter.type" aria-label="字段类型"><option value="TEXT">文本</option><option value="NUMBER">数字</option><option value="DATE">日期</option></select>
        <select v-model="filter.operator" aria-label="筛选运算"><option v-for="(label, value) in operators" :key="value" :value="value">{{ label }}</option></select>
        <input v-model="filter.value" placeholder="条件值（精确匹配）" aria-label="筛选值">
        <select v-if="filter.type === 'DATE'" v-model="filter.dateFormat" aria-label="日期格式"><option>yyyy-MM-dd</option><option>dd/MM/yyyy</option><option>dd-MMM-yy</option></select>
        <button @click="plan.filters.splice(index, 1)">移除</button>
      </div>
      <div class="actions"><button :disabled="!headers.length || plan.filters.length >= 12" @click="addFilter">添加筛选条件</button><button :disabled="!headers.length || calculating" @click="calculate">{{ calculating ? '正在核验…' : '确认条件并统计' }}</button></div>
      <p class="scope">所有条件同时满足才计入。文本区分大小写；空金额不按零处理；小计须明确排除；相同编号存在冲突时停止给出合计。平均值保留至多10位小数。</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <template v-if="result">
        <strong>{{ result.accepted ? '所选已解析表格统计通过' : '无法确认完整结果，请处理异常后重算' }}</strong>
        <p>检查 {{ result.totalRows }} 行；参与 {{ result.selectedRows }}；排除 {{ result.excludedRows }}；重复 {{ result.duplicateRows }}；异常 {{ result.invalidRows }}。</p>
        <div class="scroll"><table v-if="result.accepted"><thead><tr><th>分组</th><th>参与记录数</th><th>结果</th></tr></thead><tbody><tr v-for="(item, i) in result.groups" :key="i"><td>{{ Object.entries(item.keys).map(([k,v]) => `${k}=${v}`).join('；') || '全部匹配记录' }}</td><td>{{ item.count }}</td><td>{{ item.value ?? '无匹配值' }}</td></tr></tbody></table></div>
        <details><summary>范围与提醒</summary><p v-for="warning in result.warnings" :key="warning">{{ warning }}</p></details>
        <div class="actions"><button @click="download">下载完整核验明细（JSON）</button><select v-model="decision"><option value="">全部记录</option><option value="SELECTED">参与</option><option value="EXCLUDED">排除</option><option value="DUPLICATE">重复</option><option value="INVALID">异常</option></select></div>
        <div class="scroll"><table><thead><tr><th>出处</th><th>判定</th><th>字段与理由</th></tr></thead><tbody><tr v-for="item in pageRows" :key="`${item.tableId}:${item.row}`"><td><button :aria-label="`${item.tableId} · 行${item.row}${item.page ? ` · 页${item.page}` : ''}`" @click="showSource(item)">行{{ item.row }}</button><small v-if="item.page">页{{ item.page }}</small></td><td>{{ decisions[item.decision] }}</td><td>{{ Object.entries(item.cells).map(([k,v]) => `${k}=${v}`).join('；') }}<br>{{ item.reason }}</td></tr></tbody></table></div>
        <div class="actions"><button :disabled="page <= 1" @click="page--">上一页</button><span>{{ page }} / {{ Math.max(1, Math.ceil(filteredRows.length / 30)) }} 页</span><button :disabled="page * 30 >= filteredRows.length" @click="page++">下一页</button></div>
        <pre v-if="source" class="source">{{ source }}</pre>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import api from '@/api'
const props = defineProps<{ sessionId: string | null }>()
const visible = defineModel<boolean>({ default: false })
interface Filter { field: string; operator: string; value: string; type: string; dateFormat: string }
interface Table { id: string; title: string; headers: string[]; rowCount: number; issues: string[] }
interface Audit { tableId: string; row: number; page?: number; cells: Record<string,string>; decision: string; reason: string }
interface Result { sourceVersion: string; accepted: boolean; totalRows: number; selectedRows: number; excludedRows: number; duplicateRows: number; invalidRows: number; groups: { keys: Record<string,string>; count: number; value: string|null }[]; warnings: string[]; audit: Audit[] }
const loading=ref(false), calculating=ref(false), error=ref(''), tables=ref<Table[]>([]), result=ref<Result|null>(null), group=ref(''), valueUnit=ref(''), page=ref(1), decision=ref(''), source=ref('')
const plan=ref({ sourceVersion:'', tableIds:[] as string[], operation:'SUM', valueField:'', filters:[] as Filter[], distinctBy:'' })
const headers=computed(()=>tables.value.find(t=>plan.value.tableIds.includes(t.id))?.headers || [])
const filteredRows=computed(()=>result.value?.audit.filter(r=>!decision.value||r.decision===decision.value)||[])
const pageRows=computed(()=>filteredRows.value.slice((page.value-1)*30,page.value*30))
const operations={SUM:'合计',COUNT:'记录数',AVG:'平均值',MIN:'最小值',MAX:'最大值',DISTINCT_COUNT:'字段不同值数量'}
const operators={EQ:'等于',NE:'不等于',GT:'大于',GE:'大于等于',LT:'小于',LE:'小于等于',CONTAINS:'包含'}
const decisions:Record<string,string>={SELECTED:'参与',EXCLUDED:'排除',DUPLICATE:'重复',INVALID:'异常'}
watch(decision,()=>{page.value=1})
watch([plan, group, valueUnit],()=>{result.value=null;source.value=''}, {deep:true})
watch(()=>[visible.value,props.sessionId] as const,async([open,id])=>{
  if(!open||!id)return
  loading.value=true;error.value='';result.value=null;source.value=''
  try{
    const {data}=await api.get(`/document-qa/sessions/${id}/statistics/tables`)
    if(props.sessionId!==id)return
    tables.value=data.data.tables
    plan.value={sourceVersion:data.data.sourceVersion,tableIds:tables.value.length?[tables.value[0].id]:[],operation:'SUM',valueField:tables.value[0]?.headers.find(h=>/amount|金额/i.test(h))||tables.value[0]?.headers[0]||'',filters:[],distinctBy:''}
    group.value='';valueUnit.value=''
  }catch(e:any){error.value=e.response?.data?.message||'读取表格失败'}finally{loading.value=false}
})
function addFilter(){plan.value.filters.push({field:headers.value[0]||'',operator:'EQ',value:'',type:'TEXT',dateFormat:'yyyy-MM-dd'})}
function requestPlan(){return {...plan.value,groupBy:group.value?[group.value]:[],valueUnit:valueUnit.value}}
async function calculate(){
  const id=props.sessionId;if(!id)return
  calculating.value=true;error.value='';result.value=null;source.value=''
  const snapshot=JSON.stringify(requestPlan())
  try{
    const {data}=await api.post(`/document-qa/sessions/${id}/statistics`,JSON.parse(snapshot))
    if(id===props.sessionId&&snapshot===JSON.stringify(requestPlan())){result.value=data.data;page.value=1;decision.value=''}
  }catch(e:any){error.value=e.response?.data?.message||'统计失败'}finally{calculating.value=false}
}
async function showSource(row:Audit){if(!props.sessionId||!result.value)return;try{const {data}=await api.get(`/document-qa/sessions/${props.sessionId}/statistics/rows/${row.tableId}/${row.row}`,{params:{version:result.value.sourceVersion}});source.value=`${data.data.fileName} · ${row.tableId} · 行${row.row} · 字符偏移${data.data.startOffset}–${data.data.endOffset}\n${data.data.headers.join(' | ')}\n${data.data.text}`}catch(e:any){error.value=e.response?.data?.message||'原文读取失败'}}
function download(){if(!result.value)return;const url=URL.createObjectURL(new Blob([JSON.stringify(result.value,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='document-statistics-audit.json';a.style.display='none';document.body.append(a);a.click();setTimeout(()=>{a.remove();URL.revokeObjectURL(url)},60000)}
</script>

<style scoped>
.scope { color:var(--text-secondary); font-size:12px; line-height:1.6; }
.tables { display:grid; gap:8px; margin:10px 0; }
.tables small { display:block; color:var(--text-secondary); overflow-wrap:anywhere; margin-top:4px; }
.controls,.filter,.actions { display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin:12px 0; }
.controls label { display:grid; gap:4px; }
select,input,button { border:1px solid var(--control-border); border-radius:5px; background:var(--bg-input); color:var(--text-primary); padding:7px; max-width:100%; }
button { cursor:pointer; }
button:disabled { opacity:.5; cursor:default; }
.filter input { flex:1; min-width:100px; }
.scroll { overflow:auto; max-height:340px; }
table { width:100%; border-collapse:collapse; font-size:12px; }
th,td { padding:8px; text-align:left; border-bottom:1px solid var(--border-light); vertical-align:top; overflow-wrap:anywhere; }
th { background:var(--bg-subtle); color:var(--text-primary); }
.error { color:var(--text-primary); border-left:3px solid var(--control-border); padding-left:8px; }
.source { white-space:pre-wrap; overflow-wrap:anywhere; background:var(--bg-input); color:var(--text-primary); padding:12px; font-size:12px; }
</style>
