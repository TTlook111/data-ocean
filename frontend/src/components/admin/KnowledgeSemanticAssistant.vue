<script setup lang="ts">
import { computed,onBeforeUnmount,ref,watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listVersions,generateSemanticDraft,confirmSemanticDraft,type KnowledgeDocItem,type SemanticCandidate } from '../../api/admin/knowledge'
import { getHttpErrorMessage } from '../../utils/httpError'
import { useAuthStore } from '../../stores/auth'
import { useIamS1Store } from '../../stores/iamS1'
import { useAdminScrollLock } from '../../composables/useAdminScrollLock'
import { pendingKnowledgeWrites,trackKnowledgeWrite } from '../../composables/knowledgeWriteTracker'
import KnowledgeSourcePreview from './KnowledgeSourcePreview.vue'
const props=defineProps<{doc:KnowledgeDocItem;disabled?:boolean}>()
const emit=defineEmits<{saved:[]}>()
const auth=useAuthStore(),iam=useIamS1Store()
const visible=ref(false),loading=ref(false),error=ref(''),ready=ref(false),confirmed=ref(false),snapshotId=ref<number>()
const candidates=ref<SemanticCandidate[]>([]),selected=ref<number[]>([]),baseVersion=ref(0),selectedTables=ref<string[]>([]),availableTables=ref<string[]>([])
let sequence=0,disposed=false
const allowed=computed(()=>!props.disabled && iam.loaded && (iam.systemAdmin || iam.datasourcesWithFunction('knowledge:manage').includes(props.doc.datasourceId)) && !pendingKnowledgeWrites.value.some(x=>x.identity===auth.token && x.docId===props.doc.id))
function invalidate(){sequence++;visible.value=false;loading.value=false;candidates.value=[];selected.value=[];confirmed.value=false;ready.value=false;error.value='';snapshotId.value=undefined}
function capture(){const seq=++sequence,id=props.doc.id,version=props.doc.version,source=props.doc.datasourceId,identity=auth.token;return {id,version,source,identity,current:()=>!disposed && visible.value && seq===sequence && props.doc.id===id && props.doc.version===version && auth.token===identity}}
async function open(){
  if(loading.value || !allowed.value)return
  visible.value=true;candidates.value=[];selected.value=[];error.value='';confirmed.value=false;snapshotId.value=undefined;selectedTables.value=[];availableTables.value=[];baseVersion.value=props.doc.version
  const target=capture(),contentVersion=props.doc.currentVersion;loading.value=true
  try{const versions=(await listVersions(target.id)).data;if(!target.current())return;snapshotId.value=versions.find(v=>v.versionNo===contentVersion)?.metadataSnapshotId;if(!snapshotId.value)error.value='请先按已发布快照生成目录，再补写业务释义。'}
  catch(cause){if(target.current())error.value=getHttpErrorMessage(cause,'文档来源读取失败')}
  finally{if(target.current())loading.value=false}
}
async function generate(){
  if(!allowed.value || !ready.value || !snapshotId.value || loading.value)return
  const target=capture(),tables=[...selectedTables.value];loading.value=true;error.value=''
  try{const result=await trackKnowledgeWrite({identity:target.identity,source:target.source,docId:target.id,version:target.version,kind:'semantic-generate',started:Date.now()},()=>generateSemanticDraft(target.id,target.version,tables));if(target.current()){candidates.value=result.data;selected.value=[];confirmed.value=false}}
  catch(cause){if(target.current())error.value=getHttpErrorMessage(cause,'业务释义补写失败，原稿已保留')}
  finally{if(target.current())loading.value=false}
}
async function adopt(){
  if(!allowed.value || loading.value || !confirmed.value || !selected.value.length)return
  const target=capture(),selections=candidates.value.filter(c=>selected.value.includes(c.id)).map(c=>({id:c.id,text:c.text}));loading.value=true;error.value=''
  try{await trackKnowledgeWrite({identity:target.identity,source:target.source,docId:target.id,version:target.version,kind:'semantic-adopt',started:Date.now()},()=>confirmSemanticDraft(target.id,target.version,selections,true));if(target.current()){visible.value=false;ElMessage.success('已创建新的待审核草稿版本');emit('saved')}}
  catch(cause){if(target.current())error.value=getHttpErrorMessage(cause,'采纳失败或结果待确认，原候选保留；不自动重发')}
  finally{if(target.current())loading.value=false}
}
watch(()=>[props.doc.id,props.doc.version,props.doc.datasourceId,props.disabled,auth.token,iam.snapshot,iam.loaded],invalidate,{flush:'sync'})
watch(selectedTables,()=>{sequence++;candidates.value=[];selected.value=[];confirmed.value=false;loading.value=false},{deep:true,flush:'sync'})
watch(visible,value=>{if(!value){sequence++;loading.value=false;candidates.value=[];selected.value=[];confirmed.value=false}},{flush:'sync'})
onBeforeUnmount(()=>{disposed=true;sequence++})
useAdminScrollLock(()=>visible.value)
defineExpose({open})
</script>
<template>
  <el-dialog v-model="visible" class="knowledge-semantic-dialog" top="3vh" title="AI 辅助补写业务释义" width="min(850px, 94vw)" :show-close="!loading" :close-on-click-modal="!loading" :close-on-press-escape="!loading">
    <el-alert title="AI 生成的是待确认候选。核对业务定义、字段含义与条件后再采纳；原有目录和人工内容不会被覆盖。" type="info" :closable="false" />
    <KnowledgeSourcePreview :datasource-id="doc.datasourceId" :snapshot-id="snapshotId" :table-names="selectedTables" @ready="ready = $event" @loaded="availableTables = $event.tableNames || []" />
    <label>补写范围（留空表示全部合规表）<el-select v-model="selectedTables" :disabled="loading" multiple clearable placeholder="可按业务表分批补写"><el-option v-for="table in availableTables" :key="table" :value="table" :label="table" /></el-select></label>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-for="candidate in candidates" :key="candidate.id" class="semantic-candidate">
      <el-checkbox :disabled="loading" :model-value="selected.includes(candidate.id)" @change="(value: string | number | boolean) => selected = value ? [...selected, candidate.id] : selected.filter(id => id !== candidate.id)">采纳这条释义</el-checkbox>
      <el-input v-model="candidate.text" :disabled="loading" type="textarea" :rows="4" maxlength="4000" show-word-limit :aria-label="`编辑释义候选${candidate.id}`" />
      <p>字段依据：{{ candidate.dependencies.filter(d => d.startsWith('column:')).map(d => d.slice(7)).join('、') }}</p>
    </div>
    <el-checkbox v-if="candidates.length" v-model="confirmed" :disabled="loading">我已核对选中释义的业务含义与字段，确认采纳为待审核草稿</el-checkbox>
    <template #footer>
      <el-button :disabled="loading" @click="visible = false">关闭</el-button>
      <el-button :disabled="!allowed || !ready || loading" :loading="loading" @click="generate">{{ candidates.length ? '重新生成候选' : '生成释义候选' }}</el-button>
      <el-button v-if="candidates.length" type="primary" :disabled="!allowed || loading || !confirmed || !selected.length" @click="adopt">采纳到新草稿版本</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.semantic-candidate { display: grid; gap: 8px; padding: 14px 0; border-bottom: 1px solid var(--do-line); }
.semantic-candidate p { margin: 0; color: var(--do-muted); font-size: 12px; overflow-wrap: anywhere; }
</style>

<style>
.knowledge-semantic-dialog { display: flex; flex-direction: column; max-height: 94vh; margin-bottom: 0; }
.knowledge-semantic-dialog .el-dialog__body { overflow-y: auto; min-height: 0; }
.knowledge-semantic-dialog .el-dialog__header, .knowledge-semantic-dialog .el-dialog__footer { flex-shrink: 0; }
.knowledge-semantic-dialog .el-dialog__footer { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
.knowledge-semantic-dialog .el-checkbox, .knowledge-semantic-dialog .el-checkbox__label { white-space: normal; height: auto; line-height: 1.5; }
</style>
