<script setup lang="ts">
import { reactive, ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Eye, EyeOff, LogIn } from 'lucide-vue-next'
import { gsap } from 'gsap'
import { useAuthStore } from '../stores/auth'
import { getCaptcha } from '../api/auth'
import brandMark from '../assets/brand/dataocean-mark.png'
import { getLoginLookOffset } from '../utils/loginMotion'

const router = useRouter()
const authStore = useAuthStore()
const showPassword = ref(false)
const loading = ref(false)
const pointerActive = ref(false)
const captchaImage = ref('')
const captchaLoading = ref(false)
const captchaError = ref(false)
const loginError = ref('')
const captchaKey = ref('')
const loginPageRef = ref<HTMLElement | null>(null)
const form = reactive({
  username: '',
  password: '',
  captchaCode: '',
})

type QuickTo = ReturnType<typeof gsap.quickTo>
type EyeTracker = { element: HTMLElement; target: HTMLElement; x: QuickTo; y: QuickTo }
type BodyTracker = { element: HTMLElement; x: QuickTo; rotation: QuickTo; base: number; weight: number }
let motionMedia: ReturnType<typeof gsap.matchMedia> | undefined
let eyes: EyeTracker[] = []
let bodies: BodyTracker[] = []
let pointerFrame: number | undefined
let lastPointer: { x: number; y: number } | undefined
let disposed = false

function reduceMotion() {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
}

function rootElement() {
  return loginPageRef.value
}

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

function cancelPointerFrame() {
  if (pointerFrame !== undefined) window.cancelAnimationFrame(pointerFrame)
  pointerFrame = undefined
  lastPointer = undefined
}

function updateCharacters() {
  pointerFrame = undefined
  const pointer = lastPointer
  if (!pointer || reduceMotion()) return
  // 先统一读布局，再更新动画，避免每个角色读写交错。
  const eyePositions = eyes.map(eye => ({ eye, box: eye.element.getBoundingClientRect() }))
  const bodyPositions = bodies.map(body => ({ body, box: body.element.getBoundingClientRect() }))
  for (const { eye, box } of eyePositions) {
    const radius = eye.target === eye.element ? 3 : Math.max(0, (box.width - eye.target.offsetWidth) / 2 - 2)
    const offset = getLoginLookOffset(pointer, { x: box.x + box.width / 2, y: box.y + box.height / 2 }, radius)
    eye.x(offset.x)
    eye.y(offset.y)
  }
  for (const { body, box } of bodyPositions) {
    const direction = Math.max(-1, Math.min(1, (pointer.x - box.x - box.width / 2) / 500))
    body.x(direction * 6 * body.weight)
    body.rotation(body.base + direction * 2.2 * body.weight)
  }
}

function handlePointerMove(event: PointerEvent) {
  if (event.pointerType === 'touch' || reduceMotion() || !eyes.length) return
  lastPointer = { x: event.clientX, y: event.clientY }
  if (pointerFrame === undefined) pointerFrame = window.requestAnimationFrame(updateCharacters)
}

function releasePointer() {
  pointerActive.value = false
}

function resetCharacters() {
  cancelPointerFrame()
  releasePointer()
  eyes.forEach(eye => { eye.x(0); eye.y(0) })
  bodies.forEach(body => { body.x(0); body.rotation(body.base) })
}

function activatePointer(event: PointerEvent) {
  if (event.pointerType !== 'touch' && event.button === 0 && !reduceMotion()) pointerActive.value = true
}

function handleVisibilityChange() {
  if (document.hidden) resetCharacters()
}

