<script setup lang="ts">
/**
 * IAM-SIMPLE-1 授权配置工作区
 *
 * 三个 Tab：数据授权 / 字段保护 / 实际权限。
 * 页面按 Java 返回的 S1 能力摘要决定 Tab 是否可用；无权限时给出明确中文提示，
 * 不静默展示空数据，也不回退旧权限。真正的校验始终由后端完成。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../../stores/auth'
import { positiveId } from '../../../utils/lineageContext'
import LoadingState from '../../../components/common/LoadingState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import { useIamS1Store } from '../../../stores/iamS1'
import IamS1DataGrantPanel from './iam/IamS1DataGrantPanel.vue'
import IamS1FieldProtectionPanel from './iam/IamS1FieldProtectionPanel.vue'
import IamS1EffectivePermissionPanel from './iam/IamS1EffectivePermissionPanel.vue'

const route = useRoute(),
  router = useRouter(),
  auth = useAuthStore()
const actorEpoch = ref(0)
const tabOf = (v: unknown) =>
  ['grants', 'protections', 'effective'].includes(String(v))
    ? (v as 'grants' | 'protections' | 'effective')
    : 'grants'
const iamS1 = useIamS1Store()
const activeTab = ref<'grants' | 'protections' | 'effective'>(tabOf(route.query.tab))

const canViewGrants = computed(
  () => iamS1.loaded && !iamS1.errorMessage && iamS1.hasGlobal('security:permission:view'),
)
/** 字段保护是资源级能力：全局码或任一负责源具备 `security:mask:view` 都允许进入。 */
const canViewProtections = computed(
  () =>
    iamS1.loaded &&
    !iamS1.errorMessage &&
    (iamS1.hasGlobal('security:mask:view') || iamS1.datasourcesWithFunction('security:mask:view').length > 0),
)
const canViewEffective = computed(
  () => iamS1.loaded && !iamS1.errorMessage && (iamS1.hasGlobal('security:effective:view') || iamS1.queryUse),
)

const datasourceIdFromContext = computed(() => positiveId(route.query.datasourceId))
const invalidDefaultDatasource = computed(
  () => route.query.datasourceId !== undefined && !datasourceIdFromContext.value,
)
function selectTab(value: string | number) {
  activeTab.value = tabOf(value)
  void router.replace({ query: { ...route.query, tab: activeTab.value } })
}
watch(
  () => route.query.tab,
  (value) => {
    activeTab.value = tabOf(value)
    ensureVisibleTab()
  },
)
watch(
  () => [auth.token, iamS1.snapshot, iamS1.loaded, iamS1.errorMessage],
  () => {
    actorEpoch.value++
  },
  { flush: 'sync' },
)
function ensureVisibleTab() {
  if (!iamS1.loaded || iamS1.errorMessage) return
  if (activeTab.value === 'grants' && !canViewGrants.value) {
    activeTab.value = canViewProtections.value ? 'protections' : 'effective'
  }
  if (activeTab.value === 'protections' && !canViewProtections.value) {
    activeTab.value = canViewEffective.value ? 'effective' : 'grants'
  }
  if (activeTab.value === 'effective' && !canViewEffective.value) {
    activeTab.value = canViewGrants.value ? 'grants' : 'protections'
  }
}

watch([canViewGrants, canViewProtections, canViewEffective], ensureVisibleTab)

onMounted(async () => {
  await iamS1.load(Boolean(iamS1.errorMessage))
  ensureVisibleTab()
})
</script>

<template>
  <div class="iam-s1-workspace">
    <TaskPageHeader
      title="授权配置"
      eyebrow="权限与组织 / 业务数据边界"
      description-mode="inline"
      description="用一份表单说明“谁能查哪些数据”：对象、数据范围、有效期或生效范围三项必选。字段未选不代表全部字段。"
    />

    <div class="iam-contract">
      <div>
        <small>功能权</small>
        <strong>能做什么</strong>
        <span>角色中的功能；问数、SQL和导出独立</span>
      </div>
      <div>
        <small>后台负责源</small>
        <strong>在哪里管理</strong>
        <span>功能与源必须来自同一角色绑定</span>
      </div>
      <div>
        <small>业务数据权</small>
        <strong>能查哪些数据</strong>
        <span>部门默认与显式授权；系统管理员不自动获得</span>
      </div>
    </div>
    <el-alert
      v-if="iamS1.errorMessage"
      type="error"
      show-icon
      :closable="false"
      :title="iamS1.errorMessage"
      description="无法确认当前账号的权限，请联系系统管理员检查角色绑定与负责数据源。"
    />

    <LoadingState v-if="!iamS1.loaded && !iamS1.errorMessage" text="正在核对当前身份与权限摘要…" />
    <el-tabs
      v-else-if="iamS1.loaded && !iamS1.errorMessage"
      :key="actorEpoch"
      :model-value="activeTab"
      @update:model-value="selectTab"
      class="workspace-tabs"
    >
      <el-tab-pane label="数据授权" name="grants">
        <IamS1DataGrantPanel
          v-if="activeTab === 'grants' && canViewGrants"
          :invalid-default-datasource="invalidDefaultDatasource"
          :default-datasource-id="datasourceIdFromContext"
        />
        <EmptyState
          v-else
          message="没有“查看授权配置”权限：需要角色拥有该功能，并且该角色负责目标数据源。直接调用接口同样会被后端拒绝。"
        />
      </el-tab-pane>

      <el-tab-pane label="字段保护" name="protections">
        <IamS1FieldProtectionPanel
          v-if="activeTab === 'protections' && canViewProtections"
          :default-datasource-id="datasourceIdFromContext"
          :invalid-default-datasource="invalidDefaultDatasource"
        />
        <EmptyState v-else message="没有“查看字段保护”权限：需要角色拥有该功能，并且该角色负责目标数据源。" />
      </el-tab-pane>

      <el-tab-pane label="实际权限" name="effective">
        <IamS1EffectivePermissionPanel
          v-if="activeTab === 'effective' && canViewEffective"
          :default-datasource-id="datasourceIdFromContext"
          :invalid-default-datasource="invalidDefaultDatasource"
        />
        <EmptyState
          v-else
          message="没有“查看用户实际权限”权限：查看他人需要该功能并负责目标数据源；本人可查看自己的业务权限。"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.iam-contract {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: 14px;
  background: var(--do-surface);
}
.iam-contract > div {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.iam-contract small {
  color: var(--do-primary);
  font-size: 11px;
}
.iam-contract strong {
  font-size: 17px;
  color: var(--do-ink);
}
.iam-contract span {
  font-size: 12px;
  line-height: 1.7;
  color: var(--do-muted);
}
@media (max-width: 760px) {
  .iam-contract {
    grid-template-columns: minmax(0, 1fr);
  }
}
.iam-s1-workspace {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.workspace-tabs {
  margin-top: 4px;
}
</style>
