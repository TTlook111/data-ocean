<script setup lang="ts">
import { ArrowRight, CircleAlert } from 'lucide-vue-next'
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import type { DatasourceReadinessReason } from '../../api/admin/datasource'
import { findDomainHome, resolveReadinessActionPath } from '../../utils/adminNavigation'

const props = withDefaults(defineProps<{
  reasons?: DatasourceReadinessReason[]
  datasourceId?: number
  emptyText?: string
}>(), {
  reasons: () => [],
  emptyText: '当前没有阻断原因，可以继续检查下一阶段。',
})

const route = useRoute()

// actionPath 未映射时的安全落点：当前业务域的首个工作区。
// 不猜测目标，也不把 actionText 当作链接文案指向别处（文案与目标不符）。
const domainHome = computed(() => findDomainHome(String(route.meta.domainKey || '')))

const actions = computed(() => props.reasons.map((reason) => {
  const target = resolveReadinessActionPath(reason.actionPath, { datasourceId: props.datasourceId })
  return { reason, to: target.to, known: target.known }
}))
</script>

<template>
  <section class="next-action-card" :class="{ empty: !actions.length }">
    <div class="next-action-card__heading">
      <CircleAlert :size="18" />
      <div>
        <h2>{{ actions.length ? '需要处理的事项' : '下一步' }}</h2>
        <p>{{ actions.length ? '以下原因来自数据源就绪度接口。' : emptyText }}</p>
      </div>
    </div>

    <div v-if="actions.length" class="next-action-card__list">
      <article v-for="item in actions" :key="item.reason.code + '-' + item.reason.message" class="next-action-card__item">
        <div>
          <strong>{{ item.reason.message }}</strong>
          <span>责任角色：{{ item.reason.ownerRole || '未指定' }}</span>
        </div>
        <RouterLink v-if="item.known" class="next-action-card__action" :to="item.to">
          {{ item.reason.actionText || '去处理' }}
          <ArrowRight :size="15" />
        </RouterLink>
        <span v-else class="next-action-card__fallback">
          <em>{{ item.reason.actionText || '请手动处理' }}</em>
          <RouterLink class="next-action-card__action" :to="domainHome.path">
            返回{{ domainHome.label }}
            <ArrowRight :size="15" />
          </RouterLink>
        </span>
      </article>
    </div>
  </section>
</template>

<style scoped>
.next-action-card {
  padding: 20px;
  border: 1px solid rgba(220, 38, 38, .2);
  border-radius: var(--do-radius-lg);
  background: var(--do-danger-soft);
}

.next-action-card.empty {
  border-color: rgba(22, 163, 74, .2);
  background: var(--do-success-soft);
}

.next-action-card__heading {
  display: flex;
  gap: 10px;
  color: var(--do-danger);
}

.empty .next-action-card__heading {
  color: var(--do-success);
}

h2 {
  margin: 0;
  color: var(--do-ink);
  font-size: 16px;
}

p {
  margin: 4px 0 0;
  color: var(--do-muted);
  font-size: 12px;
}

.next-action-card__list {
  display: grid;
  gap: 8px;
  margin-top: 16px;
}

.next-action-card__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px;
  border: 1px solid rgba(220, 38, 38, .12);
  border-radius: var(--do-radius-md);
  background: rgba(255, 255, 255, .7);
}

.next-action-card__item div {
  display: grid;
  gap: 4px;
}

.next-action-card__item strong {
  color: var(--do-ink);
  font-size: 13px;
}

.next-action-card__item span {
  color: var(--do-muted);
  font-size: 12px;
}

.next-action-card__action {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 5px;
  color: var(--do-primary-strong);
  font-size: 12px;
  font-weight: 800;
}

.next-action-card__fallback {
  display: grid;
  flex: 0 0 auto;
  justify-items: end;
  gap: 5px;
}

.next-action-card__fallback em {
  color: var(--do-muted);
  font-size: 12px;
  font-style: normal;
}

</style>
