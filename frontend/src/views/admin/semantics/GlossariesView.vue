<script setup lang="ts">
/**
 * 业务术语工作区
 *
 * 按「术语分组 → 术语 → 关联字段 → 审核状态」组织，替代旧的单表格 GlossaryList.vue。
 *
 * 后端事实（GlossaryController / GlossaryTermServiceImpl）：
 * - 术语分组只有 DRAFT / PUBLISHED 两个常量，且没有独立的状态流转接口，因此状态只在创建/编辑时设置。
 * - 术语状态流转为 DRAFT|REJECTED -> PENDING_REVIEW -> APPROVED|REJECTED，
 *   另有 APPROVED -> DRAFT 的退回路径（后端 2026-09-12 补齐）。
 *   已通过术语不能直接编辑，需先退回草稿；退回会清空审核人与审核时间。
 * - 术语与物理列的关联通过 GLOSSARY_OF 关系维护，接口为 link-column / unlink-column / linked-columns。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowRight, Check, Link2, Pencil, Plus, RefreshCw, RotateCcw, Send, Trash2, X } from 'lucide-vue-next'
import {
  createGlossary,
  getTermWorkspace,
  getTermFieldSources,
  type TermColumn,
  type TermReview,
  deleteGlossary,
  deleteTerm,
  listGlossaries,
  listTerms,
  reviewTerm,
  revertTermToDraft,
  submitTermForReview,
  updateGlossary,
  type GlossaryItem,
  type GlossaryTermItem,
} from '../../../api/admin/glossary'
import TermEditor from '../../../components/admin/TermEditor.vue'
import { getHttpErrorMessage } from '../../../utils/httpError'

import { glossaryStatusLabel, glossaryTermStatusLabel } from '../../../utils/enumLabels'
import { useIamS1Store } from '../../../stores/iamS1'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const iamS1 = useIamS1Store()

/**
 * `glossary:view` / `glossary:manage` / `glossary:approve` 是「源/全」混合码：
 * 术语未关联任何数据源时只校验功能，已关联时由后端按**关联源**逐源校验。
 *
 * `hasGlobal` 与后端 `@IamS1ScopedList` 的功能级准入同源（都是“启用角色是否授予该功能码”，
 * 与负责源无关），因此这里用它做粗粒度入口判定；前端**不知道**某个术语具体关联了哪些源，
 * 动态范围一律由后端拒绝，按钮可见性不是安全边界。
 */
const canView = computed(() => iamS1.hasGlobal('glossary:view'))
const canManage = computed(() => iamS1.hasGlobal('glossary:manage'))
const canApprove = computed(() => iamS1.hasGlobal('glossary:approve'))

/** 权限不足时的中文原因，避免“点了才发现必然失败”。 */
const MANAGE_HINT = '没有“维护业务术语”能力：需要角色拥有 glossary:manage 并负责该术语关联的数据源。'
const APPROVE_HINT = '没有“审核术语”能力：需要角色拥有 glossary:approve 并负责该术语关联的数据源。审核权不自动带来维护权。'

const glossaries = ref<GlossaryItem[]>([])
const terms = ref<GlossaryTermItem[]>([])
/** 选中的术语分组与术语由 URL 查询参数决定，刷新/分享/前进后退都能恢复（开发指导 §15） */
const selectedGlossaryId = ref<number | undefined>(Number(route.query.glossaryId) || undefined)
const selectedTermId = ref<number | undefined>(Number(route.query.termId) || undefined)
const datasources = ref<Array<{ id: number; name: string }>>([])

const loadingGlossaries = ref(false)
const loadingTerms = ref(false)
const loadingLinked = ref(false)
const actionLoading = ref(false)
const glossaryError = ref('')
const termError = ref('')
const datasourceError = ref('')
const linkedError = ref('')

const termKeyword = ref('')
const termStatusFilter = ref('')

const linkedColumns = ref<TermColumn[]>([])
const reviews = ref<TermReview[]>([])
const needsReview = ref(false)

const selectedGlossary = computed(() => glossaries.value.find((item) => item.id === selectedGlossaryId.value) || null)
const selectedTerm = computed(() => terms.value.find((item) => item.id === selectedTermId.value) || null)

