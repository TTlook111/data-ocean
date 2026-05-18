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
  <main class="admin-home">
    <section class="admin-panel">
      <p class="eyebrow">DataOcean</p>
      <h1>欢迎回来，{{ auth.user?.realName || auth.user?.username || '管理员' }}</h1>
      <p>用户模块已接入登录、JWT 会话、角色和部门基础能力。</p>
      <nav>
        <RouterLink to="/admin/users">用户管理</RouterLink>
        <RouterLink to="/admin/roles">角色管理</RouterLink>
        <RouterLink to="/admin/departments">部门管理</RouterLink>
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
    radial-gradient(circle at 12% 18%, rgba(151, 210, 235, 0.35), transparent 28%),
    linear-gradient(135deg, #f7fbec 0%, #fef7df 48%, #eef8ff 100%);
}

.admin-panel {
  width: min(560px, 100%);
  padding: 34px;
  border: 1px solid rgba(67, 95, 85, 0.15);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: 0 24px 80px rgba(83, 108, 94, 0.18);
}

.eyebrow {
  margin: 0 0 10px;
  color: #65914c;
  font-weight: 700;
}

h1 {
  margin: 0 0 12px;
  color: #1d2c3e;
}

p {
  color: #68776f;
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
  color: #1d2c3e;
  background: #eef2f7;
  font-weight: 800;
}

button {
  height: 42px;
  padding: 0 18px;
  margin-top: 24px;
  border: 0;
  border-radius: 6px;
  color: #fff;
  background: #1d2c3e;
  cursor: pointer;
}
</style>
