<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <main class="not-found-page">
    <h1>404</h1>
    <p>页面不存在或已被移除</p>
    <div class="actions">
      <button @click="router.push('/')">返回首页</button>
      <button @click="router.back()">返回上一页</button>
    </div>
  </main>
</template>

<style scoped>
.not-found-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  text-align: center;
  gap: 12px;
}

.not-found-page h1 {
  font-size: 72px;
  font-weight: 700;
  color: var(--do-muted, #999);
  margin: 0;
}

.not-found-page p {
  font-size: 16px;
  color: var(--do-ink, #333);
}

.actions {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}

.actions button {
  padding: 8px 20px;
  border: 1px solid var(--do-line, #e0e0e0);
  border-radius: 6px;
  background: var(--do-surface, #fff);
  cursor: pointer;
  font-size: 14px;
}

.actions button:hover {
  border-color: var(--do-primary, #4d8fdc);
  color: var(--do-primary, #4d8fdc);
}
</style>
