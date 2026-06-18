<script setup lang="ts">
/**
 * 业务术语管理页面
 *
 * 包含术语表列表和术语条目管理，支持创建、编辑、审核流程。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Check, Send } from 'lucide-vue-next'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import {
  listGlossaries,
  createGlossary,
  updateGlossary,
  deleteGlossary,
  listTerms,
  createTerm,
  updateTerm,
  deleteTerm,
  submitTermForReview,
  reviewTerm,
  type GlossaryItem,
  type GlossaryTermItem,
} from '../../../api/admin/glossary'

const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)

const glossaries = ref<GlossaryItem[]>([])
const selectedGlossary = ref<GlossaryItem | null>(null)
const terms = ref<GlossaryTermItem[]>([])
const loading = ref(false)

// 术语表对话框
const glossaryDialogVisible = ref(false)
const glossaryForm = ref<Partial<GlossaryItem>>({})
const isEditingGlossary = ref(false)

// 术语对话框
const termDialogVisible = ref(false)
const termForm = ref<Partial<GlossaryTermItem>>({})
const isEditingTerm = ref(false)

const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  PENDING_REVIEW: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
}

async function loadGlossaries() {
  loading.value = true
  try {
    const res = await listGlossaries()
    glossaries.value = res.data ?? []
  } catch {
    ElMessage.error('术语表加载失败')
  } finally {
    loading.value = false
  }
}

async function loadTerms() {
  if (!selectedGlossary.value) return
  loading.value = true
  try {
    const res = await listTerms(selectedGlossary.value.id)
    terms.value = res.data ?? []
  } catch {
    ElMessage.error('术语加载失败')
  } finally {
    loading.value = false
  }
}

function selectGlossary(g: GlossaryItem) {
  selectedGlossary.value = g
  loadTerms()
}

function openCreateGlossary() {
  isEditingGlossary.value = false
  glossaryForm.value = { name: '', displayName: '', description: '' }
  glossaryDialogVisible.value = true
}

function openEditGlossary(g: GlossaryItem) {
  isEditingGlossary.value = true
  glossaryForm.value = { ...g }
  glossaryDialogVisible.value = true
}

async function saveGlossary() {
  try {
    if (isEditingGlossary.value && glossaryForm.value.id) {
      await updateGlossary(glossaryForm.value.id, glossaryForm.value)
      ElMessage.success('术语表更新成功')
    } else {
      await createGlossary(glossaryForm.value)
      ElMessage.success('术语表创建成功')
    }
    glossaryDialogVisible.value = false
    loadGlossaries()
  } catch {
    ElMessage.error('操作失败')
  }
}

async function handleDeleteGlossary(id: number) {
  await ElMessageBox.confirm('确定删除此术语表？', '确认')
  try {
    await deleteGlossary(id)
    ElMessage.success('已删除')
    if (selectedGlossary.value?.id === id) {
      selectedGlossary.value = null
      terms.value = []
    }
    loadGlossaries()
  } catch {
    ElMessage.error('删除失败')
  }
}

function openCreateTerm() {
  isEditingTerm.value = false
  termForm.value = { name: '', displayName: '', description: '', synonyms: '[]' }
  termDialogVisible.value = true
}

function openEditTerm(t: GlossaryTermItem) {
  isEditingTerm.value = true
  termForm.value = { ...t }
  termDialogVisible.value = true
}

async function saveTerm() {
  if (!selectedGlossary.value) return
  try {
    if (isEditingTerm.value && termForm.value.id) {
      await updateTerm(termForm.value.id, termForm.value)
      ElMessage.success('术语更新成功')
    } else {
      await createTerm(selectedGlossary.value.id, termForm.value)
      ElMessage.success('术语创建成功')
    }
    termDialogVisible.value = false
    loadTerms()
  } catch {
    ElMessage.error('操作失败')
  }
}

async function handleDeleteTerm(id: number) {
  await ElMessageBox.confirm('确定删除此术语？', '确认')
  try {
    await deleteTerm(id)
    ElMessage.success('已删除')
    loadTerms()
  } catch {
    ElMessage.error('删除失败')
  }
}

async function handleSubmitTerm(id: number) {
  try {
    await submitTermForReview(id)
    ElMessage.success('已提交审核')
    loadTerms()
  } catch {
    ElMessage.error('提交失败')
  }
}

async function handleReviewTerm(id: number, approved: boolean) {
  const action = approved ? '通过' : '拒绝'
  await ElMessageBox.confirm(`确定${action}此术语？`, '确认')
  try {
    await reviewTerm(id, approved)
    ElMessage.success(`术语已${action}`)
    loadTerms()
  } catch {
    ElMessage.error('审核失败')
  }
}

onMounted(() => {
  loadGlossaries()
  withContext(() => {
    reveal('.toolbar, .content-panel', { y: 14, stagger: 0.06 })
  })
})
</script>

<template>
  <main ref="pageRef" class="glossary-page post-login-page" v-loading="loading">

    <section class="toolbar">
      <el-button type="primary" :icon="Plus" @click="openCreateGlossary">新建术语表</el-button>
    </section>

    <div class="layout">
      <!-- 左侧：术语表列表 -->
      <aside class="sidebar">
        <h3>术语表</h3>
        <div v-if="glossaries.length === 0" class="empty-hint">暂无术语表</div>
        <div
          v-for="g in glossaries"
          :key="g.id"
          class="glossary-item"
          :class="{ active: selectedGlossary?.id === g.id }"
          @click="selectGlossary(g)"
        >
          <div class="glossary-name">{{ g.displayName || g.name }}</div>
          <div class="glossary-meta">
            <el-tag size="small" :type="g.status === 'PUBLISHED' ? 'success' : 'info'">
              {{ STATUS_LABELS[g.status] || g.status }}
            </el-tag>
            <el-button size="small" :icon="Edit" link @click.stop="openEditGlossary(g)" />
            <el-button size="small" :icon="Delete" link @click.stop="handleDeleteGlossary(g.id)" />
          </div>
        </div>
      </aside>

      <!-- 右侧：术语条目列表 -->
      <section class="content-panel">
        <div v-if="!selectedGlossary" class="empty-hint">
          请先选择术语表查看术语条目
        </div>
        <template v-else>
          <div class="term-toolbar">
            <h3>{{ selectedGlossary.displayName || selectedGlossary.name }} — 术语条目</h3>
            <el-button size="small" type="primary" :icon="Plus" @click="openCreateTerm">新增术语</el-button>
          </div>

          <el-table :data="terms" stripe size="small">
            <el-table-column prop="name" label="术语名" width="150" />
            <el-table-column prop="displayName" label="显示名" width="150" />
            <el-table-column prop="fqn" label="FQN" width="250" show-overflow-tooltip />
            <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="row.status === 'APPROVED' ? 'success' : row.status === 'REJECTED' ? 'danger' : 'warning'"
                >
                  {{ STATUS_LABELS[row.status] || row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <el-button size="small" :icon="Edit" link @click="openEditTerm(row)" />
                <el-button size="small" :icon="Delete" link @click="handleDeleteTerm(row.id)" />
                <el-button
                  v-if="row.status === 'DRAFT' || row.status === 'REJECTED'"
                  size="small"
                  :icon="Send"
                  link
                  title="提交审核"
                  @click="handleSubmitTerm(row.id)"
                />
                <el-button
                  v-if="row.status === 'PENDING_REVIEW'"
                  size="small"
                  :icon="Check"
                  link
                  title="审核通过"
                  @click="handleReviewTerm(row.id, true)"
                />
                <el-button
                  v-if="row.status === 'PENDING_REVIEW'"
                  size="small"
                  link
                  title="审核拒绝"
                  @click="handleReviewTerm(row.id, false)"
                />
              </template>
            </el-table-column>
          </el-table>
        </template>
      </section>
    </div>

    <!-- 术语表对话框 -->
    <el-dialog v-model="glossaryDialogVisible" :title="isEditingGlossary ? '编辑术语表' : '新建术语表'" width="500px">
      <el-form label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="glossaryForm.name" placeholder="英文标识，如 finance_metrics" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="glossaryForm.displayName" placeholder="中文名称，如 财务指标" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="glossaryForm.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="glossaryDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveGlossary">保存</el-button>
      </template>
    </el-dialog>

    <!-- 术语对话框 -->
    <el-dialog v-model="termDialogVisible" :title="isEditingTerm ? '编辑术语' : '新增术语'" width="600px">
      <el-form label-width="80px">
        <el-form-item label="术语名">
          <el-input v-model="termForm.name" placeholder="术语名称" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="termForm.displayName" placeholder="显示名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="termForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="同义词">
          <el-input
            v-model="termForm.synonyms"
            type="textarea"
            :rows="2"
            placeholder='JSON 数组，如 ["营收", "收入", "Revenue"]'
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="termDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveTerm">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.glossary-page {
  display: grid;
  gap: 16px;
  padding: 24px;
}




.toolbar {
  display: flex;
  gap: 12px;
}

.layout {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 16px;
}

.sidebar {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  padding: 16px;
  background: var(--do-surface);
}

.sidebar h3 {
  margin: 0 0 12px;
  font-size: 15px;
  color: var(--do-ink);
}

.glossary-item {
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.glossary-item:hover {
  background: var(--do-hover, #f5f7fa);
}

.glossary-item.active {
  background: var(--do-primary-soft, #eef8ff);
}

.glossary-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--do-ink);
  margin-bottom: 4px;
}

.glossary-meta {
  display: flex;
  align-items: center;
  gap: 4px;
}

.content-panel {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  padding: 16px;
  background: var(--do-surface);
}

.term-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.term-toolbar h3 {
  margin: 0;
  font-size: 15px;
  color: var(--do-ink);
}

.empty-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 200px;
  color: var(--do-muted);
  font-size: 14px;
}
</style>
