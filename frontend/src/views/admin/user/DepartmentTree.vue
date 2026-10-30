<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  createDepartment,
  deleteDepartment,
  listDepartments,
  listUsers,
  updateDepartment,
  type DepartmentNode,
  type DepartmentPayload,
} from '../../../api/admin/user'
import { useIamWorkspaceSession } from '../../../composables/useIamWorkspaceSession'
import { trackGovernanceWrite, pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
import GovernanceWriteNotice from '../../../components/admin/GovernanceWriteNotice.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
const session = useIamWorkspaceSession(),
  iamS1 = session.iam
const selfId = computed(() => session.auth.currentUser?.id ?? session.auth.user?.userId),
  actorKey = computed(() => JSON.stringify([session.key.value, selfId.value])),
  owned = (k: string) => session.alive() && session.ready.value && actorKey.value === k
const canViewDepartments = computed(() => session.canGlobal('organization:department:view')),
  canManageDepartments = computed(
    () => canViewDepartments.value && session.canGlobal('organization:department:manage'),
  ),
  canReadMembers = computed(() => session.canGlobal('organization:user:view'))
const departments = ref<DepartmentNode[]>([]),
  loading = ref(false),
  treeVerified = ref(false),
  errorMessage = ref(''),
  keyword = ref(''),
  selectedId = ref<number>(),
  memberCounts = ref<Record<number, number>>({}),
  memberErrors = ref<Record<number, string>>({}),
  countLoading = ref(false),
  writeError = ref(''),
  saving = ref(false),
  dialogVisible = ref(false),
  editingId = ref<number>(),
  original = ref<DepartmentNode>()
const form = reactive<DepartmentPayload>({ parentId: undefined, deptName: '', deptCode: '', sortOrder: 0 })
const nodeMap = computed(() => flatten(departments.value)),
  selected = computed(() => (selectedId.value ? nodeMap.value.get(selectedId.value) : undefined))
const identity = () => session.auth.token || '',
  locked = computed(() =>
    pendingGovernanceWrites.value.some(
      (x) => x.identity === identity() && x.keys.includes('iam-department-tree'),
    ),
  )
let treeRequest = 0,
  countRequest = 0,
  editorEpoch = 0,
  operation = 0
function message(e: unknown, fallback: string) {
  return (e as any)?.response?.data?.message || (e instanceof Error ? e.message : fallback)
}
function flatten(rows: DepartmentNode[]) {
  if (!Array.isArray(rows)) throw new Error('部门树回执不是数组')
  const map = new Map<number, DepartmentNode>(),
    stack = rows.map((node) => ({ node, parent: undefined as number | undefined }))
  while (stack.length) {
    const { node: n, parent } = stack.pop()!
    if (
      !n ||
      !Number.isSafeInteger(n.id) ||
      n.id <= 0 ||
      map.has(n.id) ||
      typeof n.deptName !== 'string' ||
      typeof n.deptCode !== 'string' ||
      (n.parentId != null && (!Number.isSafeInteger(n.parentId) || n.parentId < 0)) ||
      (n.children !== undefined && !Array.isArray(n.children)) ||
      (parent !== undefined && n.parentId !== parent)
    )
      throw new Error('部门树回执重复、父级错属或字段不完整')
    map.set(n.id, n)
    stack.push(...(n.children || []).map((node) => ({ node, parent: n.id })))
  }
  for (const n of rows)
    if (n.parentId && map.has(n.parentId)) throw new Error('树根节点父级矛盾，未作为正常层级展示')
  return map
}
const parentOf = (n: DepartmentNode) => n.parentId || undefined
function base(n: DepartmentNode) {
  return JSON.stringify([n.id, parentOf(n), n.deptName, n.deptCode, n.sortOrder ?? null])
}
function prune(rows: DepartmentNode[], text: string, excluded = new Set<number>()): DepartmentNode[] {
  return rows.flatMap((n) => {
    if (excluded.has(n.id)) return []
    const children = prune(n.children || [], text, excluded)
    return !text ||
      `${n.deptName} ${n.deptCode} ${n.id}`.toLowerCase().includes(text.toLowerCase()) ||
      children.length
      ? [{ ...n, children }]
      : []
  })
}
const visibleTree = computed(() => prune(departments.value, keyword.value.trim()))
const parentOptions = computed(() => {
  const denied = new Set<number>()
  if (editingId.value) {
    const n = nodeMap.value.get(editingId.value)
    if (n) for (const id of flatten([n]).keys()) denied.add(id)
  }
  return prune(departments.value, '', denied)
})
function parentLabel(n: DepartmentNode) {
  const id = parentOf(n)
  return !id
    ? '顶层（无父部门）'
    : nodeMap.value.has(id)
      ? `${nodeMap.value.get(id)!.deptName} · #${id}`
      : `父部门 #${id}（本次启用树未返回）`
}
function resetForm() {
  editorEpoch++
  operation++
  editingId.value = undefined
  original.value = undefined
  Object.assign(form, { parentId: undefined, deptName: '', deptCode: '', sortOrder: 0 })
  saving.value = false
}
function closeEditor() {
  dialogVisible.value = false
  resetForm()
  session.cancelOwn()
}
watch(
  dialogVisible,
  (v) => {
    if (!v) resetForm()
  },
  { flush: 'sync' },
)
watch(
  selectedId,
  () => {
    operation++
    saving.value = false
    session.cancelOwn()
  },
  { flush: 'sync' },
)
async function initialize() {
  treeRequest++
  countRequest++
  closeEditor()
  departments.value = []
  selectedId.value = undefined
  memberCounts.value = {}
  memberErrors.value = {}
  countLoading.value = false
  treeVerified.value = false
  loading.value = false
  errorMessage.value = ''
  keyword.value = ''
  writeError.value = ''
  await fetchDepartments()
}
async function fetchDepartments() {
  const k = actorKey.value,
    r = ++treeRequest
  treeVerified.value = false
  errorMessage.value = ''
  countRequest++
  memberCounts.value = {}
  memberErrors.value = {}
  countLoading.value = false
  if (!canViewDepartments.value) {
    loading.value = false
    departments.value = []
    return
  }
  loading.value = true
  try {
    const result = await listDepartments()
    if (!owned(k) || r !== treeRequest) return
    const rows = result.data
    if (!Array.isArray(rows)) throw new Error('部门树回执不完整，不能确认为空树')
    const map = flatten(rows)
    departments.value = rows
    treeVerified.value = true
    if (selectedId.value && !map.has(selectedId.value)) selectedId.value = undefined
    void fetchMemberCounts()
  } catch (e) {
    if (owned(k) && r === treeRequest) {
      departments.value = []
      selectedId.value = undefined
      errorMessage.value = message(e, '部门树读取失败')
    }
  } finally {
    if (owned(k) && r === treeRequest) loading.value = false
  }
}
async function fetchMemberCounts() {
  const k = actorKey.value,
    r = ++countRequest,
    tree = treeRequest,
    ids = [...nodeMap.value.keys()]
  memberCounts.value = {}
  memberErrors.value = {}
  if (!treeVerified.value || !canReadMembers.value) {
    countLoading.value = false
    return
  }
  countLoading.value = true
  let index = 0
  await Promise.all(
    Array.from({ length: Math.min(6, ids.length) }, async () => {
      while (index < ids.length) {
        if (!owned(k) || r !== countRequest || tree !== treeRequest || !canReadMembers.value) return
        const id = ids[index++]!
        try {
          const result = await listUsers({ page: 1, pageSize: 1, departmentId: id })
          if (!owned(k) || r !== countRequest || tree !== treeRequest) return
          const count = result.data?.total
          if (!Number.isSafeInteger(count) || Number(count) < 0) throw new Error('直属人数回执不完整')
          memberCounts.value = { ...memberCounts.value, [id]: count }
        } catch (e) {
          if (!owned(k) || r !== countRequest || tree !== treeRequest || !canReadMembers.value) return
          if (owned(k) && r === countRequest && tree === treeRequest)
            memberErrors.value = { ...memberErrors.value, [id]: message(e, '直属人数不可用') }
        }
      }
    }),
  )
  if (owned(k) && r === countRequest && tree === treeRequest) countLoading.value = false
}
function choose(node: DepartmentNode) {
  const actual = nodeMap.value.get(node.id)
  if (actual) selectedId.value = actual.id
}
function openCreate(parent?: DepartmentNode) {
  if (
    !canManageDepartments.value ||
    saving.value ||
    locked.value ||
    !treeVerified.value ||
    (parent && nodeMap.value.get(parent.id) !== parent)
  )
    return
  resetForm()
  form.parentId = parent?.id
  dialogVisible.value = true
}
function openEdit(node: DepartmentNode) {
  if (
    !canManageDepartments.value ||
    saving.value ||
    locked.value ||
    !treeVerified.value ||
    nodeMap.value.get(node.id) !== node
  )
    return
  resetForm()
  editingId.value = node.id
  original.value = { ...node }
  Object.assign(form, {
    parentId: parentOf(node),
    deptName: node.deptName,
    deptCode: node.deptCode,
    sortOrder: node.sortOrder,
  })
  dialogVisible.value = true
}
async function saveDepartment() {
  if (
    !canManageDepartments.value ||
    saving.value ||
    locked.value ||
    !dialogVisible.value ||
    !treeVerified.value
  )
    return
  writeError.value = ''
  const id = editingId.value,
    parent = form.parentId || undefined,
    previous = original.value
  if (
    !form.deptName.trim() ||
    !form.deptCode.trim() ||
    !Number.isInteger(form.sortOrder) ||
    Number(form.sortOrder) < -2147483648 ||
    Number(form.sortOrder) > 2147483647
  ) {
    writeError.value = '请填写部门名、编码，并明确32位整数排序值（0可以使用）。'
    return
  }
  if (id && (!previous || previous.id !== id)) return
  if (id && parentOf(previous!) && !parent) {
    writeError.value = '已有上级暂不直接清空为顶层，请保留或改选有效父部门；原草稿保留。'
    return
  }
  const allowed = flatten(parentOptions.value)
  if (parent && !allowed.has(parent) && parent !== parentOf(previous || ({} as DepartmentNode))) {
    writeError.value = '父部门必须来自本次启用树，不能选择自身或下级。'
    return
  }
  const payload: DepartmentPayload = {
      parentId: parent,
      deptName: form.deptName.trim(),
      deptCode: form.deptCode.trim(),
      sortOrder: form.sortOrder,
    },
    k = actorKey.value,
    e = editorEpoch,
    o = ++operation,
    signature = JSON.stringify(form),
    actor = identity()
  saving.value = true
  try {
    const fresh = flatten((await listDepartments()).data)
    if (!owned(k) || e !== editorEpoch || o !== operation || !canManageDepartments.value) return
    if (id && (!fresh.has(id) || base(fresh.get(id)!) !== base(previous!))) {
      writeError.value = '部门已在其他会话变化或未返回启用树，原草稿保留；请核对后重开，不自动重新启用。'
      return
    }
    if (parent && !fresh.has(parent) && parent !== parentOf(previous || ({} as DepartmentNode))) {
      writeError.value = '父部门已变化，请核对后再提交。'
      return
    }
    if (id && parent && flatten([fresh.get(id)!]).has(parent)) {
      writeError.value = '父部门不能是自身或当前下级。'
      return
    }
    if (id && parent !== parentOf(previous!)) {
      await ElMessageBox.confirm(
        `部门 #${id} ${previous!.deptName}（${previous!.deptCode}）：父部门从 ${parentOf(previous!) ? '#' + parentOf(previous!) : '顶层'} 改为 ${parent ? '#' + parent : '顶层'}。会改变该部门及下级路径、含下级授权的主体匹配，不直接创建数据授权。`,
        '调整精确部门层级',
        {
          type: 'warning',
          confirmButtonText: '确认调整上级',
          cancelButtonText: '取消',
          cancelButtonClass: session.cancelClass,
          closeOnClickModal: false,
        },
      )
      if (!owned(k) || e !== editorEpoch || o !== operation || !canManageDepartments.value) return
    }
    await trackGovernanceWrite(
      actor,
      ['iam-department-tree'],
      `部门 ${id ? '#' + id : '新建 ' + payload.deptCode} / 父 ${parent ? '#' + parent : '顶层'} 保存`,
      async () => {
        if (id) return updateDepartment(id, payload)
        const result = await createDepartment(payload)
        if (!Number.isSafeInteger(result.data?.id) || Number(result.data?.id) <= 0)
          throw new Error('创建回执缺部门ID，结果未知，先核对部门树')
        return result
      },
    )
    if (!owned(k)) return
    if (e === editorEpoch && o === operation) {
      if (JSON.stringify(form) === signature) closeEditor()
      else {
        if (id && previous) original.value = { ...previous, ...payload, id }
        writeError.value = '原部门变更已提交，新的输入保留。'
      }
    }
    await fetchDepartments()
  } catch (cause) {
    if (owned(k) && e === editorEpoch && o === operation && cause !== 'cancel' && cause !== 'close')
      writeError.value = message(cause, '保存失败；409保留草稿，结果未知先核对部门树')
  } finally {
    if (owned(k) && e === editorEpoch && o === operation) saving.value = false
  }
}
async function removeDepartment(node: DepartmentNode) {
  if (
    !canManageDepartments.value ||
    saving.value ||
    locked.value ||
    !treeVerified.value ||
    nodeMap.value.get(node.id) !== node
  )
    return
  writeError.value = ''
  if (node.children?.length || Number(memberCounts.value[node.id]) > 0) {
    writeError.value = '非空部门禁止删除；不会级联删除下级或用户。'
    return
  }
  const k = actorKey.value,
    o = ++operation,
    id = node.id,
    actor = identity()
  saving.value = true
  try {
    await ElMessageBox.confirm(
      `只删除部门 #${id} ${node.deptName}（${node.deptCode}），父：${parentLabel(node)}。系统会再次核对非空部门限制；没有级联删除。成功时撤回该部门ACTIVE授权，页面不提供恢复。`,
      '删除精确空部门',
      {
        type: 'warning',
        confirmButtonText: '确认删除部门',
        cancelButtonText: '取消',
        cancelButtonClass: session.cancelClass,
        closeOnClickModal: false,
      },
    )
    if (!owned(k) || o !== operation || !canManageDepartments.value || nodeMap.value.get(id) !== node) return
    const fresh = flatten((await listDepartments()).data)
    if (!owned(k) || o !== operation || !canManageDepartments.value) return
    const current = fresh.get(id)
    if (!current || base(current) !== base(node) || current.children?.length) {
      writeError.value = '目标部门已变化或出现下级，未删除，请重读核对。'
      return
    }
    await trackGovernanceWrite(actor, ['iam-department-tree'], `删除部门 #${id} ${node.deptCode}`, () =>
      deleteDepartment(id),
    )
    if (owned(k)) await fetchDepartments()
  } catch (cause) {
    if (owned(k) && o === operation && cause !== 'cancel' && cause !== 'close')
      writeError.value = message(cause, '删除失败；结果未知先核对原部门')
  } finally {
    if (owned(k) && o === operation) saving.value = false
  }
}
watch(actorKey, initialize, { immediate: true, flush: 'sync' })
</script>
<template>
  <main class="department-workspace">
    <GovernanceWriteNotice />
    <div class="department-heading">
      <div>
        <h3>组织层级与主部门</h3>
        <p>
          部门路径参与主体匹配；组织调整不直接建立数据授权。这里展示本次读取的启用部门树，未返回的部门不能推断为已删除。
        </p>
      </div>
      <div class="actions">
        <el-button @click="fetchDepartments">重读部门树</el-button>
        <el-button
          type="primary"
          :disabled="!canManageDepartments || !treeVerified || saving || locked"
          @click="openCreate()"
        >
          新增顶层部门
        </el-button>
      </div>
    </div>
    <p v-if="writeError" class="notice" role="status">{{ writeError }}</p>
    <section class="summary">
      <span>
        本次返回启用部门
        <strong>{{ treeVerified ? nodeMap.size : '—' }}</strong>
      </span>
      <span>
        树根节点
        <strong>{{ treeVerified ? departments.length : '—' }}</strong>
      </span>
      <span>
        人数为直属用户，不含下级；{{
          canReadMembers
            ? countLoading
              ? '正在读取'
              : Object.keys(memberErrors).length
                ? '部分人数不可用'
                : '本次读取完成'
            : '缺少organization:user:view，未读取'
        }}
        <el-button
          v-if="canReadMembers && treeVerified"
          link
          :loading="countLoading"
          @click="fetchMemberCounts"
        >
          重读人数
        </el-button>
      </span>
    </section>
    <ErrorState v-if="iamS1.errorMessage" :message="iamS1.errorMessage" @retry="iamS1.load(true)" />
    <LoadingState v-else-if="!iamS1.loaded || loading" text="正在读取启用部门树…" />
    <ErrorState v-else-if="errorMessage" :message="errorMessage" @retry="fetchDepartments" />
    <EmptyState
      v-else-if="!canViewDepartments"
      message="没有organization:department:view，不能读取部门树。"
    />
    <EmptyState v-else-if="!departments.length" message="本次未返回启用部门。" />
    <div v-else class="department-grid">
      <section class="panel">
        <label class="tree-search">
          定位部门
          <el-input v-model="keyword" clearable placeholder="搜索名称、编码或ID" />
        </label>
        <el-tree
          :data="visibleTree"
          node-key="id"
          default-expand-all
          highlight-current
          :current-node-key="selectedId"
          :props="{ label: 'deptName', children: 'children' }"
          @node-click="choose"
        >
          <template #default="{ data }">
            <div class="tree-node">
              <strong>{{ data.deptName }}</strong>
              <small>{{ data.deptCode }} · #{{ data.id }}</small>
              <span>{{ memberCounts[data.id] ?? '—' }} 人</span>
            </div>
          </template>
        </el-tree>
        <p v-if="!visibleTree.length">返回树中无匹配项；搜索保留命中节点的父级路径。</p>
      </section>
      <section v-if="selected" class="panel department-detail">
        <small>精确部门 #{{ selected.id }}</small>
        <h3>{{ selected.deptName }}</h3>
        <dl>
          <dt>编码</dt>
          <dd>{{ selected.deptCode }}</dd>
          <dt>上级</dt>
          <dd>{{ parentLabel(selected) }}</dd>
          <dt>直属下级</dt>
          <dd>{{ selected.children?.length ?? 0 }} 个（本次启用树）</dd>
          <dt>排序</dt>
          <dd>{{ selected.sortOrder ?? '未记录' }}</dd>
          <dt>直属用户</dt>
          <dd>
            {{
              memberCounts[selected.id] === undefined ? '未读取或不可用' : memberCounts[selected.id] + ' 人'
            }}
            <small v-if="memberErrors[selected.id]">{{ memberErrors[selected.id] }}</small>
          </dd>
        </dl>
        <div class="actions">
          <el-button
            type="primary"
            :disabled="!canManageDepartments || saving || locked"
            @click="openCreate(selected)"
          >
            新增下级
          </el-button>
          <el-button :disabled="!canManageDepartments || saving || locked" @click="openEdit(selected)">
            编辑部门
          </el-button>
          <el-button
            type="danger"
            :disabled="
              !canManageDepartments ||
              saving ||
              locked ||
              !!selected.children?.length ||
              Number(memberCounts[selected.id]) > 0
            "
            @click="removeDepartment(selected)"
          >
            删除部门
          </el-button>
        </div>
        <p>
          非空部门不能删除，不会级联删除下级或用户；用户数未知时由系统核对。层级调整保存前会重读目标，最终并发校验仍以服务端为准。
        </p>
      </section>
      <section v-else class="panel">
        <EmptyState message="从左侧选择具体部门，查看直属人数与维护动作。" />
      </section>
    </div>
    <el-dialog
      v-model="dialogVisible"
      :title="
        editingId
          ? '编辑部门 #' + editingId
          : form.parentId
            ? '新增下级 · 父 #' + form.parentId
            : '创建顶层部门'
      "
      width="min(620px,94vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <p v-if="writeError" class="notice" role="status">{{ writeError }}</p>
      <div class="form-grid">
        <label>
          上级部门
          <el-tree-select
            v-model="form.parentId"
            :clearable="!original?.parentId"
            check-strictly
            :data="parentOptions"
            node-key="id"
            :props="{ label: 'deptName', children: 'children' }"
            :disabled="saving"
            placeholder="留空创建顶层；已存在上级暂不直接清空"
          />
        </label>
        <label>
          部门名称
          <el-input v-model="form.deptName" :disabled="saving" placeholder="明确部门名称" />
        </label>
        <label>
          部门编码
          <el-input v-model="form.deptCode" :disabled="saving" placeholder="唯一部门编码" />
        </label>
        <label>
          排序（整数，0可用）
          <el-input-number
            v-model="form.sortOrder"
            :precision="0"
            :min="-2147483648"
            :max="2147483647"
            :disabled="saving"
          />
        </label>
      </div>
      <p>保存不新增数据授权。已有上级暂不支持直接设为顶层；可以保留或改选其他有效父部门。</p>
      <template #footer>
        <el-button :disabled="saving" @click="closeEditor">取消</el-button>
        <el-button type="primary" :disabled="locked" :loading="saving" @click="saveDepartment">
          保存部门
        </el-button>
      </template>
    </el-dialog>
  </main>
</template>
<style scoped>
.department-workspace {
  display: grid;
  gap: 20px;
}
.department-heading,
.actions,
.summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.department-heading h3 {
  margin: 0;
  font-size: 18px;
}
.department-heading > div:first-child {
  max-width: 730px;
}
.department-heading p,
.panel p,
.summary {
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.8;
}
.summary {
  justify-content: flex-start;
  padding: 12px 0;
  border-top: 1px solid var(--do-line);
  border-bottom: 1px solid var(--do-line);
}
.summary strong {
  font-size: 17px;
  margin-left: 8px;
  color: var(--do-ink);
}
.department-grid {
  display: grid;
  grid-template-columns: minmax(260px, 0.9fr) minmax(0, 1.2fr);
  gap: 20px;
  align-items: start;
}
.panel {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  min-width: 0;
}
.tree-search {
  display: grid;
  gap: 8px;
  font-size: 12px;
  color: var(--do-muted);
  margin-bottom: 18px;
}
.tree-node {
  display: flex;
  gap: 10px;
  align-items: center;
  min-width: 0;
  width: 100%;
  font-size: 13px;
}
.tree-node strong {
  overflow: hidden;
  text-overflow: ellipsis;
}
.tree-node small {
  color: var(--do-muted);
  font-size: 11px;
}
.tree-node > span {
  margin-left: auto;
  color: var(--do-muted);
  font-size: 12px;
  white-space: nowrap;
}
.panel :deep(.el-tree-node__content) {
  height: 40px;
}
.department-detail > small {
  color: var(--do-primary-strong);
}
.department-detail h3 {
  font-size: 21px;
  margin: 10px 0 22px;
}
.department-detail dl {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 16px;
  font-size: 13px;
  line-height: 1.7;
  margin-bottom: 26px;
}
.department-detail dt {
  color: var(--do-muted);
}
.department-detail dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.department-detail dd small {
  display: block;
  font-size: 12px;
  color: var(--do-muted);
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}
.form-grid label {
  display: grid;
  gap: 8px;
  font-size: 12px;
  color: var(--do-muted);
}
.form-grid .el-input-number {
  width: 100%;
}
.notice {
  padding: 12px;
  background: var(--do-bg);
  border-left: 3px solid var(--do-primary);
  font-size: 13px;
  line-height: 1.8;
}
@media (max-width: 950px) {
  .department-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 600px) {
  .panel {
    padding: 14px;
  }
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .tree-node {
    flex-wrap: wrap;
    gap: 4px;
  }
  .tree-node small {
    font-size: 10px;
  }
  .department-detail dl {
    grid-template-columns: 80px minmax(0, 1fr);
  }
}
</style>