const visibleTerms = computed(() => {
  const keyword = termKeyword.value.trim().toLowerCase()
  return terms.value.filter((term) => {
    if (termStatusFilter.value && term.status !== termStatusFilter.value) return false
    if (!keyword) return true
    return [term.name, term.displayName, term.fqn, term.description, ...parseSynonyms(term.synonyms)]
      .some((value) => value?.toLowerCase().includes(keyword))
  })
})

const termCounts = computed(() => ({
  total: terms.value.length,
  pending: terms.value.filter((term) => term.status === 'PENDING_REVIEW').length,
  approved: terms.value.filter((term) => term.status === 'APPROVED').length,
  rejected: terms.value.filter((term) => term.status === 'REJECTED').length,
}))

const apiError = getHttpErrorMessage

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
    // keepTerm=true：刷新恢复时保留 URL 带来的术语，由 loadTerms 校验它是否属于该术语分组
    if (next) await selectGlossary(next, true)
    else {
      selectedGlossaryId.value = undefined
      terms.value = []
      selectedTermId.value = undefined
    }
  } catch (cause) {
    glossaryError.value = apiError(cause, '术语分组加载失败')
  } finally {
    loadingGlossaries.value = false
  }
}

/**
 * 把当前选中的术语分组与术语写进 URL。
 *
 * 用 `push`：开发指导 §15 要求「URL 刷新、前进和后退恢复」，只有 push 才能让
 * 浏览器后退回到上一个选中对象。
 */
function persistSelection() {
  const query: Record<string, string> = { ...route.query as Record<string, string> }
  if (selectedGlossaryId.value) query.glossaryId = String(selectedGlossaryId.value)
  else delete query.glossaryId
  if (selectedTermId.value) query.termId = String(selectedTermId.value)
  else delete query.termId
  router.push({ query })
}

/**
 * 选择术语分组。
 *
 * @param id        术语分组 ID
 * @param keepTerm  是否保留当前选中的术语。刷新恢复时由 URL 带来的术语需要保留，
 *                  用户点击切换术语分组时则必须清空
 */
async function selectGlossary(id: number, keepTerm = false) {
  selectedGlossaryId.value = id
  if (!keepTerm) {
    selectedTermId.value = undefined
    linkedColumns.value = []
  }
  persistSelection()
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
      // 选中的术语不属于当前术语分组（URL 里带了别的术语分组的术语）：清掉，不静默换成其它术语
      selectedTermId.value = undefined
      linkedColumns.value = []
      persistSelection()
    } else if (selectedTermId.value) {
      // 刷新恢复：URL 带来的术语有效，把关联字段一并取回
      await loadLinkedColumns()
    }
  } catch (cause) {
    termError.value = apiError(cause, '术语列表加载失败')
  } finally {
    loadingTerms.value = false
  }
}

async function selectTerm(term: GlossaryTermItem) {
  selectedTermId.value = term.id
  persistSelection()
  await loadLinkedColumns()
}

let linkedRequestId = 0
async function loadLinkedColumns() {
  const requestId = ++linkedRequestId
  const termId = selectedTermId.value
  if (!termId) { linkedColumns.value = []; reviews.value = []; loadingLinked.value = false; return }
  loadingLinked.value = true
  linkedError.value = ''
  try {
    const result = await getTermWorkspace(termId)
    if (requestId !== linkedRequestId || selectedTermId.value !== termId) return
    linkedColumns.value = result.data.columns || []
    reviews.value = result.data.reviews || []
    needsReview.value = result.data.needsReview
    linkedError.value = result.data.mappingError || ''
    const index = terms.value.findIndex(term => term.id === termId)
    if (index >= 0) {
      const previous = terms.value[index]!
      terms.value[index] = { ...previous, ...result.data.term,
        linkedColumnCount: result.data.term.linkedColumnCount ?? previous.linkedColumnCount,
        datasourceNames: result.data.columns.length ? [...new Set(result.data.columns.map(column => column.datasourceName))]
          : result.data.term.datasourceNames ?? previous.datasourceNames }
    }
  } catch (cause) {
    if (requestId === linkedRequestId && selectedTermId.value === termId) {
      linkedColumns.value = []; reviews.value = []
      linkedError.value = apiError(cause, '关联字段加载失败')
    }
  } finally { if (requestId === linkedRequestId) loadingLinked.value = false }
}

/* ---------------- 术语分组编辑 ---------------- */

const glossaryDialogVisible = ref(false)
const editingGlossaryId = ref<number>()
const glossaryForm = reactive({ name: '', displayName: '', description: '', status: 'DRAFT' })

