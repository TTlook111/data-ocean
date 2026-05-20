<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Eye, EyeOff, LogIn, RefreshCw } from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'
import { getCaptcha } from '../api/auth'

const router = useRouter()
const authStore = useAuthStore()
const showPassword = ref(false)
const loading = ref(false)
const pointerActive = ref(false)
const captchaImage = ref('')
const captchaKey = ref('')
const sceneStyle = reactive<Record<string, string>>({
  '--eye-x': '0px',
  '--eye-y': '0px',
  '--small-eye-x': '0px',
  '--small-eye-y': '0px',
  '--eye-scale': '1',
  '--orange-tilt': '0deg',
  '--purple-tilt': '3deg',
  '--black-tilt': '0deg',
  '--yellow-tilt': '0deg',
})
const form = reactive({
  username: 'admin',
  password: '',
  captchaCode: '',
})

async function refreshCaptcha() {
  try {
    const res = await getCaptcha()
    if (res.data) {
      captchaKey.value = res.data.captchaKey
      captchaImage.value = res.data.captchaImage
    }
  } catch {
    ElMessage.error('获取验证码失败')
  }
}

function handlePointerMove(event: PointerEvent) {
  const viewportWidth = window.innerWidth || 1
  const viewportHeight = window.innerHeight || 1
  const x = Math.max(-1, Math.min(1, (event.clientX / viewportWidth - 0.5) * 2))
  const y = Math.max(-1, Math.min(1, (event.clientY / viewportHeight - 0.5) * 2))

  sceneStyle['--eye-x'] = `${Math.round(x * 7)}px`
  sceneStyle['--eye-y'] = `${Math.round(y * 5)}px`
  sceneStyle['--small-eye-x'] = `${Math.round(x * 4)}px`
  sceneStyle['--small-eye-y'] = `${Math.round(y * 3)}px`
  sceneStyle['--orange-tilt'] = `${(x * 2.6).toFixed(2)}deg`
  sceneStyle['--purple-tilt'] = `${(3 + x * 2.2).toFixed(2)}deg`
  sceneStyle['--black-tilt'] = `${(x * 1.7).toFixed(2)}deg`
  sceneStyle['--yellow-tilt'] = `${(x * 2.4).toFixed(2)}deg`
}

function activatePointer() {
  pointerActive.value = true
  sceneStyle['--eye-scale'] = '1.18'
}

function releasePointer() {
  window.setTimeout(() => {
    pointerActive.value = false
    sceneStyle['--eye-scale'] = '1'
  }, 120)
}

