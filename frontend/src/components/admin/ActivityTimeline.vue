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
  gap: 14px;
  margin: 0;
  padding: 0 0 0 8px;
  list-style: none;
}

li {
  position: relative;
  display: flex;
  gap: 12px;
}

.activity-timeline__dot {
  flex: 0 0 auto;
  width: 10px;
  height: 10px;
  margin-top: 5px;
  border: 2px solid var(--do-primary);
  border-radius: 50%;
  background: var(--do-surface);
}

li:not(:last-child)::after {
  position: absolute;
  top: 15px;
  bottom: -14px;
  left: 4px;
  width: 1px;
  background: var(--do-line);
  content: '';
}

strong {
  color: var(--do-ink);
  font-size: 13px;
}

p {
  margin: 3px 0;
  color: var(--do-muted);
  font-size: 12px;
}

small,
.activity-timeline__empty {
  color: var(--do-muted);
  font-size: 12px;
}
</style>
