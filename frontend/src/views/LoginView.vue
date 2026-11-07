<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Eye, EyeOff } from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'
import { getCaptcha } from '../api/auth'
import BrandMark from '../components/common/BrandMark.vue'
import coastPhoto from '../assets/ocean/california-surf.webp'

const router = useRouter()
const authStore = useAuthStore()
const form = reactive({ username: '', password: '', captchaCode: '' })
const passwordInput = ref<HTMLInputElement>()
const showPassword = ref(false)
const loading = ref(false)
const captchaImage = ref('')
const captchaLoading = ref(false)
const captchaError = ref(false)
const captchaKey = ref('')
const loginError = ref('')
let disposed = false

// 图像与 key 属于同一个挑战。刷新时立即作废旧输入，晚请求不能回填离开的页面。
async function refreshCaptcha() {
  if (captchaLoading.value) return
  captchaLoading.value = true
  captchaError.value = false
  captchaImage.value = ''
  captchaKey.value = ''
  form.captchaCode = ''
  try {
    const res = await getCaptcha()
    if (disposed) return
    if (!res.data?.captchaKey || !res.data.captchaImage) throw new Error('验证码为空')
    captchaKey.value = res.data.captchaKey
    captchaImage.value = res.data.captchaImage
  } catch {
    if (!disposed) captchaError.value = true
  } finally {
    if (!disposed) captchaLoading.value = false
  }
}

// 成功后由路由根据已登录账号决定首次改密或有权入口，保留密码原始字符。
async function submit() {
  if (loading.value) return
  if (!form.username.trim() || !form.password.trim()) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  if (!form.captchaCode.trim()) {
    ElMessage.warning('请输入验证码')
    return
  }

  if (!captchaKey.value || captchaLoading.value) {
    ElMessage.warning('请先刷新验证码')
    return
  }

  const requestIdentity = authStore.token
  loading.value = true
  loginError.value = ''
  try {
    const result = await authStore.login(
      {
        username: form.username.trim(),
        password: form.password,
        captchaKey: captchaKey.value,
        captchaCode: form.captchaCode.trim(),
      },
      () => !disposed,
    )
    if (disposed || authStore.token !== result.data.token) return
    ElMessage.success('登录成功')
    await router.replace('/')
  } catch (error: unknown) {
    if (disposed || authStore.token !== requestIdentity) return
    const message =
      typeof error === 'object' &&
      error !== null &&
      'response' in error &&
      typeof (error as { response?: { data?: { message?: string } } }).response?.data?.message === 'string'
        ? (error as { response: { data: { message: string } } }).response.data.message
        : '登录失败，请检查账号或服务状态'
    loginError.value = message
    ElMessage.error(message)
    form.password = ''
    showPassword.value = false
    form.captchaCode = ''
    // 失败后保留账号，清除密码与旧验证码，再把键盘焦点送到需要重新填写的密码。
    void refreshCaptcha()
    await nextTick()
    if (!disposed && authStore.token === requestIdentity) passwordInput.value?.focus()
  } finally {
    if (!disposed) loading.value = false
  }
}

onMounted(() => {
  void refreshCaptcha()
})
onUnmounted(() => {
  disposed = true
})
</script>