function setupLoginMotion() {
  const root = rootElement()
  if (!root) return
  motionMedia = gsap.matchMedia()
  motionMedia.add('(prefers-reduced-motion: no-preference)', () => {
    const follow = { duration: 0.35, ease: 'power3.out' }
    eyes = Array.from(root.querySelectorAll<HTMLElement>('.eye')).map(element => {
      const target = element.closest('.monster-orange, .monster-yellow') ? element : element.querySelector<HTMLElement>('em') ?? element
      return { element, target, x: gsap.quickTo(target, 'x', follow), y: gsap.quickTo(target, 'y', follow) }
    })
    bodies = Array.from(root.querySelectorAll<HTMLElement>('.monster')).map((element, index) => {
      const base = element.classList.contains('monster-purple') ? -3 : 0
      gsap.set(element, { rotation: base })
      return { element, base, weight: [1, 0.75, 0.55, 0.85][index] ?? 1,
        x: gsap.quickTo(element, 'x', follow), rotation: gsap.quickTo(element, 'rotation', follow) }
    })
    gsap.from(root.querySelectorAll('.brand-pill, .visual-copy, .monster-scene, .form-shell'), {
      y: 16, autoAlpha: 0, duration: 0.6, stagger: 0.06, ease: 'power3.out', clearProps: 'transform,opacity,visibility',
    })
    return () => { cancelPointerFrame(); releasePointer(); eyes = []; bodies = [] }
  }, root)
}

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

  loading.value = true
  loginError.value = ''
  try {
    await authStore.login({
      username: form.username.trim(),
      password: form.password,
      captchaKey: captchaKey.value,
      captchaCode: form.captchaCode.trim()
    }, () => !disposed)
    if (disposed) return
    ElMessage.success('登录成功')
    await router.replace('/query')
  } catch (error: unknown) {
    if (disposed) return
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
    refreshCaptcha()
  } finally {
    if (!disposed) loading.value = false
  }
}

