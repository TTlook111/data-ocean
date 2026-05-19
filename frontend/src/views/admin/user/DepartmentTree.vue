<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Building2, FolderPlus, Trash2 } from 'lucide-vue-next'
import {
  createDepartment,
  deleteDepartment,
  listDepartments,
  type DepartmentNode,
  type DepartmentPayload,
} from '../../../api/admin/user'

const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const departments = ref<DepartmentNode[]>([])
const form = reactive<DepartmentPayload>({
  parentId: undefined,
  deptName: '',
  deptCode: '',
  sortOrder: 0,
})

function extractError(error: unknown, fallback: string) {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const msg = (error as { response?: { data?: { message?: string } } }).response?.data?.message
    if (typeof msg === 'string') return msg
  }
  return fallback
}

async function fetchDepartments() {
  loading.value = true
  try {
    const result = await listDepartments()
    departments.value = result.data
  } catch (error) {
    ElMessage.error(extractError(error, '部门数据加载失败'))
  } finally {
    loading.value = false
  }
}

function openCreate(parent?: DepartmentNode) {
  Object.assign(form, {
    parentId: parent?.id,
    deptName: '',
    deptCode: '',
    sortOrder: 0,
  })
  dialogVisible.value = true
}

async function saveDepartment() {
  if (!form.deptName.trim() || !form.deptCode.trim()) {
    ElMessage.warning('请填写部门名称和编码')
    return
  }
  saving.value = true
  try {
    await createDepartment(form)
    ElMessage.success('部门创建成功')
    dialogVisible.value = false
    await fetchDepartments()
  } catch (error) {
    ElMessage.error(extractError(error, '部门创建失败'))
  } finally {
    saving.value = false
  }
}

async function removeDepartment(node: DepartmentNode) {
  await ElMessageBox.confirm(`确定删除部门「${node.deptName}」吗？如有下级部门将一并删除。`, '删除部门', {
    type: 'warning',
    confirmButtonText: '确认删除',
    cancelButtonText: '取消',
  })
  try {
    await deleteDepartment(node.id)
    ElMessage.success('部门已删除')
    await fetchDepartments()
  } catch (error) {
    ElMessage.error(extractError(error, '部门删除失败'))
  }
}

function nodeCount(nodes: DepartmentNode[]): number {
  return nodes.reduce((sum, n) => sum + 1 + (n.children ? nodeCount(n.children) : 0), 0)
}

onMounted(fetchDepartments)
</script>

<template>
  <main class="admin-page post-login-page">
    <header class="page-header">
      <div>
        <p>治理管理</p>
        <h1>部门管理</h1>
        <span class="header-subtitle">维护组织层级结构，供用户归属和权限范围使用。</span>
      </div>
      <el-button type="primary" @click="openCreate()">
        <FolderPlus :size="16" />
        新增部门
      </el-button>
    </header>

    <section class="dept-stats">
      <article class="dept-stat-card">
        <span class="dept-stat-icon">
          <Building2 :size="18" />
        </span>
        <div>
          <strong>{{ nodeCount(departments) }}</strong>
          <small>部门总数</small>
        </div>
      </article>
      <article class="dept-stat-card">
        <span class="dept-stat-icon green">
          <FolderPlus :size="18" />
        </span>
        <div>
          <strong>{{ departments.length }}</strong>
          <small>顶级部门</small>
        </div>
      </article>
    </section>

    <section class="tree-panel">
      <el-skeleton v-if="loading && !departments.length" :rows="6" animated style="padding:18px" />

      <el-empty v-else-if="!departments.length && !loading" description="暂无部门数据，点击右上角按钮创建" />

      <el-tree
        v-else
        v-loading="loading"
        :data="departments"
        node-key="id"
        default-expand-all
        :props="{ label: 'deptName', children: 'children' }"
      >
        <template #default="{ data }">
          <div class="tree-node">
            <span class="tree-node-label">
              <Building2 :size="14" />
              <strong>{{ data.deptName }}</strong>
              <small>{{ data.deptCode }}</small>
            </span>
            <span class="tree-node-actions">
              <el-button link type="primary" size="small" @click.stop="openCreate(data)">
                <FolderPlus :size="14" />
                新增下级
              </el-button>
              <el-button link type="danger" size="small" @click.stop="removeDepartment(data)">
                <Trash2 :size="14" />
                删除
              </el-button>
            </span>
          </div>
        </template>
      </el-tree>
    </section>

    <el-dialog v-model="dialogVisible" title="新增部门" width="520px">
      <el-form label-width="90px" :disabled="saving">
        <el-form-item label="上级部门">
          <el-tree-select
            v-model="form.parentId"
            clearable
            check-strictly
            :data="departments"
            node-key="id"
            placeholder="留空则为顶级部门"
            :props="{ label: 'deptName', children: 'children' }"
          />
        </el-form-item>
        <el-form-item label="部门名称" required>
          <el-input v-model="form.deptName" placeholder="例如：技术部" />
        </el-form-item>
        <el-form-item label="部门编码" required>
          <el-input v-model="form.deptCode" placeholder="例如：TECH" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveDepartment">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.admin-page {
  display: grid;
  gap: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.page-header p {
  margin: 0 0 6px;
  color: var(--do-primary);
  font-size: 13px;
  font-weight: 900;
}

.page-header h1 {
  margin: 0;
  font-size: 24px;
  color: var(--do-ink);
}

.dept-stats {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}

.dept-stat-card {
  display: grid;
  grid-template-columns: 40px 1fr;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.dept-stat-icon {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: var(--do-radius);
  color: var(--do-tone-blue);
  background: var(--do-tone-blue-bg);
}

.dept-stat-icon.green {
  color: var(--do-tone-green);
  background: var(--do-tone-green-bg);
}

.dept-stat-card strong {
  display: block;
  font-size: 18px;
  color: var(--do-ink);
}

.dept-stat-card small {
  color: var(--do-muted);
  font-size: 12px;
}

.tree-panel {
  min-height: 380px;
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.tree-node {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 0;
}

.tree-node-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--do-ink);
}

.tree-node-label strong {
  font-size: 14px;
}

.tree-node-label small {
  color: var(--do-muted);
  font-size: 12px;
}

.tree-node-actions {
  display: inline-flex;
  gap: 4px;
  opacity: 0;
  transition: opacity 120ms;
}

.tree-node:hover .tree-node-actions {
  opacity: 1;
}
</style>
