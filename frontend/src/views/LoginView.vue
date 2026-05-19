<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Activity, Database, Eye, EyeOff, LockKeyhole, LogIn, ShieldCheck } from 'lucide-vue-next'
import heroImage from '../assets/hero.png'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()
const showPassword = ref(false)
const loading = ref(false)
const remember = ref(true)
const form = reactive({
  username: 'admin',
  password: '',
})

const capabilities = [
  { label: '多数据源治理', icon: Database },
  { label: '权限与审计', icon: ShieldCheck },
  { label: 'NL2SQL 查询链路', icon: Activity },
]

async function submit() {
  if (!form.username.trim() || !form.password.trim()) {
    ElMessage.warning('请输入账号和密码')
    return
  }

  loading.value = true
  try {
    await authStore.login({ username: form.username.trim(), password: form.password })
    if (!remember.value) {
      sessionStorage.setItem('dataocean_session_only', '1')
    }
    ElMessage.success('登录成功')
    await router.replace('/admin')
  } catch (error: unknown) {
    const message =
      typeof error === 'object' &&
      error !== null &&
      'response' in error &&
      typeof (error as { response?: { data?: { message?: string } } }).response?.data?.message === 'string'
        ? (error as { response: { data: { message: string } } }).response.data.message
        : '登录失败，请检查账号或服务状态'
    ElMessage.error(message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-visual" aria-label="DataOcean 平台说明">
      <div class="brand-row">
        <span>DO</span>
        <strong>DataOcean</strong>
      </div>

      <div class="visual-copy">
        <p>企业级 NL2SQL 智能数据查询与治理平台</p>
        <h1>从可信元数据出发，让业务查询变得可控、可审计。</h1>
      </div>

      <div class="platform-card">
        <div class="platform-image">
          <img :src="heroImage" alt="DataOcean 数据平台层级示意" />
        </div>
        <div class="platform-metrics">
          <span>
            <strong>001</strong>
            <small>用户模块已联调</small>
          </span>
          <span>
            <strong>002</strong>
            <small>数据源管理已联调</small>
          </span>
        </div>
      </div>

      <div class="capability-row">
        <span v-for="item in capabilities" :key="item.label">
          <component :is="item.icon" :size="16" />
          {{ item.label }}
        </span>
      </div>
    </section>

    <section class="login-panel" aria-label="登录表单">
      <div class="form-shell">
        <header class="form-heading">
          <p>欢迎回来</p>
          <h2>登录 DataOcean</h2>
          <span>使用平台账号进入治理管理端与问答端。</span>
        </header>

        <form class="login-form" @submit.prevent="submit">
          <label class="field">
            <span>账号</span>
            <input v-model="form.username" type="text" autocomplete="username" placeholder="请输入公司分配的账号" />
          </label>

          <label class="field">
            <span>密码</span>
            <div class="password-field">
              <input
                v-model="form.password"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="current-password"
                placeholder="请输入密码"
              />
              <button type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
                <EyeOff v-if="showPassword" :size="19" />
                <Eye v-else :size="19" />
              </button>
            </div>
          </label>

          <div class="form-tools">
            <label class="remember">
              <input v-model="remember" type="checkbox" />
              <span>30 天内记住我</span>
            </label>
            <span class="security-note">
              <LockKeyhole :size="15" />
              JWT 安全会话
            </span>
          </div>

          <button class="submit-button" type="submit" :disabled="loading">
            <LogIn :size="18" />
            <span>{{ loading ? '登录中...' : '登录' }}</span>
          </button>
        </form>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(500px, 1.1fr) minmax(440px, 0.9fr);
  color: var(--do-ink);
  background: #fff;
}

.login-visual {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 34px;
  padding: 44px 56px;
  color: #fff;
  background:
    linear-gradient(rgba(255, 255, 255, 0.055) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.055) 1px, transparent 1px),
    #101827;
  background-size: 32px 32px, 32px 32px, auto;
}

.brand-row {
  display: inline-flex;
  align-items: center;
  gap: 12px;
}

.brand-row span {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: linear-gradient(135deg, #2563eb 0%, #0f766e 100%);
  font-weight: 900;
}

.brand-row strong {
  font-size: 18px;
}

.visual-copy {
  max-width: 680px;
}

.visual-copy p {
  margin: 0 0 14px;
  color: #93c5fd;
  font-weight: 900;
}

.visual-copy h1 {
  margin: 0;
  font-size: 42px;
  line-height: 1.18;
}

.platform-card {
  width: min(680px, 100%);
  display: grid;
  grid-template-columns: minmax(240px, 1fr) minmax(220px, 0.75fr);
  align-items: center;
  gap: 18px;
  padding: 24px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.08);
  box-shadow: 0 26px 70px rgba(0, 0, 0, 0.24);
}

.platform-image {
  min-height: 240px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.06);
}

