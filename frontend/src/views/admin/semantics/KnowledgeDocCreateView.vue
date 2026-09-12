<script setup lang="ts">
/**
 * 手工新建知识文档
 *
 * 手工创建的文档没有来源快照和覆盖表，需要作者自行维护内容；
 * 基于快照生成请回到语义知识列表使用「AI 一键生成」。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Save } from 'lucide-vue-next'
import { createKnowledgeDoc } from '../../../api/admin/knowledge'
import type { DatasourceSimpleItem } from '../../../api/admin/datasource'
import { useAdminContextStore } from '../../../stores/adminContext'
import ObjectContextSummary from '../../../components/admin/ObjectContextSummary.vue'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const router = useRouter()
const context = useAdminContextStore()

const datasources = ref<DatasourceSimpleItem[]>([])
const loadingDatasources = ref(true)
/** 数据源加载失败的原因；此前 catch 静默吞掉，页面表现为「空下拉且无任何提示」 */
const datasourcesError = ref('')
const saving = ref(false)
const form = reactive({ datasourceId: undefined as number | undefined, title: '', content: '' })

function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

async function save() {
  if (!form.datasourceId) {
    ElMessage.warning('请选择数据源')
    return
  }
  if (!form.title.trim()) {
    ElMessage.warning('请填写文档标题')
    return
  }
  saving.value = true
  try {
    const result = await createKnowledgeDoc({
      datasourceId: form.datasourceId,
      title: form.title.trim(),
      content: form.content,
    })
    const id = result.data?.id
    ElMessage.success('文档已创建，接下来请编辑内容并提交审核')
    context.selectDatasource(form.datasourceId)
    if (id) {
      context.selectKnowledgeDoc(id)
      router.replace({ name: 'admin-semantic-knowledge-detail', params: { id }, query: { tab: 'content' } })
    } else {
      router.replace({ name: 'admin-semantic-knowledge' })
    }
  } catch (cause) {
    ElMessage.error(apiError(cause, '创建文档失败'))
  } finally {
    saving.value = false
  }
}

async function loadDatasources() {
  loadingDatasources.value = true
  datasourcesError.value = ''
  try {
    await context.initialize()
    datasources.value = context.datasources
    form.datasourceId = context.datasourceId
  } catch (cause) {
    datasources.value = []
    // 必须显式呈现失败：静默置空会让用户以为「系统里没有数据源」（§11.3、§18）
    datasourcesError.value = apiError(cause, '数据源加载失败')
  } finally {
    loadingDatasources.value = false
  }
}

onMounted(loadDatasources)
</script>

<template>
  <div class="admin-page knowledge-create-page">
    <ObjectContextSummary
      title="新建知识文档"
      description="手工创建，不绑定来源快照"
      back-to="/admin/semantics/knowledge"
      source-label="语义中心 / 语义知识"
    />

    <TaskPageHeader
      eyebrow="语义知识"
      title="新建知识文档"
      description="手工创建的文档需要自己维护内容与覆盖范围。基于已发布快照生成草稿会更省事，且能带上来源信息。"
    >
      <template #actions>
        <el-button type="primary" :icon="Save" :loading="saving" @click="save">创建文档</el-button>
      </template>
    </TaskPageHeader>

    <ErrorState v-if="datasourcesError" :message="datasourcesError" @retry="loadDatasources" />
    <LoadingState v-else-if="loadingDatasources" variant="skeleton" :rows="4" />
    <EmptyState
      v-else-if="!datasources.length"
      message="当前没有可用的数据源，无法创建知识文档。请先在数据接入中创建数据源并完成采集与快照发布。"
      action-text="去数据源接入"
      @action="router.push('/admin/data-sources')"
    />

    <section v-else class="knowledge-create-page__card">
      <el-form label-width="96px">
        <el-form-item label="数据源">
          <el-select v-model="form.datasourceId" placeholder="选择数据源" style="width: 320px">
            <el-option v-for="item in datasources" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="文档标题">
          <el-input v-model="form.title" placeholder="例如：订单域业务语义" style="width: 480px" />
        </el-form-item>
        <el-form-item label="初始内容">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="18"
            placeholder="可留空，创建后在详情页编写 skills.md 内容"
          />
        </el-form-item>
      </el-form>
      <p class="knowledge-create-page__hint">
        创建后文档处于
        <BusinessStatusBadge status="DRAFT" />
        状态。完整流程是：编辑内容 → 提交审核 → 审核通过 → 发布并构建索引。
        审核通过与索引入库是两个独立阶段，发布成功并完成索引后才可被检索。
      </p>
    </section>
  </div>
</template>

<style scoped>
.knowledge-create-page { display: grid; gap: 16px; }
.knowledge-create-page__card {
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}
.knowledge-create-page__hint { margin: 0; color: var(--do-muted); font-size: 12px; line-height: 1.7; }
</style>