function openCreateGlossary() {
  if (!canManage.value) { ElMessage.warning(MANAGE_HINT); return }
  editingGlossaryId.value = undefined
  Object.assign(glossaryForm, { name: '', displayName: '', description: '', status: 'DRAFT' })
  glossaryDialogVisible.value = true
}

function openEditGlossary(item: GlossaryItem) {
  if (!canManage.value) { ElMessage.warning(MANAGE_HINT); return }
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
  if (!canManage.value) { ElMessage.warning(MANAGE_HINT); return }
  if (!glossaryForm.name.trim()) {
    ElMessage.warning('术语分组名称不能为空')
    return
  }
  actionLoading.value = true
  try {
    if (editingGlossaryId.value) {
      await updateGlossary(editingGlossaryId.value, { ...glossaryForm })
      ElMessage.success('术语分组已更新')
    } else {
      await createGlossary({ ...glossaryForm })
      ElMessage.success('术语分组已创建')
    }
    glossaryDialogVisible.value = false
    await loadGlossaries(editingGlossaryId.value)
  } catch (cause) {
    ElMessage.error(apiError(cause, '术语分组保存失败'))
  } finally {
    actionLoading.value = false
  }
}

async function removeGlossary(item: GlossaryItem) {
  if (!canManage.value) { ElMessage.warning(MANAGE_HINT); return }
  try {
    await ElMessageBox.confirm(
      `删除术语分组「${item.displayName || item.name}」后，其下术语将不再出现在业务术语工作区。确认删除？`,
      '删除术语分组',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
    actionLoading.value = true
    await deleteGlossary(item.id)
    ElMessage.success('术语分组已删除')
    if (selectedGlossaryId.value === item.id) selectedGlossaryId.value = undefined
    await loadGlossaries()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '术语分组删除失败'))
  } finally {
    actionLoading.value = false
  }
}

/* ---------------- 术语编辑 ---------------- */

const termEditor = ref<InstanceType<typeof TermEditor>>()
function openCreateTerm() {
  if (!canManage.value || !selectedGlossaryId.value) { ElMessage.warning(MANAGE_HINT); return }
  termEditor.value?.open()
}
function openEditTerm(term: GlossaryTermItem) {
  if (!canManage.value) { ElMessage.warning(MANAGE_HINT); return }
  termEditor.value?.open(term)
}
async function onTermSaved(term: GlossaryTermItem) {
  selectedTermId.value = term.id
  persistSelection()
  await loadTerms()
}

async function removeTerm(term: GlossaryTermItem) {
  if (!canManage.value) { ElMessage.warning(MANAGE_HINT); return }
  try {
    await ElMessageBox.confirm(`确认删除术语「${term.displayName || term.name}」？`, '删除术语', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
    })
    actionLoading.value = true
    await deleteTerm(term.id, term.revision)
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
  if (!canManage.value) { ElMessage.warning(MANAGE_HINT); return }
  try {
    await ElMessageBox.confirm(
      `提交术语「${term.displayName || term.name}」审核？提交后需先完成审核才能继续修改。`,
      '提交审核',
    )
    actionLoading.value = true
    await submitTermForReview(term.id, term.revision)
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
  if (!canManage.value) { ElMessage.warning(MANAGE_HINT); return }
  try {
    await ElMessageBox.confirm(
      `把术语「${term.displayName || term.name}」退回草稿？退回会清空审核人与审核时间，`
      + '修改后需要重新提交审核。',
      '退回草稿',
      { type: 'warning', confirmButtonText: '确认退回', cancelButtonText: '取消' },
    )
    actionLoading.value = true
    await revertTermToDraft(term.id, term.revision)
    ElMessage.success('已退回草稿，修改后请重新提交审核')
    await loadTerms()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '退回草稿失败'))
  } finally {
    actionLoading.value = false
  }
}

async function review(term: GlossaryTermItem, approved: boolean) {
  if (!canApprove.value) { ElMessage.warning(APPROVE_HINT); return }
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
    await reviewTerm(term.id, approved, reason, term.revision)
    ElMessage.success(`术语已${action}`)
    await loadTerms()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, `审核${action}失败`))
  } finally {
    actionLoading.value = false
  }
}