<template>
  <main class="login-page">
    <header class="login-masthead" aria-label="DataOcean">
      <BrandMark class="login-mark" />
      <span>DataOcean</span>
    </header>

    <div class="login-layout">
      <!-- 摄影原件已获公有领域标记。本地资源可离线显示，署名链接指向作品原页。 -->
      <figure class="coast-figure">
        <div class="coast-image">
          <img
            :src="coastPhoto"
            alt="加利福尼亚海岸上空俯拍的碎浪与鸟群"
            width="1920"
            height="1280"
            fetchpriority="high"
          />
        </div>
        <figcaption>
          <span>加利福尼亚海岸，2019</span>
          <a
            href="https://www.usgs.gov/media/images/shorebirds-flying-over-breaking-waves-california-coast"
            target="_blank"
            rel="noopener noreferrer"
          >
            Laney White / USGS
          </a>
        </figcaption>
      </figure>

      <section class="login-panel" aria-labelledby="login-heading">
        <div class="form-shell">
          <h1 id="login-heading">登录</h1>
          <p v-if="loginError" class="login-error" role="alert">{{ loginError }}</p>
          <form class="login-form" @submit.prevent="submit">
            <!-- 原生标签和 required 负责读屏名称与缺项定位；输入值不依靠占位文字解释。 -->
            <div class="field">
              <label for="login-username">账号</label>
              <input
                id="login-username"
                v-model="form.username"
                type="text"
                autocomplete="username"
                required
              />
            </div>
            <div class="field">
              <label for="login-password">密码</label>
              <div class="password-field">
                <input
                  id="login-password"
                  ref="passwordInput"
                  v-model="form.password"
                  :type="showPassword ? 'text' : 'password'"
                  autocomplete="current-password"
                  required
                />
                <button
                  type="button"
                  :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                  :aria-pressed="showPassword"
                  @click="showPassword = !showPassword"
                >
                  <component :is="showPassword ? EyeOff : Eye" :size="18" aria-hidden="true" />
                </button>
              </div>
            </div>
            <div class="field">
              <label for="login-captcha">验证码</label>
              <div class="captcha-field">
                <input
                  id="login-captcha"
                  v-model="form.captchaCode"
                  type="text"
                  autocomplete="off"
                  required
                  maxlength="4"
                  :aria-describedby="captchaError ? 'captcha-error' : undefined"
                />
                <button
                  class="captcha-image-button"
                  type="button"
                  aria-label="刷新验证码"
                  title="点击图片刷新验证码"
                  :disabled="captchaLoading"
                  @click="refreshCaptcha"
                >
                  <img v-if="captchaImage" :src="captchaImage" alt="验证码" class="captcha-img" />
                  <span v-else>{{ captchaLoading ? '加载中' : '重新获取' }}</span>
                </button>
              </div>
              <p v-if="captchaError" id="captcha-error" class="captcha-error" role="status">
                验证码加载失败，请点击重新获取。
              </p>
            </div>
            <button class="submit-button" type="submit" :disabled="loading" :aria-busy="loading">
              {{ loading ? '登录中…' : '登录' }}
            </button>
          </form>
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped>
/* 宽屏使用一幅完整的观测摄影与独立表单，版心、字色和主操作沿用全站令牌。 */
.login-page {
  box-sizing: border-box;
  min-height: 100dvh;
  max-width: 1800px;
  margin-inline: auto;
  padding: 0 clamp(24px, 4vw, 72px) clamp(20px, 3.5vh, 42px);
  color: var(--do-ink);
  background: var(--do-bg);
}
.login-masthead {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: clamp(64px, 8.5vh, 92px);
  font-size: 21px;
  font-weight: 650;
  letter-spacing: -0.045em;
}
.login-mark {
  width: 35px;
  height: 35px;
}
.login-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.12fr) minmax(0, 1fr);
  align-items: stretch;
}
.coast-figure {
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  min-width: 0;
  margin: 0;
}
.coast-image {
  position: relative;
  min-height: clamp(470px, 74dvh, 900px);
  overflow: hidden;
  background: var(--do-soft);
}
.coast-image img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 61% 48%;
}
.coast-figure figcaption {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px 18px;
  padding-top: 12px;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.5;
}
.coast-figure figcaption a {
  color: inherit;
  text-underline-offset: 3px;
  text-decoration-color: var(--do-line);
}
.coast-figure figcaption a:hover {
  color: var(--do-primary-strong);
}
.login-panel {
  display: grid;
  place-items: center;
  min-width: 0;
  padding: clamp(26px, 5vh, 64px) clamp(28px, 5vw, 90px) clamp(45px, 8vh, 96px);
}
.form-shell {
  width: min(100%, 384px);
}
.form-shell h1 {
  margin: 0 0 clamp(28px, 4vh, 42px);
  color: var(--do-ink);
  font-size: clamp(32px, 3vw, 42px);
  font-weight: 630;
  letter-spacing: -0.045em;
  line-height: 1.18;
}
.login-form {
  display: grid;
  gap: 20px;
}
.field {
  display: grid;
  gap: 8px;
  min-width: 0;
}
.field label {
  color: var(--do-ink);
  font-size: 14px;
  font-weight: 550;
}
.field input,
.captcha-image-button,
.submit-button {
  box-sizing: border-box;
  min-width: 0;
  height: 48px;
  border-radius: 5px;
  font: inherit;
}
.field input {
  width: 100%;
  padding: 0 13px;
  border: 1px solid var(--do-line-strong, var(--do-line));
  background: var(--do-surface);
  color: var(--do-ink);
  font-size: 16px;
}
.field input:focus {
  outline: 2px solid var(--do-primary);
  outline-offset: 1px;
}
.password-field {
  position: relative;
}
.password-field input {
  padding-right: 45px;
}
.password-field button {
  display: grid;
  place-items: center;
  position: absolute;
  right: 3px;
  top: 3px;
  width: 42px;
  height: 42px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--do-muted);
  cursor: pointer;
}
.captcha-field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 116px;
  gap: 10px;
}
.captcha-image-button {
  display: grid;
  place-items: center;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--do-line);
  background: var(--do-surface);
  color: var(--do-muted);
  cursor: pointer;
  font-size: 13px;
}
.captcha-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.submit-button {
  width: 100%;
  margin-top: 5px;
  border: 1px solid var(--do-primary);
  background: var(--do-primary);
  color: var(--do-on-primary);
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
}
.submit-button:hover:not(:disabled) {
  background: var(--do-primary-strong);
}
.login-page button:disabled {
  cursor: wait;
  opacity: 0.64;
}
.login-page button:focus-visible,
.coast-figure a:focus-visible {
  outline: 2px solid var(--do-primary);
  outline-offset: 3px;
}
.login-error,
.captcha-error {
  margin: 0;
  color: var(--do-danger);
  font-size: 13px;
  line-height: 1.55;
}
.login-error {
  margin-bottom: 20px;
  padding-inline-start: 10px;
  border-inline-start: 2px solid var(--do-danger);
}
/* 小窗口优先保留输入与错误空间，矮窗口沿页面自然滚动。 */
@media (max-width: 760px) {
  .login-page {
    padding-inline: clamp(20px, 6vw, 42px);
  }
  .login-masthead {
    max-width: 420px;
    margin: auto;
    min-height: 72px;
    border-bottom: 1px solid var(--do-line);
  }
  .login-layout {
    display: block;
  }
  .coast-figure {
    display: none;
  }
  .login-panel {
    min-height: calc(100dvh - 110px);
    padding: 36px 0 58px;
  }
  .form-shell {
    width: min(100%, 420px);
  }
}
@media (max-height: 680px) and (min-width: 761px) {
  .login-panel {
    padding-block: 20px 36px;
  }
  .coast-image {
    min-height: 460px;
  }
  .login-form {
    gap: 15px;
  }
}
</style>
