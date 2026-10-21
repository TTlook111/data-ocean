<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listVersions, generateSemanticDraft, confirmSemanticDraft, type KnowledgeDocItem, type SemanticCandidate } from '../../api/admin/knowledge'
import { getHttpErrorMessage } from '../../utils/httpError'
import KnowledgeSourcePreview from './KnowledgeSourcePreview.vue'
const props = defineProps<{ doc: KnowledgeDocItem }>()
const emit = defineEmits<{ saved: [] }>()
const visible = ref(false), loading = ref(false), error = ref(''), ready = ref(false), confirmed = ref(false)
const snapshotId = ref<number>()
const candidates = ref<SemanticCandidate[]>([])
const selected = ref<number[]>([])
const baseVersion = ref(0)
const selectedTables = ref<string[]>([])
const availableTables = ref<string[]>([])
async function open() {
  visible.value = true; candidates.value = []; selected.value = []; error.value = ''; confirmed.value = false
  snapshotId.value = undefined; selectedTables.value = []; availableTables.value = []; baseVersion.value = props.doc.version; loading.value = true
  try {
    const versions = (await listVersions(props.doc.id)).data
    snapshotId.value = versions.find(v => v.versionNo === props.doc.currentVersion)?.metadataSnapshotId
    if (!snapshotId.value) error.value = '请先按已发布快照生成目录，再补写业务释义。'
  } catch (cause) { error.value = getHttpErrorMessage(cause, '文档来源读取失败') }
  finally { loading.value = false }
}
async function generate() {
  loading.value = true; error.value = ''
  try { candidates.value = (await generateSemanticDraft(props.doc.id, baseVersion.value, selectedTables.value)).data; selected.value = []; confirmed.value = false }
  catch (cause) { error.value = getHttpErrorMessage(cause, '业务释义补写失败，原有目录与人工内容已保留') }
  finally { loading.value = false }
}
async function adopt() {
  loading.value = true; error.value = ''
  try {
    await confirmSemanticDraft(props.doc.id, baseVersion.value, candidates.value.filter(c => selected.value.includes(c.id)).map(c => ({ id: c.id, text: c.text })), confirmed.value)
    visible.value = false; ElMessage.success('已创建新的知识草稿版本，仍需提交文档审核'); emit('saved')
  } catch (cause) { error.value = getHttpErrorMessage(cause, '采纳失败，原有内容已保留') }
  finally { loading.value = false }
}
defineExpose({ open })
</script>
<template>
  <el-dialog v-model="visible" class="knowledge-semantic-dialog" top="3vh" title="AI 辅助补写业务释义" width="min(850px, 94vw)" :show-close="!loading" :close-on-click-modal="!loading" :close-on-press-escape="!loading">
    <el-alert title="AI 生成的是待确认候选。核对业务定义、字段含义与条件后再采纳；原有目录和人工内容不会被覆盖。" type="info" :closable="false" />
    <KnowledgeSourcePreview :datasource-id="doc.datasourceId" :snapshot-id="snapshotId" :table-names="selectedTables" @ready="ready = $event" @loaded="availableTables = $event.tableNames || []" />
    <label>补写范围（留空表示全部合规表）<el-select v-model="selectedTables" multiple clearable placeholder="可按业务表分批补写"><el-option v-for="table in availableTables" :key="table" :value="table" :label="table" /></el-select></label>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-for="candidate in candidates" :key="candidate.id" class="semantic-candidate">
      <el-checkbox :model-value="selected.includes(candidate.id)" @change="(value: string | number | boolean) => selected = value ? [...selected, candidate.id] : selected.filter(id => id !== candidate.id)">采纳这条释义</el-checkbox>
      <el-input v-model="candidate.text" type="textarea" :rows="4" maxlength="4000" show-word-limit :aria-label="`编辑释义候选${candidate.id}`" />
      <p>字段依据：{{ candidate.dependencies.filter(d => d.startsWith('column:')).map(d => d.slice(7)).join('、') }}</p>
    </div>
    <el-checkbox v-if="candidates.length" v-model="confirmed">我已核对选中释义的业务含义与字段，确认采纳为待审核草稿</el-checkbox>
    <template #footer>
      <el-button :disabled="loading" @click="visible = false">关闭</el-button>
      <el-button :disabled="!ready || loading" :loading="loading" @click="generate">{{ candidates.length ? '重新生成候选' : '生成释义候选' }}</el-button>
      <el-button v-if="candidates.length" type="primary" :disabled="loading || !confirmed || !selected.length" @click="adopt">采纳到新草稿版本</el-button>
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
