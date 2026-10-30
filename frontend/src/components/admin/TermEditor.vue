<script setup lang="ts">
import { computed, reactive, ref, watch, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getTermWorkspace,
  saveTermDraft,
  type GlossaryTermItem,
  type TermColumn,
} from '../../api/admin/glossary'
import { getHttpErrorMessage } from '../../utils/httpError'
import { useAuthStore } from '../../stores/auth'
import { useIamS1Store } from '../../stores/iamS1'
import { trackGovernanceWrite } from '../../composables/governanceWriteTracker'
import { useAdminScrollLock } from '../../composables/useAdminScrollLock'
import GovernanceWriteNotice from './GovernanceWriteNotice.vue'
import TermFieldPicker from './TermFieldPicker.vue'
const props = defineProps<{ groupId: number; sources: Array<{ id: number; name: string }> }>()
const emit = defineEmits<{ saved: [GlossaryTermItem] }>()
const auth = useAuthStore(),
  iam = useIamS1Store()
const identityKey = computed(() =>
  JSON.stringify([auth.token, iam.snapshot, iam.loaded, iam.errorMessage, props.groupId]),
)
const canManage = computed(() => iam.loaded && !iam.errorMessage && iam.hasGlobal('glossary:manage'))
let disposed = false
const workspaceVerified = ref(false)
const visible = ref(false)
useAdminScrollLock(() => visible.value)
const editorSession = ref(0)
const busy = ref(false)
const id = ref<number>()
const revision = ref<number>()
const error = ref('')
const mappingWarning = ref('')
const columns = ref<TermColumn[]>([])
const form = reactive({ name: '', description: '', synonyms: '', relatedTerms: '' })
async function open(term?: GlossaryTermItem) {
  if (!canManage.value) return
  if (term && !['DRAFT', 'REJECTED'].includes(term.status)) {
    ElMessage.warning('此修订当前不可编辑，请先完成审核或将已通过术语退回草稿。')
    return
  }
  const session = ++editorSession.value,
    key = identityKey.value,
    termId = term?.id
  error.value = ''
  mappingWarning.value = ''
  id.value = termId
  revision.value = term?.revision
  columns.value = []
  busy.value = false
  Object.assign(form, {
    name: term?.displayName || term?.name || '',
    description: term?.description || '',
    synonyms: term?.synonyms || '',
    relatedTerms: term?.relatedTerms || '',
  })
  try {
    const value: unknown = JSON.parse(form.synonyms)
    if (Array.isArray(value)) form.synonyms = value.join('，')
  } catch {
    /* preserve old text */
  }
  visible.value = true
  workspaceVerified.value = !term
  if (!term) return
  busy.value = true
  const current = () =>
    !disposed &&
    session === editorSession.value &&
    key === identityKey.value &&
    visible.value &&
    id.value === termId &&
    canManage.value
  try {
    const workspace = (await getTermWorkspace(term.id)).data
    if (!current()) return
    if (!workspace?.term || workspace.term.id !== term.id || workspace.term.glossaryId !== props.groupId)
      throw new Error('返回的术语或分组不匹配，原草稿不能使用此映射。')
    if (!['DRAFT', 'REJECTED'].includes(workspace.term.status))
      throw new Error('此修订当前不可编辑，请关闭并核对最新审核阶段。')
    // Definition and mapping must share the same fresh revision, never stale list text plus fresh mapping.
    Object.assign(form, {
      name: workspace.term.displayName || workspace.term.name,
      description: workspace.term.description || '',
      synonyms: workspace.term.synonyms || '',
      relatedTerms: workspace.term.relatedTerms || '',
    })
    try {
      const value: unknown = JSON.parse(form.synonyms)
      if (Array.isArray(value)) form.synonyms = value.join('，')
    } catch {
      /* preserve */
    }
    if (!Array.isArray(workspace.columns)) throw new Error('映射读取不完整，不能用空集合代替')
    workspaceVerified.value = true
    mappingWarning.value = workspace.mappingError || ''
    revision.value = workspace.term.revision
    columns.value = workspace.columns
  } catch (cause) {
    if (current()) error.value = getHttpErrorMessage(cause, '术语及映射读取失败，未用空集合替代原映射')
  } finally {
    if (current()) busy.value = false
  }
}
async function save(submit: boolean) {
  if (busy.value || !visible.value || !canManage.value || mappingWarning.value || !workspaceVerified.value)
    return
  if (!form.name.trim()) {
    ElMessage.warning('请填写业务名称')
    return
  }
  if (submit && (!form.description.trim() || !columns.value.length)) {
    ElMessage.warning('提交审核前请填写定义并关联字段，也可先保存草稿')
    return
  }
  if (columns.value.some((c) => !iam.canOnDatasource('glossary:manage', c.datasourceId))) {
    error.value = '当前选择包含无维护能力的关联源，请核对权限；不能合并不同角色的功能与数据源。'
    return
  }
  const key = identityKey.value,
    session = editorSession.value,
    group = props.groupId,
    target = id.value
  const payload = {
    ...form,
    name: form.name.trim(),
    displayName: form.name.trim(),
    revision: revision.value,
    synonyms: JSON.stringify(
      form.synonyms
        .split(/[,，]/)
        .map((v) => v.trim())
        .filter(Boolean),
    ),
    entityIds: columns.value.map((c) => c.entityId),
    submit,
  }
  const signature = JSON.stringify([form, columns.value.map((c) => c.entityId)])
  const current = () =>
    !disposed &&
    session === editorSession.value &&
    key === identityKey.value &&
    visible.value &&
    canManage.value
  busy.value = true
  error.value = ''
  try {
    const result = await trackGovernanceWrite(
      auth.token,
      [target !== undefined ? `glossary-term:${target}` : `glossary-new:${group}:${payload.name}`],
      `${submit ? '保存提交术语' : '保存术语草稿'} · ${payload.displayName}`,
      () => saveTermDraft(group, target, payload),
    )
    if (!current()) return
    if (signature !== JSON.stringify([form, columns.value.map((c) => c.entityId)])) {
      error.value = '原输入已保存，等待期间的新输入仍保留；请核对最新修订再继续。'
      return
    }
    ElMessage.success(
      submit
        ? '定义和字段映射已提交审核'
        : columns.value.length
          ? '术语及关联字段已保存'
          : '草稿已保存，尚未关联字段',
    )
    visible.value = false
    emit('saved', result.data)
  } catch (cause) {
    if (current())
      error.value = getHttpErrorMessage(
        cause,
        cause instanceof Error ? cause.message : '保存未确认完成，原稿保留，请核对修订与来源',
      )
  } finally {
    if (session === editorSession.value) busy.value = false
  }
}
watch(
  identityKey,
  () => {
    editorSession.value++
    visible.value = false
    busy.value = false
    columns.value = []
    error.value = ''
    mappingWarning.value = ''
  },
  { flush: 'sync' },
)
watch(
  visible,
  (open) => {
    if (!open) {
      editorSession.value++
      busy.value = false
    }
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  disposed = true
  editorSession.value++
})
function clearInvalidMapping() {
  if (!busy.value && canManage.value) {
    columns.value = []
    mappingWarning.value = ''
  }
}
defineExpose({ open })
</script>
<template>
  <el-dialog
    v-model="visible"
    class="term-editor-dialog"
    top="3vh"
    :title="id ? '编辑业务术语' : '新增业务术语'"
    width="min(900px, 94vw)"
    :show-close="!busy"
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
  >
    <div v-loading="busy" class="term-editor">
      <GovernanceWriteNotice />
      <p class="term-revision-context">
        {{ id ? `术语 #${id} / 修订 ${revision ?? '未提供'}` : '新术语草稿' }} ·
        定义与映射一起保存；审核不替代来源有效性或RAG构建。
      </p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-alert v-if="mappingWarning" :title="mappingWarning" type="warning" :closable="false" />
      <el-button v-if="mappingWarning" @click="clearInvalidMapping">清空失效关联，重新选择字段</el-button>
      <h3>1. 业务定义</h3>
      <el-form :disabled="busy || !canManage" label-position="top">
        <el-form-item label="业务名称" required>
          <el-input v-model="form.name" maxlength="128" placeholder="例如：客户编号、有效订单" />
        </el-form-item>
        <el-form-item label="定义与业务口径">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="说明这个概念的含义，以及适用的业务条件"
          />
        </el-form-item>
        <el-form-item label="同义词">
          <el-input v-model="form.synonyms" placeholder="用逗号分隔，例如：客户ID，会员编号" />
        </el-form-item>
      </el-form>
      <h3>2. 关联实际字段</h3>
      <TermFieldPicker
        :disabled="busy || !canManage"
        :key="editorSession"
        v-model="columns"
        :sources="sources"
      />
      <h3>3. 核对与保存</h3>
      <p class="editor-summary">
        {{
          columns.length
            ? `业务名称“${form.name || '待填写'}”将与 ${columns.length} 个字段一起保存、审核。`
            : '可先保存定义草稿，关联字段后再提交审核。'
        }}
      </p>
    </div>
    <template #footer>
      <el-button :disabled="busy" @click="visible = false">取消</el-button>
      <el-button
        :loading="busy"
        :disabled="busy || !canManage || !workspaceVerified || !!mappingWarning"
        @click="save(false)"
      >
        保存草稿
      </el-button>
      <el-button
        type="primary"
        :loading="busy"
        :disabled="busy || !canManage || !workspaceVerified || !!mappingWarning"
        @click="save(true)"
      >
        保存并提交审核
      </el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.term-revision-context {
  margin: 0;
  padding: 12px;
  background: var(--do-primary-soft);
  color: var(--do-muted);
  font-size: 13px;
  line-height: 1.7;
  border-radius: 8px;
}
.term-editor {
  display: grid;
  gap: 12px;
}
h3 {
  margin: 4px 0;
  font-size: 15px;
  color: var(--do-ink);
}
.editor-summary {
  margin: 0;
  padding: 12px;
  background: var(--do-bg);
  color: var(--do-muted);
  border-radius: var(--do-radius-md);
}
</style>

<style>
.term-editor-dialog {
  display: flex;
  flex-direction: column;
  max-height: 94vh;
  margin-bottom: 0;
}
.term-editor-dialog .el-dialog__body {
  overflow-y: auto;
  min-height: 0;
}
.term-editor-dialog .el-dialog__header,
.term-editor-dialog .el-dialog__footer {
  flex-shrink: 0;
}
.term-editor-dialog .el-dialog__footer {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
