<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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

async function fetchDepartments() {
  loading.value = true
  try {
    const result = await listDepartments()
    departments.value = result.data
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
  saving.value = true
  try {
    await createDepartment(form)
    ElMessage.success('部门创建成功')
    dialogVisible.value = false
    await fetchDepartments()
  } finally {
    saving.value = false
  }
}

async function removeDepartment(node: DepartmentNode) {
  await ElMessageBox.confirm(`确定删除部门「${node.deptName}」吗？`, '删除部门', {
    type: 'warning',
  })
  await deleteDepartment(node.id)
  ElMessage.success('部门已删除')
  await fetchDepartments()
}

onMounted(fetchDepartments)
</script>

<template>
  <main class="admin-page post-login-page">
    <header class="page-header">
      <div>
        <p>部门管理</p>
        <h1>组织结构</h1>
        <span class="header-subtitle">维护组织层级，供用户归属和权限范围使用。</span>
      </div>
      <el-button type="primary" @click="openCreate()">新增部门</el-button>
    </header>

    <el-tree
      v-loading="loading"
      class="department-tree"
      :data="departments"
      node-key="id"
      default-expand-all
      :props="{ label: 'deptName', children: 'children' }"
    >
      <template #default="{ data }">
        <div class="tree-node">
          <span>{{ data.deptName }}（{{ data.deptCode }}）</span>
          <span>
            <el-button link type="primary" @click.stop="openCreate(data)">新增下级</el-button>
            <el-button link type="danger" @click.stop="removeDepartment(data)">删除</el-button>
          </span>
        </div>
      </template>
    </el-tree>

    <el-dialog v-model="dialogVisible" title="新增部门" width="520px">
      <el-form label-width="90px">
        <el-form-item label="上级部门">
          <el-tree-select
            v-model="form.parentId"
            clearable
            check-strictly
            :data="departments"
            node-key="id"
            :props="{ label: 'deptName', children: 'children' }"
          />
        </el-form-item>
        <el-form-item label="部门名称" required>
          <el-input v-model="form.deptName" />
        </el-form-item>
        <el-form-item label="部门编码" required>
          <el-input v-model="form.deptCode" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
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
  margin-bottom: 18px;
}

.page-header p {
  margin: 0 0 6px;
  color: var(--do-primary);
  font-weight: 800;
}

.page-header h1 {
  margin: 0;
  font-size: 24px;
  color: var(--do-ink);
}

.department-tree {
  min-height: 420px;
  padding: 16px;
  border-radius: 8px;
  background: #fff;
  box-shadow: var(--do-shadow);
}

.tree-node {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
</style>
