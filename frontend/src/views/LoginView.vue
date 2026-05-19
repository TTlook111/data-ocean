<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Database, Eye, EyeOff, LockKeyhole, LogIn, MessageSquareText, ShieldCheck } from 'lucide-vue-next'
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
  { label: '数据源治理', icon: Database },
  { label: '权限管控', icon: ShieldCheck },
  { label: '自然语言查询', icon: MessageSquareText },
]

const platformMetrics = [
  { value: '权限', label: '按角色与部门控制访问范围' },
  { value: '数据源', label: '集中维护业务库连接状态' },
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
        <p>企业级 NL2SQL 数据治理平台</p>
        <h1>让业务人员在可信数据边界内，直接提出问题。</h1>
        <span>统一管理账号权限、数据源授权与问答入口，把自然语言查询放在可控、可追溯的流程里。</span>
      </div>

      <div class="platform-card">
        <div class="platform-illustration" aria-hidden="true">
          <span class="sky"></span>
          <span class="leaf leaf-one"></span>
          <span class="leaf leaf-two"></span>
          <span class="paper-plane"></span>
          <span class="data-card data-card-one">
            <Database :size="18" />
            数据源
          </span>
          <span class="data-card data-card-two">
            <ShieldCheck :size="18" />
            授权
          </span>
          <span class="query-line"></span>
        </div>
        <div class="platform-metrics">
          <span v-for="item in platformMetrics" :key="item.value">
            <strong>{{ item.value }}</strong>
            <small>{{ item.label }}</small>
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
          <span>使用平台账号进入 Web 管理端与问答端。</span>
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
  min-width: 1120px;
  display: grid;
  grid-template-columns: minmax(560px, 1.08fr) minmax(460px, 0.92fr);
  color: var(--do-ink);
  background:
    linear-gradient(180deg, rgba(189, 232, 248, 0.78) 0, rgba(255, 247, 227, 0.82) 50%, #f7fbf2 100%);
}

.login-visual {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 34px;
  padding: 44px 56px;
  background:
    linear-gradient(180deg, rgba(189, 232, 248, 0.7) 0, rgba(255, 247, 227, 0.68) 62%, rgba(246, 251, 239, 0.96) 100%);
  border-right: 1px solid rgba(190, 210, 176, 0.8);
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
  color: #fff;
  background: linear-gradient(135deg, #4d8fdc 0%, #6aa84f 100%);
  font-weight: 900;
}

.brand-row strong {
  font-size: 18px;
}

.visual-copy {
  max-width: 720px;
}

.visual-copy p {
  margin: 0 0 14px;
  color: var(--do-primary-strong);
  font-weight: 900;
}

.visual-copy h1 {
  margin: 0;
  color: #1d3c34;
  font-size: 42px;
  line-height: 1.18;
}

.visual-copy span {
  display: block;
  max-width: 640px;
  margin-top: 18px;
  color: #526653;
  font-size: 16px;
  line-height: 1.8;
}

.platform-card {
  width: min(720px, 100%);
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(220px, 0.72fr);
  align-items: center;
  gap: 18px;
  padding: 22px;
  border: 1px solid rgba(77, 143, 220, 0.22);
  border-radius: 8px;
  background: rgba(255, 253, 246, 0.76);
  box-shadow: var(--do-shadow);
}

.platform-illustration {
  position: relative;
  min-height: 270px;
  overflow: hidden;
  border: 1px solid rgba(190, 210, 176, 0.7);
  border-radius: 8px;
  background:
    linear-gradient(180deg, #bde8f8 0, #eaf7ff 42%, #fff7e3 43%, #fffaf0 100%);
}

.sky {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse at 22% 18%, rgba(255, 255, 255, 0.95) 0 18px, transparent 19px),
    radial-gradient(ellipse at 33% 15%, rgba(255, 255, 255, 0.92) 0 24px, transparent 25px),
    radial-gradient(ellipse at 70% 16%, rgba(255, 255, 255, 0.78) 0 22px, transparent 23px);
}

.leaf {
  position: absolute;
  left: -36px;
  border-radius: 56% 44% 58% 42%;
  background: rgba(106, 168, 79, 0.82);
  box-shadow:
    28px 24px 0 rgba(128, 190, 64, 0.78),
    54px -2px 0 rgba(106, 168, 79, 0.64),
    86px 34px 0 rgba(77, 143, 220, 0.72),
    112px 8px 0 rgba(77, 143, 220, 0.62);
}

.leaf-one {
  top: 72px;
  width: 86px;
  height: 70px;
}

.leaf-two {
  top: 150px;
  width: 72px;
  height: 58px;
  opacity: 0.86;
}

.paper-plane {
  position: absolute;
  top: 116px;
  left: 128px;
  width: 0;
  height: 0;
  border-top: 11px solid transparent;
  border-bottom: 11px solid transparent;
  border-left: 34px solid #fff;
  filter: drop-shadow(0 3px 4px rgba(77, 143, 220, 0.16));
  transform: rotate(-18deg);
}

.data-card {
  position: absolute;
  right: 34px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 132px;
  height: 44px;
  padding: 0 13px;
  border: 1px solid rgba(77, 143, 220, 0.22);
  border-radius: 8px;
  color: var(--do-ink);
  background: rgba(255, 253, 246, 0.92);
  font-weight: 900;
  box-shadow: 0 12px 26px rgba(77, 143, 220, 0.12);
}

.data-card-one {
  top: 96px;
}

.data-card-two {
  top: 154px;
  right: 70px;
  color: var(--do-accent-strong);
}

.query-line {
  position: absolute;
  right: 46px;
  bottom: 36px;
  width: 210px;
  height: 48px;
  border: solid rgba(106, 168, 79, 0.42);
  border-width: 0 0 3px 3px;
  border-radius: 0 0 0 46px;
}

.platform-metrics {
  display: grid;
  gap: 12px;
}

.platform-metrics span {
  min-height: 88px;
  display: grid;
  align-content: center;
  padding: 16px;
  border: 1px solid rgba(190, 210, 176, 0.72);
  border-radius: 8px;
  background: rgba(255, 250, 240, 0.9);
}

.platform-metrics strong {
  color: var(--do-primary-strong);
  font-size: 26px;
}

.platform-metrics small {
  margin-top: 7px;
  color: var(--do-muted);
  line-height: 1.5;
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
  border: 1px solid rgba(77, 143, 220, 0.22);
  border-radius: 8px;
  color: #355b42;
  background: rgba(255, 253, 246, 0.74);
  font-size: 13px;
  font-weight: 800;
}

.login-panel {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 56px;
  background: rgba(255, 253, 246, 0.92);
}

.form-shell {
  width: 100%;
  max-width: 430px;
  padding: 30px;
  border: 1px solid rgba(190, 210, 176, 0.74);
  border-radius: 8px;
  background: #fffdf6;
  box-shadow: var(--do-shadow);
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
  color: #9aa796;
}

.field input:focus {
  border-color: var(--do-primary);
  box-shadow: 0 0 0 4px rgba(77, 143, 220, 0.16);
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
  color: var(--do-accent-strong);
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
  box-shadow: 0 14px 24px rgba(77, 143, 220, 0.22);
  cursor: pointer;
}

.submit-button:hover {
  background: var(--do-primary-strong);
}

.submit-button:disabled {
  cursor: wait;
  opacity: 0.72;
}
</style>
