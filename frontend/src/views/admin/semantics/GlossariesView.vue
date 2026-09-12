<script setup lang="ts">
/**
 * 业务术语工作区
 *
 * 按「术语表 → 术语 → 关联字段 → 审核状态」组织，替代旧的单表格 GlossaryList.vue。
 *
 * 后端事实（GlossaryController / GlossaryTermServiceImpl）：
 * - 术语表只有 DRAFT / PUBLISHED 两个常量，且没有独立的状态流转接口，因此状态只在创建/编辑时设置。
 * - 术语状态流转为 DRAFT|REJECTED -> PENDING_REVIEW -> APPROVED|REJECTED，
 *   另有 APPROVED -> DRAFT 的退回路径（后端 2026-09-12 补齐）。
 *   已通过术语不能直接编辑，需先退回草稿；退回会清空审核人与审核时间。
 * - 术语与物理列的关联通过 GLOSSARY_OF 关系维护，接口为 link-column / unlink-column / linked-columns。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Link2, Pencil, Plus, RefreshCw, RotateCcw, Send, Trash2, X } from 'lucide-vue-next'
import {
  createGlossary,
  createTerm,
  deleteGlossary,
  deleteTerm,
  getLinkedColumns,
  linkTermToColumn,
  listGlossaries,
  listTerms,
  reviewTerm,
  revertTermToDraft,
  submitTermForReview,
  unlinkTermFromColumn,
  updateGlossary,
  updateTerm,
  type GlossaryItem,
  type GlossaryTermItem,
  type LinkedColumnItem,
} from '../../../api/admin/glossary'
import { getEntitiesByDatasource } from '../../../api/admin/catalog'
import { listSimpleDatasources, type DatasourceSimpleItem } from '../../../api/admin/datasource'
import { glossaryStatusLabel, glossaryTermStatusLabel } from '../../../utils/enumLabels'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const glossaries = ref<GlossaryItem[]>([])
const terms = ref<GlossaryTermItem[]>([])
const selectedGlossaryId = ref<number>()
const selectedTermId = ref<number>()
const datasources = ref<DatasourceSimpleItem[]>([])

const loadingGlossaries = ref(false)
const loadingTerms = ref(false)
const loadingLinked = ref(false)
const actionLoading = ref(false)
const glossaryError = ref('')
const termError = ref('')
const linkedError = ref('')

const termKeyword = ref('')
const termStatusFilter = ref('')

const linkedColumns = ref<LinkedColumnItem[]>([])

const selectedGlossary = computed(() => glossaries.value.find((item) => item.id === selectedGlossaryId.value) || null)
const selectedTerm = computed(() => terms.value.find((item) => item.id === selectedTermId.value) || null)

const visibleTerms = computed(() => {
  const keyword = termKeyword.value.trim().toLowerCase()
  return terms.value.filter((term) => {
    if (termStatusFilter.value && term.status !== termStatusFilter.value) return false
    if (!keyword) return true
    return [term.name, term.displayName, term.fqn, term.description]
      .some((value) => value?.toLowerCase().includes(keyword))
  })
})

const termCounts = computed(() => ({
  total: terms.value.length,
  pending: terms.value.filter((term) => term.status === 'PENDING_REVIEW').length,
  approved: terms.value.filter((term) => term.status === 'APPROVED').length,
  rejected: terms.value.filter((term) => term.status === 'REJECTED').length,
}))

function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

/** 术语的同义词在库中以 JSON 数组字符串保存，展示时解析失败就退回原文 */
function parseSynonyms(value?: string): string[] {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.map((item) => String(item)) : [value]
  } catch {
    return value.split(',').map((item) => item.trim()).filter(Boolean)
  }
}

async function loadGlossaries(preferredId?: number) {
  loadingGlossaries.value = true
  glossaryError.value = ''
  try {
    const result = await listGlossaries()
    glossaries.value = result.data || []
    const next = preferredId
      || (glossaries.value.some((item) => item.id === selectedGlossaryId.value) ? selectedGlossaryId.value : undefined)
      || glossaries.value[0]?.id
    if (next) await selectGlossary(next)
    else {
      selectedGlossaryId.value = undefined
      terms.value = []
      selectedTermId.value = undefined
    }
  } catch (cause) {
    glossaryError.value = apiError(cause, '术语表加载失败')
  } finally {
    loadingGlossaries.value = false
  }
}