async function submit() {
  if (!form.username.trim() || !form.password.trim()) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  if (!form.captchaCode.trim()) {
    ElMessage.warning('请输入验证码')
    return
  }

  loading.value = true
  try {
    await authStore.login({
      username: form.username.trim(),
      password: form.password,
      captchaKey: captchaKey.value,
      captchaCode: form.captchaCode.trim()
    })
    ElMessage.success('登录成功')
    await router.replace('/query')
  } catch (error: unknown) {
    const message =
      typeof error === 'object' &&
      error !== null &&
      'response' in error &&
      typeof (error as { response?: { data?: { message?: string } } }).response?.data?.message === 'string'
        ? (error as { response: { data: { message: string } } }).response.data.message
        : '登录失败，请检查账号或服务状态'
    ElMessage.error(message)
    form.captchaCode = ''
    refreshCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(refreshCaptcha)
</script>

<template>
  <main
    class="login-page"
    :class="{ 'is-pointer-down': pointerActive }"
    :style="sceneStyle"
    @pointermove="handlePointerMove"
    @pointerdown="activatePointer"
    @pointerup="releasePointer"
    @pointerleave="releasePointer"
  >
    <section class="login-visual" aria-label="DataOcean 平台说明">
      <div class="brand-pill">
        <span></span>
        <strong>DataOcean</strong>
      </div>

      <div class="visual-copy">
        <p>你的数据海洋</p>
        <h1>从自然语言开始探索</h1>
      </div>

      <div class="monster-scene" aria-hidden="true">
        <span class="monster monster-orange">
          <i class="eye eye-left"><em></em></i>
          <i class="eye eye-right"><em></em></i>
        </span>
        <span class="monster monster-purple">
          <i class="eye eye-left"><em></em></i>
          <i class="eye eye-right"><em></em></i>
        </span>
        <span class="monster monster-black">
          <i class="eye eye-left"><em></em></i>
          <i class="eye eye-right"><em></em></i>
        </span>
        <span class="monster monster-yellow">
          <i class="eye eye-left"><em></em></i>
          <i class="eye eye-right"><em></em></i>
          <b class="mouth"></b>
        </span>
        <span class="ground-line"></span>
      </div>
    </section>

    <section class="login-panel" aria-label="登录表单">
      <div class="form-shell">
        <header class="form-heading">
          <h2>欢迎回来！</h2>
          <span>请输入你的账号信息</span>
        </header>

        <form class="login-form" @submit.prevent="submit">
          <label class="field">
            <span>账号</span>
            <input v-model="form.username" type="text" autocomplete="username" placeholder="请输入账号" />
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

          <label class="field">
            <span>验证码</span>
            <div class="captcha-field">
              <input
                v-model="form.captchaCode"
                type="text"
                autocomplete="off"
                placeholder="请输入验证码"
                maxlength="4"
              />
              <img
                v-if="captchaImage"
                :src="captchaImage"
                alt="验证码"
                class="captcha-img"
                @click="refreshCaptcha"
                title="点击刷新验证码"
              />
              <button type="button" class="captcha-refresh" aria-label="刷新验证码" @click="refreshCaptcha">
                <RefreshCw :size="16" />
              </button>
            </div>
          </label>

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
  --eye-x: 0px;
  --eye-y: 0px;
  --small-eye-x: 0px;
  --small-eye-y: 0px;
  --eye-scale: 1;
  --orange-tilt: 0deg;
  --purple-tilt: 3deg;
  --black-tilt: 0deg;
  --yellow-tilt: 0deg;
  min-height: 100vh;
  min-width: 1120px;
  display: grid;
  grid-template-columns: minmax(560px, 1fr) minmax(560px, 1fr);
  color: #0f172a;
  background: #fff;
}

.login-visual {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  padding: 46px 52px;
  color: #fff;
  background:
    linear-gradient(rgba(255, 255, 255, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.08) 1px, transparent 1px),
    linear-gradient(160deg, #7b3ff4 0%, #6d2ee8 42%, #3c12a4 100%);
  background-size: 30px 30px, 30px 30px, auto;
}

.login-visual::before {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 71% 7%, rgba(255, 255, 255, 0.14), transparent 18%),
    radial-gradient(circle at 18% 75%, rgba(255, 136, 91, 0.16), transparent 24%);
  content: "";
}

.brand-pill {
  position: relative;
  z-index: 2;
  width: max-content;
  height: 44px;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 0 14px;
  border: 1px solid rgba(255, 255, 255, 0.24);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.14);
  box-shadow: 0 18px 40px rgba(30, 12, 110, 0.18);
}

.brand-pill span {
  width: 24px;
  height: 24px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.28);
}

.brand-pill strong {
  font-size: 18px;
  line-height: 1;
}

.visual-copy {
  position: relative;
  z-index: 2;
  margin: 46px 0 0 34px;
}

.visual-copy p {
  margin: 0 0 12px;
  color: #ddd2ff;
  font-size: 20px;
  font-weight: 900;
}

.visual-copy h1 {
  margin: 0;
  max-width: 560px;
  font-size: 46px;
  line-height: 1.16;
  letter-spacing: 0;
  text-shadow: 0 8px 24px rgba(42, 13, 128, 0.28);
}

.monster-scene {
  position: absolute;
  left: 44px;
  right: 40px;
  bottom: 70px;
  z-index: 2;
  height: 470px;
}

.monster {
  position: absolute;
  bottom: 0;
  display: block;
  box-shadow: 0 18px 32px rgba(28, 6, 96, 0.22);
  transform-origin: 50% 100%;
  transition: transform 140ms ease-out;
}

.eye {
  position: absolute;
  width: 24px;
  height: 24px;
  overflow: hidden;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 5px rgba(20, 20, 40, 0.18);
  transform: scale(var(--eye-scale));
  transition: transform 120ms ease-out;
}

.eye em {
  position: absolute;
  top: calc(50% - 4px + var(--eye-y));
  left: calc(50% - 4px + var(--eye-x));
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #273244;
  transition: top 120ms ease-out, left 120ms ease-out;
}