.platform-image img {
  width: min(280px, 82%);
  height: auto;
  display: block;
}

.platform-metrics {
  display: grid;
  gap: 12px;
}

.platform-metrics span {
  min-height: 86px;
  display: grid;
  align-content: center;
  padding: 16px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.09);
}

.platform-metrics strong {
  font-size: 30px;
}

.platform-metrics small {
  color: #cbd5e1;
}

.capability-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.capability-row span {
  height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 12px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 8px;
  color: #dbeafe;
  background: rgba(255, 255, 255, 0.08);
  font-size: 13px;
  font-weight: 800;
}

.login-panel {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 56px;
  background: #fff;
}

.form-shell {
  width: 100%;
  max-width: 430px;
}

.form-heading {
  margin-bottom: 28px;
}

.form-heading p {
  margin: 0 0 8px;
  color: var(--do-primary);
  font-size: 13px;
  font-weight: 900;
}

.form-heading h2 {
  margin: 0;
  font-size: 32px;
  line-height: 1.2;
}

.form-heading span {
  display: block;
  margin-top: 10px;
  color: var(--do-muted);
  line-height: 1.6;
}

.login-form {
  display: grid;
  gap: 18px;
}

.field {
  display: grid;
  gap: 9px;
  color: var(--do-ink);
  font-size: 14px;
  font-weight: 900;
}

.field input {
  width: 100%;
  height: 46px;
  border: 1px solid var(--do-line-strong);
  border-radius: 8px;
  padding: 0 13px;
  color: var(--do-ink);
  background: #fff;
  outline: none;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.field input::placeholder {
  color: #98a2b3;
}

.field input:focus {
  border-color: var(--do-primary);
  box-shadow: 0 0 0 4px rgba(37, 99, 235, 0.12);
}

.password-field {
  position: relative;
}

.password-field input {
  padding-right: 48px;
}

.password-field button {
  position: absolute;
  top: 50%;
  right: 6px;
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 7px;
  color: var(--do-muted);
  background: transparent;
  transform: translateY(-50%);
  cursor: pointer;
}

.password-field button:hover {
  color: var(--do-ink);
  background: var(--do-soft);
}

.form-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  color: var(--do-muted);
  font-size: 14px;
}

.remember,
.security-note {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-weight: 800;
}

.remember input {
  width: 17px;
  height: 17px;
  margin: 0;
  accent-color: var(--do-primary);
}

.security-note {
  color: var(--do-accent);
  white-space: nowrap;
}

.submit-button {
  height: 46px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  border: 0;
  border-radius: 8px;
  color: #fff;
  background: var(--do-primary);
  font-weight: 900;
  box-shadow: 0 14px 24px rgba(37, 99, 235, 0.18);
  cursor: pointer;
}

.submit-button:hover {
  background: var(--do-primary-strong);
}

.submit-button:disabled {
  cursor: wait;
  opacity: 0.72;
}

@media (max-width: 980px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .login-visual {
    min-height: auto;
    padding: 36px 28px;
  }

  .visual-copy h1 {
    font-size: 32px;
  }

  .platform-card {
    grid-template-columns: 1fr;
  }

  .login-panel {
    min-height: auto;
    padding: 42px 28px;
  }
}

@media (max-width: 560px) {
  .login-visual,
  .login-panel {
    padding: 28px 18px;
  }

  .visual-copy h1,
  .form-heading h2 {
    font-size: 28px;
  }

  .platform-image {
    min-height: 180px;
  }

  .form-tools {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
  }
}
</style>
