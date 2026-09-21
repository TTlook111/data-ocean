<script setup lang="ts">
/**
 * IAM-SIMPLE-1 授权配置工作区
 *
 * 三个 Tab：数据授权 / 字段保护 / 实际权限。
 * 页面按 Java 返回的 S1 能力摘要决定 Tab 是否可用；无权限时给出明确中文提示，
 * 不静默展示空数据，也不回退旧权限。真正的校验始终由后端完成。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import EmptyState from '../../../components/common/EmptyState.vue'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import { useIamS1Store } from '../../../stores/iamS1'
import IamS1DataGrantPanel from './iam/IamS1DataGrantPanel.vue'
import IamS1FieldProtectionPanel from './iam/IamS1FieldProtectionPanel.vue'
import IamS1EffectivePermissionPanel from './iam/IamS1EffectivePermissionPanel.vue'

const route = useRoute()
const iamS1 = useIamS1Store()
const activeTab = ref<'grants' | 'protections' | 'effective'>('grants')

const canViewGrants = computed(() => iamS1.hasGlobal('security:permission:view'))
const canViewProtections = computed(() => iamS1.hasGlobal('security:mask:view'))
const canViewEffective = computed(() => iamS1.hasGlobal('security:effective:view') || iamS1.queryUse)

const datasourceIdFromContext = computed(() => {
  const raw = route.query.datasourceId
  const parsed = typeof raw === 'string' ? Number(raw) : NaN
  return Number.isFinite(parsed) ? parsed : undefined
})

function ensureVisibleTab() {
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
  await iamS1.load()
  ensureVisibleTab()
})
</script>

<template>
  <div class="iam-s1-workspace">
    <TaskPageHeader
      title="授权配置"
      description="用一份表单说明“谁能查哪些数据”：对象、数据范围、有效期或生效范围三项必选。字段未选不代表全部字段。"
    />

    <el-alert
      v-if="iamS1.errorMessage"
      type="error"
      show-icon
      :closable="false"
      :title="iamS1.errorMessage"
      description="无法确认当前账号的 IAM-SIMPLE-1 能力，请联系系统管理员检查角色绑定与负责数据源。"
    />

    <el-tabs v-model="activeTab" class="workspace-tabs">
      <el-tab-pane label="数据授权" name="grants">
        <IamS1DataGrantPanel v-if="canViewGrants" :default-datasource-id="datasourceIdFromContext" />
        <EmptyState
          v-else
          message="没有“查看授权配置”权限：需要 IAM-SIMPLE-1 角色包含该功能，并且该角色负责目标数据源。直接调用接口同样会被后端拒绝。"
        />
      </el-tab-pane>

      <el-tab-pane label="字段保护" name="protections">
        <IamS1FieldProtectionPanel v-if="canViewProtections" />
        <EmptyState
          v-else
          message="没有“查看字段保护”权限：需要 IAM-SIMPLE-1 角色包含该功能，并且该角色负责目标数据源。"
        />
      </el-tab-pane>

      <el-tab-pane label="实际权限" name="effective">
        <IamS1EffectivePermissionPanel v-if="canViewEffective" />
        <EmptyState
          v-else
          message="没有“查看用户实际权限”权限：查看他人需要该功能并负责目标数据源；本人可查看自己的业务权限。"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.iam-s1-workspace {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.workspace-tabs {
  margin-top: 4px;
}
</style>