async function selectGlossary(id: number) {
  selectedGlossaryId.value = id
  selectedTermId.value = undefined
  linkedColumns.value = []
  await loadTerms()
}

async function loadTerms() {
  if (!selectedGlossaryId.value) {
    terms.value = []
    return
  }
  loadingTerms.value = true
  termError.value = ''
  try {
    const result = await listTerms(selectedGlossaryId.value)
    terms.value = result.data || []
    if (selectedTermId.value && !terms.value.some((item) => item.id === selectedTermId.value)) {
      selectedTermId.value = undefined
    }
  } catch (cause) {
    termError.value = apiError(cause, '术语列表加载失败')
  } finally {
    loadingTerms.value = false
  }
}

async function selectTerm(term: GlossaryTermItem) {
  selectedTermId.value = term.id
  await loadLinkedColumns()
}

async function loadLinkedColumns() {
  if (!selectedTermId.value) {
    linkedColumns.value = []
    return
  }
  loadingLinked.value = true
  linkedError.value = ''
  try {
    const result = await getLinkedColumns(selectedTermId.value)
    linkedColumns.value = result.data || []
  } catch (cause) {
    linkedColumns.value = []
    linkedError.value = apiError(cause, '关联字段加载失败')
  } finally {
    loadingLinked.value = false
  }
}

/* ---------------- 术语表编辑 ---------------- */

const glossaryDialogVisible = ref(false)
const editingGlossaryId = ref<number>()
const glossaryForm = reactive({ name: '', displayName: '', description: '', status: 'DRAFT' })

function openCreateGlossary() {
  editingGlossaryId.value = undefined
  Object.assign(glossaryForm, { name: '', displayName: '', description: '', status: 'DRAFT' })
  glossaryDialogVisible.value = true
}

function openEditGlossary(item: GlossaryItem) {
  editingGlossaryId.value = item.id
  Object.assign(glossaryForm, {
    name: item.name,
    displayName: item.displayName || '',
    description: item.description || '',
    status: item.status || 'DRAFT',
  })
  glossaryDialogVisible.value = true
}

async function saveGlossary() {
  if (!glossaryForm.name.trim()) {
    ElMessage.warning('术语表名称不能为空')
    return
  }
  actionLoading.value = true
  try {
    if (editingGlossaryId.value) {
      await updateGlossary(editingGlossaryId.value, { ...glossaryForm })
      ElMessage.success('术语表已更新')
    } else {
      await createGlossary({ ...glossaryForm })
      ElMessage.success('术语表已创建')
    }
    glossaryDialogVisible.value = false
    await loadGlossaries(editingGlossaryId.value)
  } catch (cause) {
    ElMessage.error(apiError(cause, '术语表保存失败'))
  } finally {
    actionLoading.value = false
  }
}

async function removeGlossary(item: GlossaryItem) {
  try {
    await ElMessageBox.confirm(
      `删除术语表「${item.displayName || item.name}」后，其下术语将不再出现在业务术语工作区。确认删除？`,
      '删除术语表',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
    actionLoading.value = true
    await deleteGlossary(item.id)
    ElMessage.success('术语表已删除')
    if (selectedGlossaryId.value === item.id) selectedGlossaryId.value = undefined
    await loadGlossaries()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '术语表删除失败'))
  } finally {
    actionLoading.value = false
  }
}

/* ---------------- 术语编辑 ---------------- */

const termDialogVisible = ref(false)
const editingTermId = ref<number>()
const termForm = reactive({ name: '', displayName: '', description: '', synonyms: '', relatedTerms: '' })

function openCreateTerm() {
  if (!selectedGlossaryId.value) {
    ElMessage.warning('请先选择或创建术语表')
    return
  }
  editingTermId.value = undefined
  Object.assign(termForm, { name: '', displayName: '', description: '', synonyms: '', relatedTerms: '' })
  termDialogVisible.value = true
}

function openEditTerm(term: GlossaryTermItem) {
  editingTermId.value = term.id
  Object.assign(termForm, {
    name: term.name,
    displayName: term.displayName || '',
    description: term.description || '',
    synonyms: parseSynonyms(term.synonyms).join(', '),
    relatedTerms: term.relatedTerms || '',
  })
  termDialogVisible.value = true
}