onMounted(() => {
  setupLoginMotion()
  void refreshCaptcha()
  window.addEventListener('blur', resetCharacters)
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onUnmounted(() => {
  disposed = true
  cancelPointerFrame()
  motionMedia?.revert()
  window.removeEventListener('blur', resetCharacters)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})
</script>

<template>
  <main
    ref="loginPageRef"
    class="login-page"
    :class="{ 'is-pointer-down': pointerActive }"
    @pointermove="handlePointerMove"
    @pointerdown="activatePointer"
    @pointerup="releasePointer"
    @pointerleave="resetCharacters"
    @pointercancel="resetCharacters"
  >
    <section class="login-visual" aria-label="DataOcean 平台说明">
      <div class="brand-pill">
        <img class="login-brand-mark" :src="brandMark" alt="" />
        <strong>DataOcean</strong>
      </div>

      <div class="visual-copy">
        <h1>让数据<br />回答问题</h1>
        <p>先理解业务，再让数据回答。</p>
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
          <h2>登录 DataOcean</h2>
          <span>进入你有权使用的数据与工作区</span>
        </header>

        <p v-if="loginError" class="login-error" role="alert">{{ loginError }}</p>
        <form class="login-form" @submit.prevent="submit">
          <label class="field">
            <span>账号</span>
            <input
              v-model="form.username"
              type="text"
              autocomplete="username"
              placeholder="请输入账号"
            />
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

          <div class="field">
            <label for="login-captcha">验证码</label>
            <div class="captcha-field">
              <input id="login-captcha" v-model="form.captchaCode" type="text" autocomplete="off"
                placeholder="请输入验证码" maxlength="4" :aria-describedby="captchaError ? 'captcha-error' : undefined" />
              <button class="captcha-image-button" type="button" aria-label="刷新验证码" title="点击图片刷新验证码" :disabled="captchaLoading" @click="refreshCaptcha">
                <img v-if="captchaImage" :src="captchaImage" alt="验证码" class="captcha-img" />
                <span v-else>{{ captchaLoading ? '加载中' : '重新获取' }}</span>
              </button>
            </div>
            <p v-if="captchaError" id="captcha-error" class="captcha-error" role="status">验证码加载失败，请点击重新获取。</p>
          </div>

          <button class="submit-button" type="submit" :disabled="loading" :aria-busy="loading">
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
  position: relative;
  isolation: isolate;
  --login-blue: var(--do-primary-strong);
  --login-radius: 20px;
  --login-gutter-block: clamp(14px, 2.4vh, 30px);
  --login-gutter-inline: clamp(18px, 2.6vw, 40px);
  height: 100svh;
  height: 100dvh;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1.04fr) minmax(0, 0.98fr);
  grid-template-rows: minmax(0, 1fr);
  align-items: stretch;
  gap: 0;
  padding: clamp(14px, 2.4vh, 30px) clamp(18px, 2.6vw, 40px);
  padding: var(--login-gutter-block) var(--login-gutter-inline);
  color: var(--do-ink);
  background: transparent;
}
.login-page::before {
  position: absolute;
  z-index: -1;
  inset: clamp(14px, 2.4vh, 30px) clamp(18px, 2.6vw, 40px);
  inset: var(--login-gutter-block) var(--login-gutter-inline);
  border-radius: var(--login-radius);
  box-shadow: var(--do-glass-shadow-raised);
  content: "";
  pointer-events: none;
}
.login-visual {
  position: relative;
  isolation: isolate;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: clamp(30px, 4.6dvh, 52px) clamp(32px, 4.4vw, 64px) clamp(34px, 5.2dvh, 60px);
  overflow: hidden;
  border-right: 0;
  border-radius: var(--login-radius) 0 0 var(--login-radius);
  color: white;
  background:
    linear-gradient(150deg, rgba(97, 176, 255, .42), rgba(13, 104, 232, .74) 36%, rgba(55, 145, 237, .44) 78%),
    var(--do-ocean-art);
  background-size: cover;
  background-position: 26% center;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.6);
}
.login-visual::before { position: absolute; z-index: -1; inset: 42% -18% -30%; border-radius: 50%; background: radial-gradient(ellipse, rgba(224, 242, 255, 0.42), transparent 67%); content: ""; pointer-events: none; }
.login-visual::after { position: absolute; z-index: -1; inset: 0; background: linear-gradient(155deg, rgba(255,255,255,.16), transparent 32%, transparent 72%, rgba(255,255,255,.16)); content: ""; pointer-events: none; }
.brand-pill { position: relative; z-index: 1; display: flex; align-items: center; gap: 14px; width: fit-content; }
.login-brand-mark { display: grid; place-items: center; width: 56px; height: 56px; border: 1px solid rgba(255,255,255,.35); border-radius: 13px; color: white; background: linear-gradient(145deg, #1264f3, #2878ff); box-shadow: 0 8px 20px rgba(13, 70, 177, .2); font-size: 24px; font-weight: 650; letter-spacing: -1px; }
.brand-pill strong { color: #10203b; font-size: clamp(24px, 1.9vw, 30px); font-weight: 700; letter-spacing: -1.2px; }
.visual-copy { position: relative; z-index: 1; margin-top: clamp(20px, 3.6dvh, 40px); }
.visual-copy h1 { margin: 0; color: #fff; font-size: clamp(52px, 5.4vw, 72px); font-weight: 760; line-height: 1.08; letter-spacing: -2px; text-shadow: 0 2px 14px rgba(25, 87, 175, .14); }
.visual-copy p { margin: clamp(8px, 1.2dvh, 12px) 0 0; color: rgba(255,255,255,.8); font-size: clamp(15px, 1.1vw, 17px); font-weight: 400; line-height: 1.5; }
.monster-scene { position: relative; z-index: 1; width: 100%; max-width: 500px; width: min(100%, 500px, 52dvh); aspect-ratio: 520 / 320; flex-shrink: 0; margin: auto 0 0; container-type: inline-size; }
.monster { position: absolute; bottom: 0; display: block; transform-origin: center bottom; box-shadow: 0 16px 26px rgb(31 63 118 / 17%), inset 0 4px 9px rgba(255,255,255,.2); }
.monster-orange { z-index: 5; left: 0; width: 40%; height: 40%; border-radius: 110px 110px 3px 3px; background: linear-gradient(135deg, #ffb792, #ff9c75); }
.monster-purple { z-index: 2; left: 27%; width: 29%; height: 100%; border-radius: 15px 15px 3px 3px; background: linear-gradient(150deg, #8ba9ff, #6d8cf4); }
.monster-black { z-index: 4; left: 48%; width: 23%; height: 71%; border-radius: 8px 8px 3px 3px; background: linear-gradient(140deg, #46516a, #293447); }
.monster-yellow { z-index: 6; left: 66%; width: 32%; height: 56%; border-radius: 100px 100px 3px 3px; background: linear-gradient(145deg, #fff08a, #ffe367); }
.eye { position: absolute; display: block; width: 16px; height: 16px; width: clamp(10px, 4cqw, 20px); height: clamp(10px, 4cqw, 20px); overflow: hidden; border-radius: 50%; background: white; }
.eye em { position: absolute; top: calc(50% - 3.5px); left: calc(50% - 3.5px); width: 7px; height: 7px; border-radius: 50%; background: #172c44; }
.monster-purple .eye { top: 21%; }
.monster-purple .eye-left { left: 42%; }
.monster-purple .eye-right { left: 65%; }
.monster-black .eye { top: 28%; }
.monster-black .eye-left { left: 39%; }
.monster-black .eye-right { left: 65%; }
.monster-orange .eye, .monster-yellow .eye { width: 11px; height: 11px; width: clamp(7px, 2.2cqw, 11px); height: clamp(7px, 2.2cqw, 11px); background: #233954; overflow: visible; box-shadow: 0 2px 3px rgb(20 40 60 / 12%); }
.monster-orange .eye em, .monster-yellow .eye em { display: none; }
.monster-orange .eye { top: 49%; }
.monster-orange .eye-left { left: 46%; }
.monster-orange .eye-right { left: 58%; }
.monster-yellow .eye { top: 31%; }
.monster-yellow .eye-left { left: 42%; }
.monster-yellow .eye-right { left: 58%; }
.mouth { position: absolute; left: 31%; top: 52%; width: 38%; height: 12%; border-bottom: 2px solid #233954; border-radius: 0 0 40px 40px; transition: height 180ms ease; }
.is-pointer-down .mouth { height: 18%; }
.ground-line { position: absolute; z-index: 7; inset: auto -2% -3px; height: 3px; border-radius: 50%; background: rgb(79 128 180 / 10%); }
.login-panel { position: relative; isolation: isolate; display: flex; align-items: center; align-items: safe center; justify-content: center; min-height: 0; padding: clamp(28px, 3.6dvh, 46px) clamp(28px, 3.6vw, 56px) clamp(40px, 5.2dvh, 64px); border-left: 0; border-radius: 0 var(--login-radius) var(--login-radius) 0; background: rgba(249, 253, 255, .52); box-shadow: inset 0 1px 0 rgba(255, 255, 255, .7); backdrop-filter: none; -webkit-backdrop-filter: none; }
.login-panel::before { position: absolute; z-index: -1; inset: 0; border-radius: inherit; clip-path: inset(0 round 0 var(--login-radius) var(--login-radius) 0); backdrop-filter: blur(20px) saturate(125%); -webkit-backdrop-filter: blur(20px) saturate(125%); content: ''; pointer-events: none; }
.form-shell { width: 100%; max-width: 430px; }
.form-heading { margin-bottom: clamp(20px, 2.8dvh, 28px); }
.form-heading h2 { margin: 0; font-size: clamp(34px, 3vw, 44px); font-weight: 700; line-height: 1.15; letter-spacing: -.8px; }
.form-heading span { display: block; margin-top: 8px; color: var(--do-muted); font-size: clamp(15px, 1.05vw, 16px); line-height: 1.5; font-weight: 400; }
.login-form { display: grid; gap: clamp(16px, 2.2dvh, 20px); }
.field { display: grid; gap: 8px; font-size: 14.5px; font-weight: 500; min-width: 0; }
.field input { width: 100%; min-width: 0; height: 50px; border: 1px solid rgba(142, 178, 225, .42); border-radius: 9px; padding: 0 16px; color: var(--do-ink); background: rgba(255,255,255,.76); font-size: 15.5px; font-weight: 400; transition: border-color 160ms ease, box-shadow 160ms ease, background-color 160ms ease; }
.field input::placeholder { color: var(--do-muted); }
.field input:focus { outline: none; border-color: var(--login-blue); background: rgba(255,255,255,.96); box-shadow: 0 0 0 3px rgba(45,104,204,.14); }
.password-field { position: relative; }
.password-field input { padding-right: 54px; }
.password-field button { position: absolute; inset: 5px 6px 5px auto; width: 40px; display: grid; place-items: center; border: 0; border-radius: 7px; color: var(--do-muted); background: transparent; cursor: pointer; }
.password-field button:hover { color: var(--login-blue); background: var(--do-primary-soft); }
.captcha-field { display: flex; align-items: stretch; gap: 10px; }
.captcha-field input { flex: 1 1 0; }
.captcha-image-button { width: clamp(108px, 9.2vw, 136px); height: 50px; flex-shrink: 0; padding: 0; overflow: hidden; border: 1px solid rgba(142,178,225,.42); border-radius: 8px; color: var(--do-muted); background: rgba(255,255,255,.76); cursor: pointer; }
.captcha-img { width: 100%; height: 100%; object-fit: contain; }
.captcha-error { margin: 0; color: var(--do-danger); font-size: 12.5px; line-height: 1.5; font-weight: 400; }
.submit-button { display: flex; align-items: center; justify-content: center; gap: 10px; height: 50px; margin-top: 2px; border: 1px solid rgba(255,255,255,.2); border-radius: 10px; color: white; background: linear-gradient(110deg, #1766f4, #2871ef); box-shadow: 0 9px 20px rgba(37,99,235,.18); font-size: 16px; font-weight: 650; cursor: pointer; transition: background 160ms ease, box-shadow 160ms ease, transform 160ms ease; }
.submit-button:hover { background: linear-gradient(110deg, #115bdf, #1f63de); box-shadow: 0 11px 24px rgba(37,99,235,.24); }
.submit-button:active { transform: translateY(1px); }
.submit-button:disabled, .captcha-image-button:disabled { opacity: .65; cursor: wait; }
button:focus-visible { outline: 3px solid rgb(45 104 204 / 45%); outline-offset: 3px; }
@media (max-height: 820px) and (min-width: 901px) {
  .login-visual { padding: clamp(24px, 3.4dvh, 38px) clamp(28px, 3.6vw, 52px) clamp(28px, 4dvh, 42px); }
  .login-brand-mark { width: 48px; height: 48px; border-radius: 12px; font-size: 21px; }
  .brand-pill { gap: 12px; }
  .brand-pill strong { font-size: 26px; }
  .visual-copy { margin-top: clamp(16px, 2.6dvh, 28px); }
  .visual-copy h1 { font-size: clamp(44px, 4.2vw, 58px); }
  .visual-copy p { font-size: 15px; }
  .monster-scene { width: min(100%, 440px, 48dvh); }
  .login-panel { padding-block: clamp(24px, 3.2dvh, 34px) clamp(32px, 4.4dvh, 44px); }
  .form-heading { margin-bottom: 22px; }
  .form-heading h2 { font-size: 36px; }
  .form-heading span { margin-top: 6px; }
  .login-form { gap: 16px; }
}
@media (max-height: 700px) and (min-width: 901px) {
  .login-visual { padding: 20px 28px 24px; }
  .login-brand-mark { width: 42px; height: 42px; border-radius: 11px; font-size: 19px; }
  .brand-pill strong { font-size: 23px; }
  .visual-copy { margin-top: 14px; }
  .visual-copy h1 { font-size: 46px; }
  .visual-copy p { font-size: 14px; }
  .monster-scene { width: min(100%, 400px, 42dvh); }
  .login-panel { padding-block: 22px 30px; }
  .form-heading { margin-bottom: 18px; }
  .form-heading h2 { font-size: 34px; }
  .login-form { gap: 16px; }
}
@media (max-height: 640px) and (min-width: 901px) {
  /* 极低桌面窗口不再强制一屏，允许页面自然滚动以保住表单可用性。 */
  .login-page { height: auto; min-height: 100dvh; }
  .monster-scene { width: min(100%, 340px, 38dvh); }
}
@media (max-width: 1100px) and (min-width: 901px) {
  .login-visual { padding-inline: clamp(24px, 3.2vw, 40px); }
  .login-panel { padding-inline: clamp(22px, 3vw, 36px); }
  .brand-pill { gap: 12px; }
  .captcha-image-button { width: 112px; }
}
@media (max-width: 900px) {
  .login-page { height: auto; min-height: 100dvh; grid-template-columns: minmax(0, 1fr); grid-template-rows: auto auto; align-items: start; align-content: start; gap: 0; padding: 12px; }
  .login-page::before { display: none; }
  .login-visual { min-height: 0; padding: 20px 22px 22px; border-right: 1px solid var(--do-glass-border); border-bottom: 0; border-radius: var(--login-radius) var(--login-radius) 0 0; }
  .brand-pill { gap: 10px; }
  .brand-pill strong { font-size: 22px; }
  .login-brand-mark { width: 38px; height: 38px; border-radius: 10px; font-size: 17px; }
  .visual-copy { margin-top: 16px; }
  .visual-copy h1 { font-size: clamp(28px, 8vw, 34px); line-height: 1.15; }
  .visual-copy h1 br { display: none; }
  .visual-copy p { margin-top: 6px; font-size: 14px; }
  .monster-scene { display: none; }
  .login-panel { align-items: flex-start; justify-content: center; min-height: 0; padding: 24px 20px 30px; border-left: 1px solid var(--do-glass-border); border-top: 0; border-radius: 0 0 var(--login-radius) var(--login-radius); }
  .login-panel::before { clip-path: inset(0 round 0 0 var(--login-radius) var(--login-radius)); }
  .form-shell { max-width: 430px; }
  .form-heading { margin-bottom: 20px; }
  .form-heading h2 { font-size: 30px; }
  .form-heading span { font-size: 15px; }
  .login-form { gap: 16px; }
  .field { font-size: 14px; gap: 7px; }
  .field input { height: 50px; font-size: 15.5px; padding-inline: 14px; }
  .password-field input { padding-right: 52px; }
  .captcha-image-button { width: 102px; height: 50px; }
  .submit-button { height: 50px; font-size: 16px; }
}
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { transition: none !important; } }
@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
  .login-panel { background: rgba(249, 253, 255, .96); }
}
@media (prefers-reduced-transparency: reduce) {
  .login-panel { background: rgba(249, 253, 255, .98); }
  .login-panel::before { backdrop-filter: none; -webkit-backdrop-filter: none; }
}
.login-page { background:var(--do-bg); }
.login-page::before { box-shadow:none; border:1px solid var(--do-line); }
.login-visual { color:var(--do-ink); background:#eee9df; box-shadow:none; }
.login-visual::before, .login-visual::after, .login-panel::before { display:none; }
.login-brand-mark { object-fit:contain; padding:4px; background:transparent; box-shadow:none; border:0; }
.brand-pill strong, .visual-copy h1 { color:var(--do-ink); text-shadow:none; }
.visual-copy h1 { font-size:clamp(42px,5vw,68px); }
.visual-copy p { color:var(--do-muted); }
.login-panel { background:var(--do-surface); box-shadow:none; }
.form-heading h2 { font-size:clamp(28px,3vw,36px); }
.field input { border-color:var(--do-line); background:var(--do-surface); }
.submit-button, .submit-button:hover { background:var(--do-primary); box-shadow:none; }
.login-error { padding:12px; border-left:3px solid var(--do-danger); color:var(--do-danger); font-size:13px; line-height:1.6; }
</style>
