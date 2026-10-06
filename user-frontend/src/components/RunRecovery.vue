<template>
  <section v-if="visibleRuns.length" class="run-recovery" aria-label="任务恢复">
    <details><summary>任务记录 <span v-if="unfinished">· {{ unfinished }} 项未完成</span></summary>
      <p class="run-hint">刷新可查看进度。恢复聊天会重新生成未完成回答并计入用量，已保存的相同工具调用会复用。记录保留 7 天。</p>
      <article v-for="run in visibleRuns" :key="run.id">
        <header><strong>{{ run.kind !== 'TOOL' ? '回答任务' : fileToolLabels[run.question] || '文件任务' }}</strong><span>{{ statusLabels[run.status] || run.status }}</span></header>
        <p class="run-question">{{ run.kind !== 'TOOL' ? run.question : '' }}</p>
        <ol v-if="run.steps.length"><li v-for="(step, index) in run.steps" :key="index">{{ fileToolLabels[step.name] || step.name }} · {{ step.status === 'RUNNING' && run.status === 'RUNNING' ? '执行中' : stepLabels[step.status] || step.status }}</li></ol>
        <p v-if="run.note" class="run-hint">{{ run.note }}</p>
        <p v-if="run.usageIncomplete" class="run-hint">有 {{ run.modelRequests - run.reportedModelRequests }} 次模型请求未返回完整用量，已知 {{ run.promptTokens + run.completionTokens }} token。供应商可能已产生消耗。</p>
        <div class="run-actions"><button v-if="resumable(run)" :disabled="busy || streaming || run.attempts >= 5" @click="resume(run)">{{ acting === run.id ? '正在恢复…' : '恢复任务' }}</button><button @click="details(run)">查看检查点</button></div>
        <div v-if="detail?.id === run.id" class="run-detail"><p v-if="detail.partial">{{ detail.status === 'COMPLETED' ? '已完成回答：' : '部分回答（未完成，不作为最终结论）：' }}</p><pre v-if="detail.partial">{{ detail.partial }}</pre><ToolArtifacts :assets="detail.artifacts || []" /></div>
      </article>
    </details>
  </section>
</template>
<script setup lang="ts">
import {ref,computed,watch,onUnmounted} from 'vue'
import {ElMessage} from 'element-plus'
import {listRuns,getRun,resumeToolRun,type RunRecord} from '@/api/runs'
import {fileToolLabels} from '@/api/workspaceTools'
import ToolArtifacts from './ToolArtifacts.vue'
const props=defineProps<{sessionId:string|null;streaming:boolean}>()
const emit=defineEmits<{resume:[run:RunRecord];completed:[];busy:[value:boolean]}>()
const runs=ref<RunRecord[]>([]),detail=ref<RunRecord|null>(null),acting=ref(''),busy=ref(false)
const statusLabels:Record<string,string>={RUNNING:'执行中',INTERRUPTED:'已中断',COMPLETED:'已完成',FAILED:'未完成',CANCELLED:'已停止',UNCERTAIN:'需核对成果'}
const stepLabels:Record<string,string>={RUNNING:'中断时未确认',SUCCEEDED:'已完成',FAILED:'未完成'}
const resumable=(r:RunRecord)=>['INTERRUPTED','FAILED','CANCELLED'].includes(r.status)
const visibleRuns=computed(()=>[...runs.value.filter(r=>r.status!=='COMPLETED'),...runs.value.filter(r=>r.status==='COMPLETED').slice(0,3)].slice(0,20))
const unfinished=computed(()=>runs.value.filter(r=>r.status!=='COMPLETED').length)
let timer:ReturnType<typeof setTimeout>|undefined,disposed=false,loading=false,generation=0
async function load(){const id=props.sessionId;if(!id||id==='new'||loading)return;loading=true;const gen=generation;try{const{data}=await listRuns(id);if(gen!==generation||id!==props.sessionId||disposed)return;const fresh:RunRecord[]=data.data;const completed=fresh.some(n=>n.status==='COMPLETED'&&runs.value.some(o=>o.id===n.id&&o.status!=='COMPLETED'));runs.value=fresh;if(completed)emit('completed')}catch{/* A failed status lookup must not break chat. */}finally{loading=false;if(!disposed){clearTimeout(timer);timer=setTimeout(load,props.streaming||runs.value.some(r=>r.status==='RUNNING')?2000:10000)}}}
watch(()=>props.sessionId,()=>{generation++;runs.value=[];detail.value=null;clearTimeout(timer);void load()},{immediate:true})
watch(()=>props.streaming,()=>{clearTimeout(timer);timer=setTimeout(load,400)})
onUnmounted(()=>{disposed=true;clearTimeout(timer)})
async function details(run:RunRecord){const session=props.sessionId;if(!session)return;try{const{data}=await getRun(session,run.id);if(session===props.sessionId)detail.value=data.data}catch{ElMessage.error('检查点读取失败，请刷新重试')}}
async function resume(run:RunRecord){if(props.streaming||busy.value||!props.sessionId)return;if(run.kind!=='TOOL'){emit('resume',run);return}const session=props.sessionId;busy.value=true;acting.value=run.id;emit('busy',true);try{await resumeToolRun(session,run.id);await load()}catch(e:any){ElMessage.error(e.response?.data?.message||'恢复失败，请刷新查看状态')}finally{busy.value=false;acting.value='';emit('busy',false)}}
</script>
<style scoped>
.run-recovery {margin:8px 12px;border:1px solid var(--control-border);border-radius:10px;padding:8px 12px;background:var(--bg-subtle);color:var(--text-primary);font-size:12px;line-height:1.6;max-height:260px;overflow:auto;overflow-wrap:anywhere;}.run-recovery summary{cursor:pointer;font-weight:600}.run-recovery article{padding:9px 0;border-top:1px solid var(--border)}.run-recovery header{display:flex;justify-content:space-between;gap:8px}.run-hint{color:var(--text-secondary);margin:5px 0}.run-question{margin:4px 0}.run-recovery ol{padding-left:20px;margin:4px 0}.run-actions{display:flex;gap:7px}.run-actions button{border:1px solid var(--control-border);border-radius:6px;background:var(--bg-input);color:var(--text-primary);padding:4px 8px;cursor:pointer}.run-actions button:disabled{opacity:.5;cursor:default}.run-detail pre{white-space:pre-wrap;font:inherit;max-height:170px;overflow:auto}.run-actions button:focus-visible{outline:2px solid var(--citation);outline-offset:2px}
</style>