async function saveTerm() {
  if (!selectedGlossaryId.value) return
  if (!termForm.name.trim()) {
    ElMessage.warning('术语名不能为空')
    return
  }
  const payload: Partial<GlossaryTermItem> = {
    name: termForm.name.trim(),
    displayName: termForm.displayName.trim(),
    description: termForm.description,
    // 后端按 JSON 数组字符串保存同义词，这里把逗号分隔的输入转换为 JSON
    synonyms: JSON.stringify(termForm.synonyms.split(',').map((item) => item.trim()).filter(Boolean)),
    relatedTerms: termForm.relatedTerms,
  }
  actionLoading.value = true
  try {
    if (editingTermId.value) {
      await updateTerm(editingTermId.value, payload)
      ElMessage.success('术语已更新')
    } else {
      await createTerm(selectedGlossaryId.value, payload)
      ElMessage.success('术语已创建')
    }
    termDialogVisible.value = false
    await loadTerms()
  } catch (cause) {
    ElMessage.error(apiError(cause, '术语保存失败'))
  } finally {
    actionLoading.value = false
  }
}

async function removeTerm(term: GlossaryTermItem) {
  try {
    await ElMessageBox.confirm(`确认删除术语「${term.displayName || term.name}」？`, '删除术语', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
    })
    actionLoading.value = true
    await deleteTerm(term.id)
    ElMessage.success('术语已删除')
    if (selectedTermId.value === term.id) selectedTermId.value = undefined
    await loadTerms()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '术语删除失败'))
  } finally {
    actionLoading.value = false
  }
}

async function submitTerm(term: GlossaryTermItem) {
  try {
    await ElMessageBox.confirm(
      `提交术语「${term.displayName || term.name}」审核？提交后需先完成审核才能继续修改。`,
      '提交审核',
    )
    actionLoading.value = true
    await submitTermForReview(term.id)
    ElMessage.success('已提交审核')
    await loadTerms()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '提交审核失败'))
  } finally {
    actionLoading.value = false
  }
}

/**
 * 把已通过的术语退回草稿。
 *
 * 后端 2026-09-12 补上了 `APPROVED → DRAFT` 的合法路径，并给 updateTerm 加了状态校验。
 * 此前状态机从 APPROVED 没有出边、而 updateTerm 不校验状态，形成「合规流程被限制、
 * 绕过路径不受限」的倒挂，前端只能把已通过术语整体锁成只读。
 */
async function revertTerm(term: GlossaryTermItem) {
  try {
    await ElMessageBox.confirm(
      `把术语「${term.displayName || term.name}」退回草稿？退回会清空审核人与审核时间，`
      + '修改后需要重新提交审核。',
      '退回草稿',
      { type: 'warning', confirmButtonText: '确认退回', cancelButtonText: '取消' },
    )
    actionLoading.value = true
    await revertTermToDraft(term.id)
    ElMessage.success('已退回草稿，修改后请重新提交审核')
    await loadTerms()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '退回草稿失败'))
  } finally {
    actionLoading.value = false
  }
}

async function review(term: GlossaryTermItem, approved: boolean) {
  const action = approved ? '通过' : '拒绝'
  try {
    let reason: string | undefined
    if (approved) {
      await ElMessageBox.confirm(`确认通过术语「${term.displayName || term.name}」？`, '审核通过')
    } else {
      const result = await ElMessageBox.prompt(`请输入拒绝「${term.displayName || term.name}」的原因`, '审核拒绝', {
        inputValidator: (value) => Boolean(value?.trim()) || '拒绝原因不能为空',
        confirmButtonText: '确认拒绝',
        cancelButtonText: '取消',
      })
      reason = result.value
    }
    actionLoading.value = true
    await reviewTerm(term.id, approved, reason)
    ElMessage.success(`术语已${action}`)
    await loadTerms()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, `审核${action}失败`))
  } finally {
    actionLoading.value = false
  }
}

/* ---------------- 关联字段 ---------------- */

const linkDialogVisible = ref(false)
const linkDatasourceId = ref<number>()
const linkKeyword = ref('')
const linkCandidates = ref<LinkedColumnItem[]>([])
const linkCandidatesLoading = ref(false)

async function openLinkDialog() {
  linkDatasourceId.value = datasources.value[0]?.id
  linkKeyword.value = ''
  linkCandidates.value = []
  linkDialogVisible.value = true
  if (linkDatasourceId.value) await loadLinkCandidates()
}

