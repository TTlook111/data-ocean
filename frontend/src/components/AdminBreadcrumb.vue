<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { ChevronRight } from 'lucide-vue-next'

const route = useRoute()

interface BreadcrumbItem {
  label: string
  to?: string
}

const breadcrumbs = computed<BreadcrumbItem[]>(() => {
  const items: BreadcrumbItem[] = []

  const section = route.meta.section as string | undefined
  const currentTitle = route.meta.title as string | undefined

  // 只在三级页面（有路由参数或 matched 深度 > 2）时才显示面包屑
  const hasParams = Object.keys(route.params).length > 0
  const isDeepPage = route.matched.length > 2
  if (!hasParams && !isDeepPage) return items

  // 第一级：section（来自 meta.section）
  if (section) {
    items.push({ label: section })
  }

  // 中间级：从 matched 路由中提取有 title 的父级
  const matched = route.matched
  for (let i = 0; i < matched.length - 1; i++) {
    const record = matched[i]
    const title = record.meta?.title as string | undefined
    if (title && title !== section) {
      items.push({ label: title, to: record.path || undefined })
    }
  }

  // 最后一级：当前路由的 title
  if (currentTitle) {
    items.push({ label: currentTitle })
  }

  return items
})

</script>

<template>
  <nav v-if="breadcrumbs.length > 1" class="admin-breadcrumb" aria-label="面包屑导航">
    <el-breadcrumb :separator-icon="ChevronRight">
      <el-breadcrumb-item
        v-for="(item, index) in breadcrumbs"
        :key="index"
        :to="item.to && index < breadcrumbs.length - 1 ? { path: item.to } : undefined"
      >
        {{ item.label }}
      </el-breadcrumb-item>
    </el-breadcrumb>
  </nav>
</template>

<style scoped>
.admin-breadcrumb {
  padding: 10px 16px 0;
  background: #fff;
}

.admin-breadcrumb :deep(.el-breadcrumb) {
  font-size: 13px;
  line-height: 1.4;
}

.admin-breadcrumb :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: var(--do-ink);
  font-weight: 500;
}

.admin-breadcrumb :deep(.el-breadcrumb__separator) {
  display: inline-flex;
  align-items: center;
}
</style>