function reviewSnapshot(record: TermReview): { name?: string; displayName?: string; description?: string; columns?: TermColumn[] } {
  try { return JSON.parse(record.snapshotJson) } catch { return {} }
}
const reviewChanges = computed(() => {
  const previous = reviews.value.find(record => record.action === 'APPROVED')
  if (!previous || !selectedTerm.value) return '首次审核：请核对业务定义及全部关联字段。'
  const snapshot = reviewSnapshot(previous)
  const descriptionChanged = snapshot.description !== selectedTerm.value.description
  const mappingChanged = (snapshot.columns || []).map(c => c.entityId).sort().join(',') !== linkedColumns.value.map(c => c.entityId).sort().join(',')
  return [descriptionChanged ? '业务定义已变化' : '业务定义未变化', mappingChanged ? '字段映射已变化' : '字段映射未变化'].join('；')
})
function openLinkDialog() {
  if (selectedTerm.value) openEditTerm(selectedTerm.value)
}

async function loadSources() {
  datasourceError.value = ''
  if (!canManage.value) return
  try { datasources.value = (await getTermFieldSources()).data || [] }
  catch (cause) { datasourceError.value = apiError(cause, '数据源列表加载失败') }
}
onMounted(async () => {
  // 先取 S1 能力摘要：无 glossary:view 时不应发那个必然 403 的列表请求
  await iamS1.load()
  await loadSources()
  if (!canView.value) {
    glossaries.value = []
    return
  }
  await loadGlossaries()
})

// 浏览器前进/后退或外部改 URL 时同步回组件状态（§15 要求前进后退可恢复）
watch(() => route.query.glossaryId, (value) => {
  const next = Number(value) || undefined
  if (next === selectedGlossaryId.value) return
  if (next) selectGlossary(next, true)
  else {
    selectedGlossaryId.value = undefined
    selectedTermId.value = undefined
    terms.value = []
    linkedColumns.value = []
  }
})

watch(() => route.query.termId, (value) => {
  const next = Number(value) || undefined
  if (next === selectedTermId.value) return
  selectedTermId.value = next
  // 走 loadTerms 而不是直接取关联字段：由它校验该术语是否属于当前术语分组
  loadTerms()
})
</script>

