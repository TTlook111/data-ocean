<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Eye, EyeOff } from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'
import { getCaptcha } from '../api/auth'
import BrandMark from '../components/common/BrandMark.vue'
import LoginCharacters from '../components/login/LoginCharacters.vue'

const router = useRouter()
const authStore = useAuthStore()
const form = reactive({ username: '', password: '', captchaCode: '' })
const passwordInput = ref<HTMLInputElement>()
// 插画只接收字段的交互状态，密码值仍由表单独立持有。
const passwordFocused = ref(false)
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
    <div class="login-layout">
      <!-- 延续旧登录页的品牌与四角色构图，窄屏把插画收为表单上方的小组像。 -->
      <aside class="login-visual" aria-label="DataOcean">
        <div class="login-brand">
          <BrandMark class="login-mark" />
          <span lang="en" translate="no">DataOcean</span>
        </div>
        <p class="login-statement">让数据<br />回答问题</p>
        <div class="character-frame"><LoginCharacters :conceal="passwordFocused || showPassword" /></div>
      </aside>

      <section class="login-panel" aria-labelledby="login-heading">
        <div class="form-shell">
          <h1 id="login-heading">登录 DataOcean</h1>
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
                  @focus="passwordFocused = true"
                  @blur="passwordFocused = false"
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
/* 双栏共用一个外框。版心在大屏限制高度；窗口较矮时由文档自然滚动。 */
.login-page {
  --page-gutter: clamp(16px, 3.4dvh, 40px);
  display: grid;
  place-items: center;
  min-height: 100dvh;
  padding: var(--page-gutter) clamp(16px, 3.4vw, 72px);
  color: var(--do-ink);
  background: var(--do-bg);
}
.login-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.04fr) minmax(0, 1fr);
  width: min(100%, 1520px);
  min-height: clamp(540px, calc(100dvh - 2 * var(--page-gutter)), 880px);
  overflow: clip;
  border: 1px solid var(--do-line);
  border-radius: 14px;
  background: var(--do-surface);
}
.login-visual {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-width: 0;
  padding: clamp(30px, 5dvh, 58px) clamp(30px, 4.2vw, 70px);
  background: color-mix(in srgb, var(--do-primary-soft) 52%, var(--do-nav-bg));
}
.login-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 23px;
  font-weight: 650;
  letter-spacing: -0.045em;
}
.login-mark {
  width: 29px;
  height: 36px;
}
.login-statement {
  margin: clamp(30px, 5.4dvh, 58px) 0 28px;
  color: var(--do-ink);
  font-size: clamp(46px, 4.8vw, 74px);
  font-weight: 690;
  letter-spacing: -.055em;
  line-height: 1.12;
}
.character-frame {
  width: min(100%, 52dvh, 490px);
  margin-top: auto;
}
.login-panel {
  display: grid;
  place-items: center;
  min-width: 0;
  padding: clamp(28px, 5vh, 60px) clamp(28px, 4.6vw, 76px);
}
.form-shell {
  width: min(100%, 410px);
}
.form-shell h1 {
  margin: 0 0 clamp(28px, 4vh, 42px);
  color: var(--do-ink);
  font-size: clamp(29px, 2.5vw, 38px);
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
.login-page button:focus-visible {
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
/* 窄屏保留品牌和小角色，省下大标题所占空间给登录表单。 */
@media (max-width: 760px) {
  .login-page {
    padding: 16px;
  }
  .login-layout {
    display: block;
    max-width: 480px;
    min-height: auto;
  }
  .login-visual {
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
    padding: 18px clamp(16px, 5vw, 26px) 14px;
  }
  .login-brand { font-size: 19px; gap: 7px; }
  .login-mark { width: 24px; height: 31px; }
  .login-statement { display: none; }
  .character-frame {
    width: min(40%, 128px);
    margin-top: 0;
  }
  .login-panel {
    padding: 30px clamp(18px, 6vw, 32px) 36px;
  }
  .form-shell {
    width: 100%;
  }
}
/* 较矮的桌面把留白让给错误消息和操作按钮。 */
@media (max-height: 680px) and (min-width: 761px) {
  .login-visual, .login-panel { padding-block: 26px; }
  .login-statement { margin-block: 24px; }
  .login-form { gap: 15px; }
}
</style>