/**
 * 数据源内字段候选。
 *
 * 使用 `/entities?datasourceId=` 拉取后在页面内过滤。`/api/admin/catalog/search` 自
 * 2026-09-12 起已支持真实 datasourceId 过滤（此前该参数被后端忽略），但切换到服务端
 * 搜索属阶段 8 的收敛项，不在本次缺陷修复范围内，此处保持既有实现不变。
 */
async function loadLinkCandidates() {
  if (!linkDatasourceId.value) {
    linkCandidates.value = []
    return
  }
  linkCandidatesLoading.value = true
  try {
    const result = await getEntitiesByDatasource(linkDatasourceId.value)
    linkCandidates.value = (result.data || []).filter((item) => item.entityType === 'COLUMN')
  } catch (cause) {
    linkCandidates.value = []
    ElMessage.error(apiError(cause, '字段候选加载失败'))
  } finally {
    linkCandidatesLoading.value = false
  }
}

const filteredLinkCandidates = computed(() => {
  const keyword = linkKeyword.value.trim().toLowerCase()
  const linkedIds = new Set(linkedColumns.value.map((item) => item.id))
  return linkCandidates.value
    .filter((item) => !linkedIds.has(item.id))
    .filter((item) => !keyword || [item.name, item.displayName, item.fqn].some((value) => value?.toLowerCase().includes(keyword)))
    .slice(0, 50)
})

async function linkColumn(entity: LinkedColumnItem) {
  if (!selectedTermId.value) return
  actionLoading.value = true
  try {
    await linkTermToColumn(selectedTermId.value, entity.id)
    ElMessage.success('已关联字段')
    await loadLinkedColumns()
  } catch (cause) {
    ElMessage.error(apiError(cause, '关联字段失败'))
  } finally {
    actionLoading.value = false
  }
}

async function unlinkColumn(entity: LinkedColumnItem) {
  if (!selectedTermId.value) return
  try {
    await ElMessageBox.confirm(`解除与「${entity.displayName || entity.name}」的关联？`, '解除关联', {
      type: 'warning',
      confirmButtonText: '解除',
      cancelButtonText: '取消',
    })
    actionLoading.value = true
    await unlinkTermFromColumn(selectedTermId.value, entity.id)
    ElMessage.success('已解除关联')
    await loadLinkedColumns()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '解除关联失败'))
  } finally {
    actionLoading.value = false
  }
}

onMounted(async () => {
  try {
    const result = await listSimpleDatasources()
    datasources.value = result.data || []
  } catch {
    datasources.value = []
  }
  await loadGlossaries()
})
</script>

