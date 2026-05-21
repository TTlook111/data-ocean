<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { GitCompare } from 'lucide-vue-next'
import { listSnapshots, diffSnapshots, type SnapshotItem, type SchemaDiffResult } from '../../../api/admin/metadata'

const loading = ref(false)
const snapshots = ref<SnapshotItem[]>([])
const oldId = ref<number>()
const newId = ref<number>()
const diffResult = ref<SchemaDiffResult | null>(null)

async function fetchSnapshots() {
  const res = await listSnapshots({ page: 1, size: 100 })
  snapshots.value = res.data?.records ?? []
}

async function handleCompare() {
  if (!oldId.value || !newId.value) {
    ElMessage.warning('请选择两个快照进行对比')
    return
  }
  if (oldId.value === newId.value) {
    ElMessage.warning('不能对比相同的快照')
    return
  }
  loading.value = true
  try {
    const res = await diffSnapshots(oldId.value, newId.value)
    diffResult.value = res.data
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '对比失败')
  } finally {
    loading.value = false
  }
}

onMounted(fetchSnapshots)
</script>

<template>
  <main class="snapshot-diff-page post-login-page">
    <header class="page-header">
      <div>
        <p>元数据管理</p>
        <h1>快照差异对比</h1>
        <span class="header-subtitle">对比两次快照的结构变更</span>
      </div>
    </header>

    <section class="compare-bar">
      <el-select v-model="oldId" placeholder="旧快照" style="width: 260px">
        <el-option v-for="s in snapshots" :key="s.id"
                   :label="`V${s.snapshotVersion} - ${s.datasourceName} (${s.createdAt})`" :value="s.id" />
      </el-select>
      <span style="color: var(--do-muted)">→</span>
      <el-select v-model="newId" placeholder="新快照" style="width: 260px">
        <el-option v-for="s in snapshots" :key="s.id"
                   :label="`V${s.snapshotVersion} - ${s.datasourceName} (${s.createdAt})`" :value="s.id" />
      </el-select>
      <el-button type="primary" :loading="loading" @click="handleCompare">
        <GitCompare :size="16" style="margin-right: 6px" />对比
      </el-button>
    </section>

    <section class="diff-result" v-if="diffResult">
      <div class="diff-section" v-if="diffResult.addedTables.length">
        <h4>新增表 ({{ diffResult.addedTables.length }})</h4>
        <el-tag v-for="t in diffResult.addedTables" :key="t" type="success" style="margin: 4px">{{ t }}</el-tag>
      </div>
      <div class="diff-section" v-if="diffResult.removedTables.length">
        <h4>删除表 ({{ diffResult.removedTables.length }})</h4>
        <el-tag v-for="t in diffResult.removedTables" :key="t" type="danger" style="margin: 4px">{{ t }}</el-tag>
      </div>
      <div class="diff-section" v-if="diffResult.addedColumns.length">
        <h4>新增字段 ({{ diffResult.addedColumns.length }})</h4>
        <el-table :data="diffResult.addedColumns" size="small" stripe>
          <el-table-column prop="tableName" label="表" width="180" />
          <el-table-column prop="columnName" label="字段" width="180" />
          <el-table-column prop="newType" label="类型" />
        </el-table>
      </div>
      <div class="diff-section" v-if="diffResult.removedColumns.length">
        <h4>删除字段 ({{ diffResult.removedColumns.length }})</h4>
        <el-table :data="diffResult.removedColumns" size="small" stripe>
          <el-table-column prop="tableName" label="表" width="180" />
          <el-table-column prop="columnName" label="字段" width="180" />
          <el-table-column prop="oldType" label="原类型" />
        </el-table>
      </div>
      <div class="diff-section" v-if="diffResult.modifiedColumns.length">
        <h4>变更字段 ({{ diffResult.modifiedColumns.length }})</h4>
        <el-table :data="diffResult.modifiedColumns" size="small" stripe>
          <el-table-column prop="tableName" label="表" width="160" />
          <el-table-column prop="columnName" label="字段" width="160" />
          <el-table-column prop="oldType" label="原类型" width="130" />
          <el-table-column prop="newType" label="新类型" width="130" />
          <el-table-column prop="oldComment" label="原注释" />
          <el-table-column prop="newComment" label="新注释" />
        </el-table>
      </div>
      <el-empty v-if="!diffResult.addedTables.length && !diffResult.removedTables.length &&
                       !diffResult.addedColumns.length && !diffResult.removedColumns.length &&
                       !diffResult.modifiedColumns.length"
                description="两个快照无差异" />
    </section>
  </main>
</template>

<style scoped>
.snapshot-diff-page { display: grid; gap: 16px; }
.page-header { }
.page-header p { font-size: 12px; color: var(--do-muted); margin: 0 0 4px; }
.page-header h1 { font-size: 22px; margin: 0; color: var(--do-ink); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }
.compare-bar { display: flex; align-items: center; gap: 12px; }
.diff-result { border: 1px solid var(--do-line); border-radius: 8px; background: var(--do-surface); padding: 20px; }
.diff-section { margin-bottom: 20px; }
.diff-section h4 { margin: 0 0 10px; font-size: 14px; color: var(--do-ink); }
</style>
