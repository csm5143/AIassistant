<template>
  <el-dialog v-model="visible" title="本聊天记忆" width="min(760px, 95vw)" append-to-body :close-on-click-modal="false" @closed="source = ''; error = ''">
    <p class="memory-help">只用于当前聊天。明确的事实与任务状态自动保留，其他内容可手动添加。多个项目请写明名称，例如“云帆项目的预算是9100元”。点击来源核对，修改后以新值为准。</p>
    <div v-if="loading">正在读取记忆…</div>
    <template v-else-if="snapshot">
      <div class="memory-actions"><label><input type="checkbox" :checked="snapshot.enabled" :disabled="busy || disabled" @change="toggle">启用本聊天记忆</label><button :disabled="busy" @click="load">刷新</button><button :disabled="busy || disabled" @click="clearAll">清空记忆</button></div>
      <p class="memory-help" v-if="!snapshot.enabled">已暂停历史记忆的整理和检索。最近的正常聊天上下文仍会用于连续对话。</p>
      <p class="memory-help" v-if="snapshot.historyTruncated">记忆整理达到处理上限，当前记录不保证覆盖全部历史；请整理条目或另建聊天。</p>
      <details v-if="snapshot.scopeWarnings?.length" class="task-summary scope-warning"><summary>有 {{ snapshot.scopeWarnings.length }} 条更正需要确认项目</summary><p class="memory-help">以下原话未写入事实，以免覆盖其他项目。请在聊天中补充项目名称，或修改对应的记忆条目；原话保留供核对。</p><p v-for="warning in snapshot.scopeWarnings" :key="warning.messageId">{{ warning.text }}<small class="memory-help">（来源消息 {{ warning.messageId }}）</small></p></details>
      <section v-if="taskItems.length" class="task-summary"><h3>任务摘要</h3><p v-for="item in taskItems" :key="item.id"><strong>{{ item.label }}：</strong>{{ item.value }}</p></section>
      <div class="memory-actions"><label><input type="checkbox" v-model="showHistory">查看被替代的旧记录</label><span class="memory-help">当前 {{ activeItems.length }} / {{ snapshot.activeLimit }} 条</span></div>
      <div class="memory-items">
        <article v-for="item in displayedItems" :key="item.id" :class="{ superseded: item.status !== 'ACTIVE' }">
          <div class="item-heading"><strong>{{ item.label }}</strong><small>{{ categoryLabels[item.category] }} · {{ item.status === 'ACTIVE' ? (item.pinned ? '常驻上下文' : '按问题检索') : '已被替代' }}</small></div>
          <p>{{ item.value }}</p>
          <div class="memory-actions"><button :disabled="busy" @click="viewSource(item)">{{ item.origin === 'MANUAL' ? '手动编辑' : '查看来源消息' }}</button><button v-if="item.status === 'ACTIVE'" :disabled="busy || disabled" @click="edit(item)">修改</button><button :disabled="busy || disabled" @click="forget(item)">删除</button></div>
        </article>
        <p v-if="!displayedItems.length" class="memory-help">暂无记忆。可以添加“预算”“目标”“下一步”等条目。</p>
      </div>
      <details :open="editing !== null" class="memory-editor"><summary>{{ editing ? '修改记忆' : '添加记忆' }}</summary><div class="memory-form"><label>标签<input v-model="form.label" aria-label="记忆标签" maxlength="32" :disabled="!!editing" placeholder="例如：目标、预算、回答偏好"></label><label>类型<select v-model="form.category" aria-label="记忆类型"><option v-for="(label, key) in categoryLabels" :key="key" :value="key">{{ label }}</option></select></label><label class="wide">内容<textarea v-model="form.value" aria-label="记忆内容" maxlength="500" rows="3" placeholder="只记录已确认的信息"></textarea></label><label><input v-model="form.pinned" type="checkbox">常驻上下文</label></div><div class="memory-actions"><button :disabled="busy || disabled || !form.label.trim() || !form.value.trim()" @click="save">保存记忆</button><button v-if="editing" @click="reset">取消修改</button></div></details>
      <p class="memory-help">删除会移除该条目的所有版本，并屏蔽来源及同项目或未注明项目的旧值消息；明确属于其他项目的同值记录会保留。聊天记录本身保留，需要重新记住时手动添加。清空后仅从新消息开始整理。</p>
      <pre v-if="source" class="memory-source">{{ source }}</pre>
    </template>
    <p v-if="error" role="alert" class="memory-error">{{ error }}</p>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import api from '@/api'