<template>
  <div class="admin-page glossaries-page">
    <TaskPageHeader
      eyebrow="语义中心"
      title="业务术语"
      description="把业务叫法绑定到物理字段。术语通过审核后才会进入检索改写，未通过审核的术语不应被当作可用语义。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loadingGlossaries" @click="loadGlossaries()">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateGlossary">新建术语表</el-button>
      </template>
    </TaskPageHeader>

    <ErrorState v-if="glossaryError" :message="glossaryError" @retry="loadGlossaries()" />

    <div v-else class="glossaries-layout">
      <!-- 术语表列表 -->
      <aside class="panel glossary-column">
        <header class="panel__heading">
          <div>
            <h2>术语表</h2>
            <p>{{ glossaries.length }} 个术语表</p>
          </div>
        </header>
        <LoadingState v-if="loadingGlossaries" text="正在读取术语表…" />
        <EmptyState
          v-else-if="!glossaries.length"
          message="还没有术语表。先创建术语表，再在其中维护业务术语。"
          action-text="新建术语表"
          @action="openCreateGlossary"
        />
        <ul v-else class="glossary-list">
          <li
            v-for="item in glossaries"
            :key="item.id"
            class="glossary-list__item"
            :class="{ 'is-active': item.id === selectedGlossaryId }"
          >
            <button type="button" class="glossary-list__select" @click="selectGlossary(item.id)">
              <strong>{{ item.displayName || item.name }}</strong>
              <small>{{ item.name }}</small>
            </button>
            <div class="glossary-list__meta">
              <BusinessStatusBadge :status="item.status" :label="glossaryStatusLabel(item.status)" />
              <span class="glossary-list__actions">
                <el-button link :icon="Pencil" aria-label="编辑术语表" @click="openEditGlossary(item)" />
                <el-button link type="danger" :icon="Trash2" aria-label="删除术语表" @click="removeGlossary(item)" />
              </span>
            </div>
          </li>
        </ul>
      </aside>

      <!-- 术语列表 -->
      <section class="panel term-column">
        <header class="panel__heading">
          <div>
            <h2>{{ selectedGlossary ? (selectedGlossary.displayName || selectedGlossary.name) : '术语' }}</h2>
            <p v-if="selectedGlossary">
              共 {{ termCounts.total }} 条 · 待审核 {{ termCounts.pending }} · 已通过 {{ termCounts.approved }} · 已拒绝 {{ termCounts.rejected }}
            </p>
            <p v-else>请选择左侧术语表</p>
          </div>
          <el-button type="primary" :icon="Plus" :disabled="!selectedGlossary" @click="openCreateTerm">新增术语</el-button>
        </header>

        <div v-if="selectedGlossary" class="term-toolbar">
          <el-input v-model="termKeyword" placeholder="搜索术语名 / 显示名 / FQN" clearable style="width: 220px" />
          <el-select v-model="termStatusFilter" placeholder="全部状态" clearable style="width: 140px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待审核" value="PENDING_REVIEW" />
            <el-option label="已通过" value="APPROVED" />
            <el-option label="已拒绝" value="REJECTED" />
          </el-select>
        </div>

        <ErrorState v-if="termError" :message="termError" @retry="loadTerms" />
        <LoadingState v-else-if="loadingTerms" variant="skeleton" :rows="5" />
        <EmptyState
          v-else-if="!selectedGlossary"
          message="业务术语按术语表组织，请先在左侧选择或创建术语表。"
          action-text="新建术语表"
          @action="openCreateGlossary"
        />
        <EmptyState
          v-else-if="!visibleTerms.length"
          :message="terms.length ? '当前筛选条件下没有术语。' : '该术语表还没有术语，先新增一条业务术语。'"
          action-text="新增术语"
          @action="openCreateTerm"
        />
        <ul v-else class="term-list">
          <li
            v-for="term in visibleTerms"
            :key="term.id"
            class="term-list__item"
            :class="{ 'is-active': term.id === selectedTermId }"
          >
            <button type="button" class="term-list__select" @click="selectTerm(term)">
              <strong>{{ term.displayName || term.name }}</strong>
              <small>{{ term.fqn }}</small>
              <span v-if="parseSynonyms(term.synonyms).length" class="term-list__synonyms">
                同义词：{{ parseSynonyms(term.synonyms).join('、') }}
              </span>
            </button>
            <BusinessStatusBadge :status="term.status" :label="glossaryTermStatusLabel(term.status)" />
          </li>
        </ul>
      </section>

      <!-- 术语详情 -->
      <aside class="panel detail-column">
        <header class="panel__heading">
          <div>
            <h2>术语详情</h2>
            <p v-if="selectedTerm">定义、同义词、状态、关联字段与审核信息</p>
            <p v-else>在中间列表中选择一条术语</p>
          </div>
        </header>

        <EmptyState v-if="!selectedTerm" message="选择术语后可以查看定义、关联字段并执行允许的状态动作。" />

        <div v-else class="term-detail">
          <div class="term-detail__status">
            <BusinessStatusBadge :status="selectedTerm.status" :label="glossaryTermStatusLabel(selectedTerm.status)" />
            <span class="term-detail__type">状态码 {{ selectedTerm.status }}</span>
          </div>

          <dl class="term-detail__facts">
            <div><dt>术语名</dt><dd>{{ selectedTerm.name }}</dd></div>
            <div><dt>显示名</dt><dd>{{ selectedTerm.displayName || '—' }}</dd></div>
            <div><dt>FQN</dt><dd class="is-mono">{{ selectedTerm.fqn }}</dd></div>
            <div><dt>定义</dt><dd>{{ selectedTerm.description || '尚未填写定义' }}</dd></div>
            <div>
              <dt>同义词</dt>
              <dd>{{ parseSynonyms(selectedTerm.synonyms).join('、') || '尚未配置同义词' }}</dd>
            </div>
            <div><dt>相关术语</dt><dd>{{ selectedTerm.relatedTerms || '—' }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ selectedTerm.updatedAt || selectedTerm.createdAt || '—' }}</dd></div>
          </dl>

          <!-- 状态门禁：动作以后端状态机为准 -->
          <div class="term-detail__actions">
            <template v-if="selectedTerm.status === 'DRAFT' || selectedTerm.status === 'REJECTED'">
              <el-button type="primary" :icon="Pencil" @click="openEditTerm(selectedTerm)">编辑</el-button>
              <el-button :icon="Send" :loading="actionLoading" @click="submitTerm(selectedTerm)">提交审核</el-button>
              <el-button type="danger" plain :icon="Trash2" @click="removeTerm(selectedTerm)">删除</el-button>
            </template>
            <template v-else-if="selectedTerm.status === 'PENDING_REVIEW'">
              <el-button type="primary" :icon="Check" :loading="actionLoading" @click="review(selectedTerm, true)">审核通过</el-button>
              <el-button type="danger" plain :icon="X" :loading="actionLoading" @click="review(selectedTerm, false)">审核拒绝</el-button>
            </template>
            <template v-else>
              <el-button :icon="RotateCcw" :loading="actionLoading" @click="revertTerm(selectedTerm)">退回草稿</el-button>
              <p class="term-detail__locked">
                已通过的术语不能直接修改，需先退回草稿。退回会清空审核人与审核时间——
                原审核结论不再代表修改后的内容，修改后必须重新提交审核。
              </p>
            </template>
          </div>

          <!-- 审核记录 -->
          <section class="term-detail__section">
            <h3>审核记录</h3>
            <dl class="term-detail__facts">
              <div><dt>审核状态</dt><dd>{{ glossaryTermStatusLabel(selectedTerm.status) }}</dd></div>
              <div><dt>审核人</dt><dd>{{ selectedTerm.reviewerId ? '用户 #' + selectedTerm.reviewerId : '—' }}</dd></div>
              <div><dt>审核时间</dt><dd>{{ selectedTerm.reviewedAt || '尚未审核' }}</dd></div>
            </dl>
          </section>

          <!-- 关联字段 -->
          <section class="term-detail__section">
            <div class="section-heading">
              <h3>关联字段（{{ linkedColumns.length }}）</h3>
              <el-button link type="primary" :icon="Link2" @click="openLinkDialog">关联字段</el-button>
            </div>
            <ErrorState v-if="linkedError" :message="linkedError" @retry="loadLinkedColumns" />
            <LoadingState v-else-if="loadingLinked" text="正在读取关联字段…" />
            <EmptyState v-else-if="!linkedColumns.length" message="该术语尚未绑定物理字段，未绑定字段的术语无法在 SQL 生成中定位。" />
            <ul v-else class="linked-list">
              <li v-for="column in linkedColumns" :key="column.id">
                <div>
                  <strong>{{ column.displayName || column.name }}</strong>
                  <small>{{ column.fqn }}</small>
                </div>
                <el-button link type="danger" aria-label="解除关联" @click="unlinkColumn(column)">解除</el-button>
              </li>
            </ul>
          </section>
        </div>
      </aside>
    </div>

    <!-- 术语表对话框 -->
    <el-dialog v-model="glossaryDialogVisible" :title="editingGlossaryId ? '编辑术语表' : '新建术语表'" width="520px">
      <el-form label-width="88px">
        <el-form-item label="名称">
          <el-input v-model="glossaryForm.name" placeholder="英文标识，如 finance_metrics" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="glossaryForm.displayName" placeholder="中文名称，如 财务指标" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="glossaryForm.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="glossaryForm.status" style="width: 100%">
            <el-option :label="glossaryStatusLabel('DRAFT')" value="DRAFT" />
            <el-option :label="glossaryStatusLabel('PUBLISHED')" value="PUBLISHED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="glossaryDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="saveGlossary">保存</el-button>
      </template>
    </el-dialog>

    <!-- 术语对话框 -->
    <el-dialog v-model="termDialogVisible" :title="editingTermId ? '编辑术语' : '新增术语'" width="620px">
      <el-form label-width="88px">
        <el-form-item label="术语名">
          <el-input v-model="termForm.name" placeholder="术语名称" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="termForm.displayName" placeholder="显示名称" />
        </el-form-item>
        <el-form-item label="定义">
          <el-input v-model="termForm.description" type="textarea" :rows="3" placeholder="业务口径说明，该内容会进入检索改写" />
        </el-form-item>
        <el-form-item label="同义词">
          <el-input v-model="termForm.synonyms" placeholder="用逗号分隔，如：营收, 收入, Revenue" />
        </el-form-item>
        <el-form-item label="相关术语">
          <el-input v-model="termForm.relatedTerms" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="termDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="saveTerm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 关联字段对话框 -->
    <el-dialog v-model="linkDialogVisible" title="关联物理字段" width="680px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="先选择数据源，再从中挑出该术语对应的物理列。后端会创建 GLOSSARY_OF 关系。"
      />
      <div class="link-toolbar">
        <el-select v-model="linkDatasourceId" placeholder="选择数据源" style="width: 220px" @change="loadLinkCandidates">
          <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
        </el-select>
        <el-input v-model="linkKeyword" placeholder="按字段名 / 资产路径过滤" clearable style="width: 260px" />
      </div>
      <LoadingState v-if="linkCandidatesLoading" variant="skeleton" :rows="4" />
      <EmptyState
        v-else-if="!filteredLinkCandidates.length"
        message="没有可关联的字段。请确认数据源已完成快照发布并同步了实体，或调整过滤条件。"
      />
      <ul v-else class="link-candidates">
        <li v-for="entity in filteredLinkCandidates" :key="entity.id">
          <div>
            <strong>{{ entity.displayName || entity.name }}</strong>
            <small>{{ entity.fqn }}</small>
          </div>
          <el-button link type="primary" :loading="actionLoading" @click="linkColumn(entity)">关联</el-button>
        </li>
      </ul>
      <template #footer>
        <el-button @click="linkDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.glossaries-page { display: grid; gap: 16px; }