<template>
  <div class="admin-page glossaries-page">
    <TaskPageHeader
      eyebrow="语义中心"
      title="业务术语"
      description="维护业务概念、同义词与实际字段的对应关系。可先保存草稿，关联字段后提交定义和映射审核。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loadingGlossaries" @click="loadGlossaries()">刷新</el-button>
        <el-button type="primary" :icon="Plus" :disabled="!canManage" :title="canManage ? '' : MANAGE_HINT" @click="openCreateGlossary">新建分组</el-button>
      </template>
    </TaskPageHeader>

    <ErrorState v-if="datasourceError" :message="datasourceError" @retry="loadSources" />
    <ErrorState v-if="glossaryError" :message="glossaryError" @retry="loadGlossaries()" />

    <div v-else class="glossaries-layout">
      <!-- 术语分组列表 -->
      <aside class="panel glossary-column">
        <header class="panel__heading">
          <div>
            <h2>术语分组</h2>
            <p>{{ glossaries.length }} 个术语分组</p>
          </div>
        </header>
        <LoadingState v-if="loadingGlossaries" text="正在读取术语分组…" />
        <EmptyState
          v-else-if="!glossaries.length"
          message="还没有术语分组。先创建术语分组，再在其中维护业务术语。"
          action-text="新建分组"
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
                <el-button link :icon="Pencil" aria-label="编辑术语分组" :disabled="!canManage" @click="openEditGlossary(item)" />
                <el-button link type="danger" :icon="Trash2" aria-label="删除术语分组" :disabled="!canManage" @click="removeGlossary(item)" />
              </span>
            </div>
          </li>
        </ul>
      </aside>

      <!-- 右栏：当前术语分组的术语列表 + 术语详情 -->
      <div class="glossaries-main">
      <!-- 术语列表 -->
      <section class="panel term-column">
        <header class="panel__heading">
          <div>
            <h2>{{ selectedGlossary ? (selectedGlossary.displayName || selectedGlossary.name) : '术语' }}</h2>
            <p v-if="selectedGlossary">
              共 {{ termCounts.total }} 条 · 待审核 {{ termCounts.pending }} · 已通过 {{ termCounts.approved }} · 已拒绝 {{ termCounts.rejected }}
            </p>
            <p v-else>请选择左侧术语分组</p>
          </div>
          <el-button type="primary" :icon="Plus" :disabled="!selectedGlossary || !canManage" :title="canManage ? '' : MANAGE_HINT" @click="openCreateTerm">新增术语</el-button>
        </header>

        <div v-if="selectedGlossary" class="term-toolbar">
          <el-input v-model="termKeyword" placeholder="搜索业务名称、定义或同义词" clearable style="width: 220px" />
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
          message="业务术语按术语分组组织，请先在左侧选择或创建术语分组。"
          action-text="新建分组"
          @action="openCreateGlossary"
        />
        <EmptyState
          v-else-if="!visibleTerms.length"
          :message="terms.length ? '当前筛选条件下没有术语。' : '该术语分组还没有术语，先新增一条业务术语。'"
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
              <small>{{ term.description || '尚未填写业务定义' }}</small>
              <small v-if="term.linkedColumnCount !== undefined">{{ term.linkedColumnCount ? `已关联 ${term.linkedColumnCount} 个可见字段` : '尚未关联可见字段' }} {{ term.datasourceNames?.join('、') }}</small>
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
            <span class="term-detail__type">修订 {{ selectedTerm.revision }}</span>
          </div>

          <dl class="term-detail__facts">
            <div><dt>业务名称</dt><dd>{{ selectedTerm.displayName || selectedTerm.name }}</dd></div>

            <div><dt>定义</dt><dd>{{ selectedTerm.description || '尚未填写定义' }}</dd></div>
            <div>
              <dt>同义词</dt>
              <dd>{{ parseSynonyms(selectedTerm.synonyms).join('、') || '尚未配置同义词' }}</dd>
            </div>
            <div v-if="selectedTerm.relatedTerms && selectedTerm.relatedTerms !== '[]'"><dt>相关术语</dt><dd>{{ selectedTerm.relatedTerms }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ selectedTerm.updatedAt || selectedTerm.createdAt || '—' }}</dd></div>
          </dl>

          <!-- 关联字段 -->
          <section class="term-detail__section">
            <div class="section-heading">
              <h3>关联字段（{{ linkedColumns.length }}）</h3>
              <el-button v-if="['DRAFT', 'REJECTED'].includes(selectedTerm.status)" link type="primary" :icon="Link2" :disabled="!canManage" @click="openLinkDialog">修改关联</el-button>
            </div>
            <ErrorState v-if="linkedError" :message="linkedError" @retry="loadLinkedColumns" />
            <LoadingState v-else-if="loadingLinked" text="正在读取关联字段…" />
            <EmptyState v-else-if="!linkedColumns.length" message="该术语尚未绑定物理字段，未绑定字段的术语无法在 SQL 生成中定位。" />
            <ul v-else class="linked-list">
              <li v-for="column in linkedColumns" :key="column.entityId">
                <div>
                  <strong>{{ column.description || column.columnName }}</strong>
                  <small>{{ column.datasourceName }} / {{ column.tableName }} / {{ column.columnName }}</small>
                </div>
              </li>
            </ul>
          </section>
          <el-alert v-if="selectedTerm.status === 'PENDING_REVIEW'" :title="reviewChanges" type="info" :closable="false" />
          <!-- 状态门禁：动作以后端状态机为准 -->
          <div class="term-detail__actions">
            <template v-if="selectedTerm.status === 'DRAFT' || selectedTerm.status === 'REJECTED'">
              <el-button type="primary" :icon="Pencil" :disabled="!canManage" @click="openEditTerm(selectedTerm)">编辑</el-button>
              <el-button :icon="Send" :loading="actionLoading" :disabled="!canManage" @click="submitTerm(selectedTerm)">提交审核</el-button>
              <el-button type="danger" plain :icon="Trash2" :disabled="!canManage" @click="removeTerm(selectedTerm)">删除</el-button>
            </template>
            <template v-else-if="selectedTerm.status === 'PENDING_REVIEW'">
              <el-button type="primary" :icon="Check" :loading="actionLoading" :disabled="!canApprove" :title="canApprove ? '' : APPROVE_HINT" @click="review(selectedTerm, true)">审核通过</el-button>
              <el-button type="danger" plain :icon="X" :loading="actionLoading" :disabled="!canApprove" :title="canApprove ? '' : APPROVE_HINT" @click="review(selectedTerm, false)">审核拒绝</el-button>
            </template>
            <template v-else>
              <el-button :icon="RotateCcw" :loading="actionLoading" :disabled="!canManage" @click="revertTerm(selectedTerm)">退回草稿</el-button>
              <p class="term-detail__locked">
                已通过的术语不能直接修改，需先退回草稿。退回会清空审核人与审核时间——
                原审核结论不再代表修改后的内容，修改后必须重新提交审核。
              </p>
            </template>
          </div>

          <!-- 下一步：已通过的术语会参与查询改写，指向语义知识 -->
          <div v-if="selectedTerm.status === 'APPROVED'" class="term-detail__next">
            <span>术语已审核；字段定位和知识使用仍受来源有效性与当前权限约束。</span>
            <RouterLink class="term-detail__next-link" to="/admin/semantics/knowledge">
              查看语义知识 <ArrowRight :size="15" />
            </RouterLink>
          </div>

          <!-- 审核记录 -->
          <section class="term-detail__section">
            <h3>审核记录</h3>
            <dl class="term-detail__facts">
              <div><dt>审核状态</dt><dd>{{ glossaryTermStatusLabel(selectedTerm.status) }}</dd></div>
              <div><dt>审核人</dt><dd>{{ selectedTerm.reviewerId ? '用户 #' + selectedTerm.reviewerId : '—' }}</dd></div>
              <div><dt>审核时间</dt><dd>{{ selectedTerm.reviewedAt || '尚未审核' }}</dd></div>
            </dl>
            <el-alert v-if="needsReview && selectedTerm.status === 'APPROVED'" title="历史审核尚未包含可核验的完整字段映射，请退回草稿重新确认。" type="warning" :closable="false" />
            <el-timeline v-if="reviews.length">
              <el-timeline-item v-for="record in reviews" :key="record.id" :timestamp="record.createdAt">
                {{ ({ SUBMITTED: '提交审核', APPROVED: '审核通过', REJECTED: '审核拒绝', REVERTED: '退回草稿' } as Record<string, string>)[record.action] || record.action }} · 修订 {{ record.revision }} · 用户 #{{ record.actorId }}
                <p v-if="record.reason">{{ record.reason }}</p>
                <details><summary>本次审核的定义与映射</summary><p>{{ reviewSnapshot(record).description || '本次操作未修改业务定义' }}</p>
                  <ul><li v-for="column in reviewSnapshot(record).columns || []" :key="column.entityId">{{ column.datasourceName }} / {{ column.tableName }} / {{ column.columnName }}</li></ul></details>
              </el-timeline-item>
            </el-timeline>
            <p v-else>尚无定义与映射的修订审核记录。</p>
            <details><summary>技术标识</summary><p>内部名称：{{ selectedTerm.name }}</p><p>{{ selectedTerm.fqn }}</p></details>
          </section>


        </div>
      </aside>
      </div>
    </div>

    <!-- 术语分组对话框 -->
    <el-dialog v-model="glossaryDialogVisible" :title="editingGlossaryId ? '编辑术语分组' : '新建分组'" width="520px">
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

    <TermEditor v-if="selectedGlossaryId" ref="termEditor" :group-id="selectedGlossaryId" :sources="datasources" @saved="onTermSaved" />
  </div>
