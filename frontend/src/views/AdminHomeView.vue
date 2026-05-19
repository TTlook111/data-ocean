<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function logout() {
  await auth.logout()
  await router.replace('/login')
}
</script>

<template>
  <main class="admin-home post-login-page">
    <section class="admin-panel">
      <p class="eyebrow">DataOcean</p>
      <h1>欢迎回来，{{ auth.user?.realName || auth.user?.username || '管理员' }}</h1>
      <p>用户模块已接入登录、JWT 会话、角色和部门基础能力。</p>
      <nav>
        <RouterLink to="/admin/users">用户管理</RouterLink>
        <RouterLink to="/admin/roles">角色管理</RouterLink>
        <RouterLink to="/admin/departments">部门管理</RouterLink>
        <RouterLink to="/admin/datasources">数据源管理</RouterLink>
        <RouterLink to="/query">问答端数据源</RouterLink>
        <RouterLink to="/profile">个人资料</RouterLink>
      </nav>
      <button type="button" @click="logout">退出登录</button>
    </section>
  </main>
</template>

<style scoped>
.admin-home {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 32px;
  background:
    linear-gradient(180deg, rgba(134, 210, 236, 0.42) 0%, rgba(255, 243, 214, 0.62) 46%, rgba(255, 250, 240, 0.98) 100%),
    linear-gradient(90deg, rgba(117, 169, 20, 0.18) 0%, transparent 30%, rgba(47, 127, 211, 0.16) 100%);
}

.admin-panel {
  width: min(560px, 100%);
  padding: 34px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
  backdrop-filter: blur(12px);
}

.eyebrow {
  margin: 0 0 10px;
  color: var(--do-leaf-deep);
  font-weight: 700;
}

h1 {
  margin: 0 0 12px;
  color: var(--do-ink);
}

p {
  color: var(--do-muted);
}

nav {
  display: flex;
  gap: 10px;
  margin-top: 22px;
  flex-wrap: wrap;
}

a {
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  padding: 0 14px;
  border-radius: 6px;
  color: var(--do-ink);
  background: var(--do-hydrangea-soft);
  font-weight: 800;
}

a:hover {
  color: #fff;
  background: var(--do-hydrangea);
}

button {
  height: 42px;
  padding: 0 18px;
  margin-top: 24px;
  border: 0;
  border-radius: 6px;
  color: #fff;
  background: var(--do-ink);
  cursor: pointer;
}
</style>