.glossaries-layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr) 380px;
  gap: 16px;
  align-items: start;
}

.panel {
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.panel__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.panel__heading h2 { margin: 0; color: var(--do-ink); font-size: 15px; }
.panel__heading p { margin: 4px 0 0; color: var(--do-muted); font-size: 12px; }

.glossary-list, .term-list, .linked-list, .link-candidates {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.glossary-list__item, .term-list__item {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
}

.glossary-list__item.is-active, .term-list__item.is-active {
  border-color: var(--do-primary);
  background: var(--do-primary-soft);
}

.glossary-list__select, .term-list__select {
  display: grid;
  gap: 3px;
  width: 100%;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.glossary-list__select strong, .term-list__select strong { color: var(--do-ink); font-size: 13px; }
.glossary-list__select small, .term-list__select small { color: var(--do-muted); font-size: 11px; word-break: break-all; }
.term-list__synonyms { color: var(--do-muted); font-size: 11px; }

.glossary-list__meta { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.glossary-list__actions { display: inline-flex; }

.term-column { min-width: 0; }
.term-toolbar { display: flex; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }

.term-detail { display: grid; gap: 16px; }
.term-detail__status { display: flex; align-items: center; gap: 8px; }
.term-detail__type { color: var(--do-muted); font-size: 11px; text-transform: uppercase; }

.term-detail__facts { display: grid; gap: 8px; margin: 0; }
.term-detail__facts > div { display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 8px; }
.term-detail__facts dt { color: var(--do-muted); font-size: 12px; }
.term-detail__facts dd { margin: 0; color: var(--do-ink); font-size: 13px; word-break: break-word; }
.term-detail__facts .is-mono { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 12px; }

.term-detail__actions { display: flex; flex-wrap: wrap; gap: 8px; }
.term-detail__locked { margin: 0; padding: 10px 12px; border-radius: var(--do-radius-md); background: var(--do-bg); color: var(--do-muted); font-size: 12px; line-height: 1.7; }

.term-detail__section { display: grid; gap: 10px; padding-top: 14px; border-top: 1px solid var(--do-line); }
.term-detail__section h3 { margin: 0; color: var(--do-ink); font-size: 13px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }

.linked-list li, .link-candidates li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
}

.linked-list strong, .link-candidates strong { display: block; color: var(--do-ink); font-size: 13px; }
.linked-list small, .link-candidates small { color: var(--do-muted); font-size: 11px; word-break: break-all; }

.link-toolbar { display: flex; gap: 10px; margin: 14px 0; flex-wrap: wrap; }
.link-candidates { max-height: 340px; overflow: auto; }

@media (max-width: 1440px) {
  .glossaries-layout { grid-template-columns: 240px minmax(0, 1fr) 340px; }
}

@media (max-width: 1180px) {
  .glossaries-layout { grid-template-columns: minmax(0, 1fr); }
}
</style>