</template>

<style scoped>
.review-snapshot { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
.glossaries-page { display: grid; gap: 16px; }

/*
 * 左右分栏（开发指导 §7.10）：左＝术语分组列表，右＝当前术语分组的术语列表和详情。
 * 右栏内部再分「术语列表 | 术语详情」两个子列。
 */
.glossaries-layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.glossaries-main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 16px;
  align-items: start;
  min-width: 0;
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
.term-list__select small:first-of-type { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; font-size: 12px; }
.term-list__synonyms { color: var(--do-muted); font-size: 11px; }

.glossary-list__meta { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.glossary-list__actions { display: inline-flex; }

.term-column { min-width: 0; }
.term-toolbar { display: flex; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }

.term-detail { display: grid; gap: 16px; }
.term-detail__next {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px;
  border-radius: var(--do-radius-md);
  background: var(--do-success-soft);
  font-size: 12px;
  color: var(--do-muted);
}
.term-detail__next-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--do-primary-strong);
  font-size: 12px;
  font-weight: 800;
}
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
  .glossaries-layout { grid-template-columns: 240px minmax(0, 1fr); }
  .glossaries-main { grid-template-columns: minmax(0, 1fr) 340px; }
}

@media (max-width: 1180px) {
  .glossaries-main { grid-template-columns: minmax(0, 1fr); }
}

</style>