type Item = { id:string; label:string; value:string; category:string; pinned:boolean; status:string; origin:string; sourceMessageId:string | null }
type Snapshot = { enabled:boolean; version:number; items:Item[]; indexedUserMessages:number; activeLimit:number; historyTruncated:boolean; scopeWarnings?:{messageId:string;text:string}[] }
const props=defineProps<{modelValue:boolean;sessionId:string|null;disabled:boolean}>()
const emit=defineEmits<{ 'update:modelValue':[value:boolean] }>()
const visible=computed({get:()=>props.modelValue,set:v=>emit('update:modelValue',v)})
const snapshot=ref<Snapshot|null>(null),loading=ref(false),busy=ref(false),error=ref(''),source=ref(''),showHistory=ref(false),editing=ref<string|null>(null)
const categoryLabels:Record<string,string>={FACT:'事实',TASK:'任务状态',PREFERENCE:'偏好',CONSTRAINT:'约束'}
const form=reactive({label:'',value:'',category:'FACT',pinned:false})
const activeItems=computed(()=>snapshot.value?.items.filter(i=>i.status==='ACTIVE')||[])
const taskItems=computed(()=>activeItems.value.filter(i=>i.category==='TASK'))
const displayedItems=computed(()=>showHistory.value?snapshot.value?.items||[]:activeItems.value)
let generation=0
watch(()=>[props.modelValue,props.sessionId],()=>{generation++;snapshot.value=null;source.value='';busy.value=false;loading.value=false;error.value='';reset();if(props.modelValue&&props.sessionId)void load()},{immediate:true})
function reset(){editing.value=null;Object.assign(form,{label:'',value:'',category:'FACT',pinned:false})}
function edit(item:Item){editing.value=item.id;Object.assign(form,{label:item.label,value:item.value,category:item.category,pinned:item.pinned})}
function url(){return `/chat/sessions/${props.sessionId}/memory`}
async function load(){if(!props.sessionId)return;const g=generation;loading.value=true;error.value='';try{const {data}=await api.get(url());if(g===generation)snapshot.value=data.data}catch(e:any){if(g===generation)error.value=e.response?.data?.message||'记忆读取失败'}finally{if(g===generation)loading.value=false}}
async function mutate(method:'post'|'patch'|'delete',path:string,body?:any){const g=generation;busy.value=true;error.value='';try{const {data}=await api.request({method,url:path,data:body,params:method==='delete'?{version:snapshot.value?.version}:undefined});if(g===generation){snapshot.value=data.data;source.value='';reset()}}catch(e:any){if(g===generation){error.value=e.response?.data?.message||'记忆保存失败';if(e.response?.status===409)await load()}}finally{if(g===generation)busy.value=false}}
async function save(){await mutate(editing.value?'patch':'post',url()+(editing.value?'/'+editing.value:''),{...form,version:snapshot.value?.version})}
async function toggle(event:Event){await mutate('patch',url(),{enabled:(event.target as HTMLInputElement).checked,version:snapshot.value?.version})}
async function forget(item:Item){try{await ElMessageBox.confirm(`删除这条“${item.label}”记忆的全部版本？来源及同项目或未注明项目的旧值消息将从后续上下文中屏蔽。`,'删除记忆',{confirmButtonText:'删除',cancelButtonText:'取消'});await mutate('delete',url()+'/'+item.id)}catch{}}
async function clearAll(){try{await ElMessageBox.confirm('清空全部记忆，并让此前聊天不再作为后续记忆来源？聊天记录仍保留。','清空记忆',{confirmButtonText:'清空',cancelButtonText:'取消'});await mutate('delete',url())}catch{}}
async function viewSource(item:Item){const g=generation;try{const {data}=await api.get(url()+`/${item.id}/source`);if(g===generation)source.value=`${data.data.messageId ? '消息 '+data.data.messageId+'\n' : ''}${data.data.text}`}catch(e:any){if(g===generation)error.value=e.response?.data?.message||'来源读取失败'}}
</script>

<style scoped>
.memory-help {color:var(--text-secondary);font-size:12px;line-height:1.7;overflow-wrap:anywhere;}
.memory-actions {display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:10px 0;}
.memory-actions label {display:flex;gap:6px;align-items:center;}
.task-summary {padding:12px;border:1px solid var(--control-border);border-radius:9px;background:var(--bg-input);}
.task-summary h3 {font-size:14px;margin:0 0 8px;}.task-summary p {margin:5px 0;overflow-wrap:anywhere;}
.memory-items {max-height:38vh;overflow:auto;}.memory-items article {padding:12px 0;border-bottom:1px solid var(--control-border);}.memory-items p {margin:8px 0;white-space:pre-wrap;overflow-wrap:anywhere;}.superseded {opacity:.65;}
.item-heading {display:flex;flex-wrap:wrap;gap:8px;align-items:center;}.item-heading small {color:var(--text-secondary);}
button,input,textarea,select {background:var(--bg-input);color:var(--text-primary);border:1px solid var(--control-border);border-radius:6px;padding:7px;box-sizing:border-box;max-width:100%;}button {cursor:pointer;}button:disabled {opacity:.5;cursor:default;}input[type=checkbox] {accent-color:#bbb;}
.memory-form {display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:10px;margin-top:12px;}.memory-form label {display:grid;gap:5px;}.memory-form .wide {grid-column:1/-1;}.memory-form input:not([type=checkbox]),textarea,select {width:100%;}.memory-editor {margin-top:14px;}.memory-editor summary {cursor:pointer;}
.memory-source {background:var(--bg-input);border-radius:7px;padding:12px;white-space:pre-wrap;overflow-wrap:anywhere;max-height:30vh;overflow:auto;}.memory-error {color:#e6a0a0;}
@media(max-width:420px){.memory-form {grid-template-columns:minmax(0,1fr);}}
</style>
