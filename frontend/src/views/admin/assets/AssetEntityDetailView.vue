<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { GitBranch, Network, ShieldCheck } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { confirmEntityTag, getEntityDetail, getEntityDownstream, getEntityLineage, getEntityTags, unconfirmEntityTag, type EntityDetail, type MetadataEntityItem, type MetadataRelationshipItem } from '../../../api/admin/catalog'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import ObjectContextSummary from '../../../components/admin/ObjectContextSummary.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const route = useRoute()
const loading = ref(true)
const error = ref('')
const detail = ref<EntityDetail | null>(null)
const lineage = ref<MetadataRelationshipItem[]>([])
const downstream = ref<MetadataRelationshipItem[]>([])
const tags = ref<MetadataEntityItem[]>([])
const newTag = ref('')
const entityId = Number(route.params.entityId)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [entityResult, lineageResult, downstreamResult, tagsResult] = await Promise.all([
      getEntityDetail(entityId),
      getEntityLineage(entityId),
      getEntityDownstream(entityId),
      getEntityTags(entityId),
    ])
    detail.value = entityResult.data
    lineage.value = lineageResult.data || []
    downstream.value = downstreamResult.data || []
    tags.value = tagsResult.data || []
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '资产详情加载失败'
  } finally {
    loading.value = false
  }
}

async function addTag() {
  if (!newTag.value.trim()) return
  await confirmEntityTag(entityId, newTag.value.trim())
  newTag.value = ''
  tags.value = (await getEntityTags(entityId)).data || []
  ElMessage.success('标签已确认')
}

async function removeTag(tag: MetadataEntityItem) {
  await unconfirmEntityTag(entityId, tag.name)
  tags.value = tags.value.filter((item) => item.id !== tag.id)
}

onMounted(load)
</script>

<template>
  <div class="admin-page entity-detail-page">
    <ObjectContextSummary
      v-if="detail"
      :title="detail.entity.displayName || detail.entity.name"
      :description="detail.entity.fqn"
      back-to="/admin/assets"
      source-label="当前已发布资产目录"
    />
    <TaskPageHeader title="资产详情" description="查看实体的技术信息、关系和下游影响，当前对象由 URL 锁定。" />
    <LoadingState v-if="loading" variant="skeleton" :rows="6" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else-if="detail">
      <section class="entity-detail__summary">
        <div><span>对象类型</span><strong>{{ detail.entity.entityType }}</strong></div>
        <div><span>版本</span><strong>v{{ detail.entity.version }}</strong></div>
        <div><span>关系数量</span><strong>{{ detail.outgoingRelations.length + detail.incomingRelations.length }}</strong></div>
      </section>
      <section class="entity-detail__card entity-detail__tags">
        <h2><ShieldCheck :size="17" />实体标签</h2>
        <div><el-tag v-for="tag in tags" :key="tag.id" closable @close="removeTag(tag)">{{ tag.displayName || tag.name }}</el-tag></div>
        <el-input v-model="newTag" placeholder="输入标签 FQN，如 PII.手机号" clearable @keyup.enter="addTag"><template #append><el-button @click="addTag">确认标签</el-button></template></el-input>
      </section>
      <div class="entity-detail__grid">
        <section class="entity-detail__card">
          <h2><GitBranch :size="17" />实体关系</h2>
          <el-table v-if="lineage.length" :data="lineage" size="small">
            <el-table-column prop="sourceType" label="来源类型" />
            <el-table-column prop="relationType" label="关系" />
            <el-table-column prop="targetType" label="目标类型" />
          </el-table>
          <EmptyState v-else message="当前实体暂无血缘关系" />
        </section>
        <section class="entity-detail__card">
          <h2><Network :size="17" />下游影响</h2>
          <el-table v-if="downstream.length" :data="downstream" size="small">
            <el-table-column prop="sourceId" label="来源实体" />
            <el-table-column prop="relationType" label="关系" />
            <el-table-column prop="targetId" label="下游实体" />
          </el-table>
          <EmptyState v-else message="当前实体暂无下游影响" />
        </section>
      </div>
      <el-alert class="entity-detail__notice" type="info" :closable="false">
        <template #title><ShieldCheck :size="16" /> 数据源内目录只展示正式发布快照同步后的资产。</template>
      </el-alert>
    </template>
  </div>
</template>

<style scoped>
.entity-detail__summary,
.entity-detail__grid {
  display: grid;
  gap: 14px;
}

.entity-detail__summary {
  grid-template-columns: repeat(3, 1fr);
  margin-bottom: 16px;
}

.entity-detail__summary > div,
.entity-detail__card {
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
}

.entity-detail__summary span {
  display: block;
  color: var(--do-muted);
  font-size: 12px;
}

.entity-detail__summary strong {
  display: block;
  margin-top: 6px;
  color: var(--do-ink);
  font-size: 20px;
}

.entity-detail__grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.entity-detail__card h2 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 12px;
  color: var(--do-ink);
  font-size: 15px;
}

.entity-detail__notice {
  margin-top: 16px;
}
.entity-detail__tags { margin-bottom: 14px; }
.entity-detail__tags > div { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }

@media (max-width: 760px) {
  .entity-detail__summary,
  .entity-detail__grid {
    grid-template-columns: 1fr;
  }
}
</style>
