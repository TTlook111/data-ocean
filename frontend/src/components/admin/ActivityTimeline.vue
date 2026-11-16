<script setup lang="ts">
defineProps<{
  items: Array<{ time?: string; operator?: string; action?: string; result?: string; description?: string }>
}>()
</script>

<template>
  <ol v-if="items.length" class="activity-timeline">
    <li v-for="(item, index) in items" :key="(item.time || 'unknown') + '-' + index">
      <span class="activity-timeline__dot"></span>
      <div>
        <strong>{{ item.action || item.description || '活动记录' }}</strong>
        <p v-if="item.result">{{ item.result }}</p>
        <small>{{ item.operator || '系统' }} · {{ item.time || '时间未知' }}</small>
      </div>
    </li>
  </ol>
  <p v-else class="activity-timeline__empty">暂无活动记录</p>
</template>

<style scoped>
.activity-timeline {
  display: grid;
  gap: 12px;
  margin: 0;
  padding: 0 0 0 6px;
  list-style: none;
}

li {
  position: relative;
  display: flex;
  gap: 10px;
}

.activity-timeline__dot {
  flex: 0 0 auto;
  width: 8px;
  height: 8px;
  margin-top: 5px;
  border: 0;
  border-radius: 50%;
  background: var(--do-primary);
  box-shadow: 0 0 0 3px var(--do-primary-soft);
}

li:not(:last-child)::after {
  position: absolute;
  top: 16px;
  bottom: -14px;
  left: 3px;
  width: 1px;
  background: var(--do-line);
  content: '';
}

strong {
  color: var(--do-ink);
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1.45;
}

p {
  margin: 2px 0 0;
  color: var(--do-ink);
  font-size: 11.5px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

small,
.activity-timeline__empty {
  display: block;
  margin-top: 2px;
  color: var(--do-muted);
  font-size: 11px;
  line-height: 1.5;
}
</style>
