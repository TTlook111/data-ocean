<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getTermWorkspace, saveTermDraft, type GlossaryTermItem, type TermColumn } from '../../api/admin/glossary'
import { getHttpErrorMessage } from '../../utils/httpError'
import TermFieldPicker from './TermFieldPicker.vue'
const props = defineProps<{ groupId: number; sources: Array<{ id: number; name: string }> }>()
const emit = defineEmits<{ saved: [GlossaryTermItem] }>()
const visible = ref(false)
const editorSession = ref(0)
const busy = ref(false)
const id = ref<number>()
const revision = ref<number>()
const error = ref('')
const mappingWarning = ref('')
const columns = ref<TermColumn[]>([])
const form = reactive({ name: '', description: '', synonyms: '', relatedTerms: '' })
async function open(term?: GlossaryTermItem) {
  ++editorSession.value
  error.value = ''; mappingWarning.value = ''; id.value = term?.id; revision.value = term?.revision; columns.value = []
  Object.assign(form, { name: term?.displayName || term?.name || '', description: term?.description || '',
    synonyms: term?.synonyms || '', relatedTerms: term?.relatedTerms || '' })
  try { const value: unknown = JSON.parse(form.synonyms); if (Array.isArray(value)) form.synonyms = value.join('，') } catch { /* 已有文本同义词原样保留 */ }
  visible.value = true
  if (term) {
    busy.value = true
    try {
      const workspace = (await getTermWorkspace(term.id)).data
      mappingWarning.value = workspace.mappingError || ''
      revision.value = workspace.term.revision; columns.value = workspace.columns
    } catch (cause) { error.value = getHttpErrorMessage(cause, '术语及映射读取失败') }
    finally { busy.value = false }
  }
}
async function save(submit: boolean) {
  if (!form.name.trim()) { ElMessage.warning('请填写业务名称'); return }
  if (submit && (!form.description.trim() || !columns.value.length)) { ElMessage.warning('提交审核前请填写定义并关联字段，也可先保存草稿'); return }
  busy.value = true; error.value = ''
  try {
    const result = await saveTermDraft(props.groupId, id.value, { ...form, name: form.name.trim(),
      displayName: form.name.trim(), revision: revision.value,
      synonyms: JSON.stringify(form.synonyms.split(/[,，]/).map(v => v.trim()).filter(Boolean)),
      entityIds: columns.value.map(v => v.entityId), submit })
    ElMessage.success(submit ? '定义和字段映射已提交审核' : columns.value.length ? '术语及关联字段已保存' : '草稿已保存，尚未关联字段')
    visible.value = false; emit('saved', result.data)
  } catch (cause) { error.value = getHttpErrorMessage(cause, '保存失败，未写入本次修改') }
  finally { busy.value = false }
}
defineExpose({ open })
</script>
<template>
  <el-dialog v-model="visible" class="term-editor-dialog" top="3vh" :title="id ? '编辑业务术语' : '新增业务术语'" width="min(900px, 94vw)" :show-close="!busy" :close-on-click-modal="!busy" :close-on-press-escape="!busy">
    <div v-loading="busy" class="term-editor">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-alert v-if="mappingWarning" :title="mappingWarning" type="warning" :closable="false" />
      <el-button v-if="mappingWarning" @click="columns = []; mappingWarning = ''">清空失效关联，重新选择字段</el-button>
      <h3>1. 业务定义</h3>
      <el-form label-position="top">
        <el-form-item label="业务名称" required><el-input v-model="form.name" maxlength="128" placeholder="例如：客户编号、有效订单" /></el-form-item>
        <el-form-item label="定义与业务口径"><el-input v-model="form.description" type="textarea" :rows="3" placeholder="说明这个概念的含义，以及适用的业务条件" /></el-form-item>
        <el-form-item label="同义词"><el-input v-model="form.synonyms" placeholder="用逗号分隔，例如：客户ID，会员编号" /></el-form-item>
      </el-form>
      <h3>2. 关联实际字段</h3>
      <TermFieldPicker :key="editorSession" v-model="columns" :sources="sources" />
      <h3>3. 核对与保存</h3>
      <p class="editor-summary">{{ columns.length ? `业务名称“${form.name || '待填写'}”将与 ${columns.length} 个字段一起保存、审核。` : '可先保存定义草稿，关联字段后再提交审核。' }}</p>
    </div>
    <template #footer>
      <el-button :disabled="busy" @click="visible = false">取消</el-button>
      <el-button :loading="busy" :disabled="busy || (!!id && !!error) || !!mappingWarning" @click="save(false)">保存草稿</el-button>
      <el-button type="primary" :loading="busy" :disabled="busy || (!!id && !!error) || !!mappingWarning" @click="save(true)">保存并提交审核</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.term-editor { display: grid; gap: 12px; }
h3 { margin: 4px 0; font-size: 15px; color: var(--do-ink); }
.editor-summary { margin: 0; padding: 12px; background: var(--do-bg); color: var(--do-muted); border-radius: var(--do-radius-md); }
</style>

<style>
.term-editor-dialog { display: flex; flex-direction: column; max-height: 94vh; margin-bottom: 0; }
.term-editor-dialog .el-dialog__body { overflow-y: auto; min-height: 0; }
.term-editor-dialog .el-dialog__header, .term-editor-dialog .el-dialog__footer { flex-shrink: 0; }
.term-editor-dialog .el-dialog__footer { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
</style>
