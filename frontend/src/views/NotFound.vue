<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight } from 'lucide-vue-next'
import brandMark from '../assets/brand/dataocean-mark.png'
import { useAuthStore } from '../stores/auth'
import { useIamS1Store } from '../stores/iamS1'
import { resolveAdminEntry } from '../utils/adminNavigation'
const router = useRouter(),
  auth = useAuthStore(),
  iam = useIamS1Store()
onMounted(() => {
  if (auth.token) void iam.load()
})
const destination = computed(() => {
  if (!auth.token) return { path: '/login' }
  if (iam.loaded && !iam.errorMessage) {
    if (iam.queryUse) return { path: '/query' }
    const entry = resolveAdminEntry(iam)
    if (entry) return entry
  }
  return { path: '/profile' }
})
const label = computed(() =>
  destination.value.path === '/query'
    ? '返回问数'
    : destination.value.path === '/login'
      ? '前往登录'
      : destination.value.path === '/profile'
        ? '返回个人资料'
        : '返回有权工作区',
)
function back() {
  const previous = window.history.state?.back
  if (typeof previous === 'string' && previous.startsWith('/') && !previous.startsWith('//')) {
    const target = router.resolve(previous)
    if (target.matched.length && !target.matched.some((record) => record.path.includes(':pathMatch'))) {
      router.back()
      return
    }
  }
  void router.replace(destination.value)
}
</script>
<template>
  <main class="not-found-page">
    <section>
      <div class="brand">
        <img :src="brandMark" alt="" />
        <strong>DataOcean</strong>
      </div>
      <p class="eyebrow">页面未找到 · 404</p>
      <h1>这个地址没有对应页面</h1>
      <p class="description">
        链接可能已更新，或地址输入有误。你可以返回当前账号有权使用的工作区，再继续刚才的任务。
      </p>
      <div class="actions">
        <RouterLink :to="destination" class="primary">
          {{ label }}
          <ArrowRight :size="16" />
        </RouterLink>
        <button type="button" @click="back">
          <ArrowLeft :size="16" />
          返回上一页
        </button>
      </div>
      <RouterLink class="help" to="/guide/query">查看使用指南</RouterLink>
    </section>
    <span class="number" aria-hidden="true">404</span>
  </main>
</template>
<style scoped>
.not-found-page {
  min-height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 64px;
  padding: 48px 24px;
  background: var(--do-bg);
  color: var(--do-ink);
}
section {
  max-width: 520px;
  min-width: 0;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 56px;
}
.brand img {
  width: 24px;
  height: 38px;
  object-fit: contain;
}
.brand strong {
  font-size: 19px;
}
.eyebrow {
  color: var(--do-primary);
  font-size: 12px;
  font-weight: 600;
}
h1 {
  font-size: clamp(28px, 4vw, 38px);
  line-height: 1.3;
  letter-spacing: -1px;
  margin: 14px 0 20px;
}
.description {
  font-size: 15px;
  line-height: 1.9;
  color: var(--do-muted);
}
.number {
  font-family: monospace;
  font-size: clamp(100px, 18vw, 240px);
  letter-spacing: -20px;
  color: var(--do-line);
  user-select: none;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 28px;
}
.actions a,
.actions button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 12px 18px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  font-size: 13px;
  background: var(--do-surface);
  color: var(--do-ink);
  cursor: pointer;
}
.actions .primary {
  background: var(--do-primary);
  border-color: var(--do-primary);
  color: white;
}
.help {
  display: inline-block;
  margin-top: 24px;
  color: var(--do-muted);
  font-size: 12px;
}
a:focus-visible,
button:focus-visible {
  outline: 2px solid var(--do-primary);
  outline-offset: 4px;
}
@media (max-width: 760px) {
  .number {
    display: none;
  }
  .not-found-page {
    align-items: flex-start;
    padding-top: 48px;
  }
  .brand {
    margin-bottom: 44px;
  }
}
</style>