.monster-orange {
  z-index: 5;
  left: 4px;
  width: 266px;
  height: 194px;
  border-radius: 150px 150px 0 0;
  background: linear-gradient(135deg, #ffad75 0%, #ff8b5d 100%);
  transform: rotate(var(--orange-tilt));
}

.monster-orange .eye {
  top: 90px;
  width: 15px;
  height: 15px;
  background: #273244;
  transform: translate(var(--small-eye-x), var(--small-eye-y)) scale(var(--eye-scale));
}

.monster-orange .eye em {
  display: none;
}

.monster-orange .eye-left {
  left: 122px;
}

.monster-orange .eye-right {
  left: 148px;
}

.monster-purple {
  z-index: 2;
  left: 190px;
  bottom: 0;
  width: 198px;
  height: 450px;
  border-radius: 18px 18px 0 0;
  background: linear-gradient(155deg, #8d65fb 0%, #6d36dc 100%);
  transform: rotate(var(--purple-tilt));
}

.monster-purple .eye-left {
  right: 92px;
  top: 82px;
}

.monster-purple .eye-right {
  right: 58px;
  top: 84px;
}

.monster-black {
  z-index: 4;
  left: 332px;
  bottom: 2px;
  width: 144px;
  height: 340px;
  border-radius: 8px 8px 0 0;
  background: linear-gradient(140deg, #3a3a3d 0%, #121212 100%);
  transform: rotate(var(--black-tilt));
}

.monster-black .eye-left {
  left: 58px;
  top: 76px;
}

.monster-black .eye-right {
  left: 92px;
  top: 78px;
}

.monster-yellow {
  z-index: 6;
  left: 416px;
  width: 188px;
  height: 272px;
  border-radius: 106px 106px 0 0;
  background: linear-gradient(145deg, #fff06f 0%, #f3df3e 100%);
  transform: rotate(var(--yellow-tilt));
}

.monster-yellow .eye {
  top: 82px;
  width: 15px;
  height: 15px;
  background: #273244;
  transform: translate(var(--small-eye-x), var(--small-eye-y)) scale(var(--eye-scale));
}

.monster-yellow .eye em {
  display: none;
}

.monster-yellow .eye-left {
  left: 80px;
}

.monster-yellow .eye-right {
  left: 112px;
}

.monster-yellow .mouth {
  position: absolute;
  left: 58px;
  top: 132px;
  width: 82px;
  height: 26px;
  border-bottom: 4px solid #343942;
  border-radius: 0 0 42px 42px;
  transition: width 120ms ease-out, height 120ms ease-out, border 120ms ease-out, border-radius 120ms ease-out,
    left 120ms ease-out, top 120ms ease-out;
}

.login-page.is-pointer-down .monster-yellow .mouth {
  left: 88px;
  top: 132px;
  width: 22px;
  height: 22px;
  border: 4px solid #343942;
  border-radius: 50%;
}

.ground-line {
  position: absolute;
  left: 0;
  right: 22px;
  bottom: 0;
  height: 4px;
  border-radius: 999px;
  background: rgba(248, 233, 110, 0.55);
}

.login-panel {
  min-height: 100vh;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 56px;
  background: #fff;
}

.form-shell {
  width: 100%;
  max-width: 500px;
  margin-top: 34px;
}

.form-heading {
  margin-bottom: 28px;
}

.form-heading h2 {
  margin: 0;
  color: #020617;
  font-size: 38px;
  line-height: 1.18;
  letter-spacing: 0;
}

.form-heading span {
  display: block;
  margin-top: 8px;
  color: #64748b;
  font-size: 16px;
  font-weight: 800;
}

.login-form {
  display: grid;
  gap: 18px;
}

.field {
  display: grid;
  gap: 10px;
  color: #0f172a;
  font-size: 14px;
  font-weight: 900;
}

.field input {
  width: 100%;
  height: 54px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 0 16px;
  color: #020617;
  background: #fff;
  outline: none;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.field input::placeholder {
  color: #a5b4c5;
  font-weight: 800;
}

.field input:focus {
  border-color: #7c3aed;
  box-shadow: 0 0 0 4px rgba(124, 58, 237, 0.1);
}

.password-field {
  position: relative;
}

.password-field input {
  padding-right: 50px;
}

.password-field button {
  position: absolute;
  top: 50%;
  right: 12px;
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 8px;
  color: #64748b;
  background: transparent;
  transform: translateY(-50%);
  cursor: pointer;
}

.password-field button:hover {
  color: #4c1d95;
  background: #f5f3ff;
}

.captcha-field {
  display: flex;
  align-items: center;
  gap: 10px;
}

.captcha-field input {
  flex: 1;
  height: 54px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 0 16px;
  color: #020617;
  background: #fff;
  outline: none;
  font-size: 16px;
  letter-spacing: 2px;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.captcha-field input::placeholder {
  color: #a5b4c5;
  font-weight: 800;
  letter-spacing: 0;
}

.captcha-field input:focus {
  border-color: #7c3aed;
  box-shadow: 0 0 0 4px rgba(124, 58, 237, 0.1);
}

.captcha-img {
  height: 48px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid #e2e8f0;
}

.captcha-refresh {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 8px;
  color: #64748b;
  background: transparent;
  cursor: pointer;
}

.captcha-refresh:hover {
  color: #4c1d95;
  background: #f5f3ff;
}

.submit-button {
  height: 54px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border: 0;
  border-radius: 12px;
  color: #fff;
  background: #111827;
  font-weight: 900;
  box-shadow: 0 18px 34px rgba(15, 23, 42, 0.16);
  cursor: pointer;
}

.submit-button:hover {
  background: #020617;
}

.submit-button:disabled {
  cursor: wait;
  opacity: 0.72;
}
</style>
