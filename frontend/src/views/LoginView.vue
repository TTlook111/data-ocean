<script setup lang="ts">
import { reactive, ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Eye, EyeOff, LogIn } from 'lucide-vue-next'
import { gsap } from 'gsap'
import { useAuthStore } from '../stores/auth'
import { getCaptcha } from '../api/auth'
import BrandMark from '../components/common/BrandMark.vue'
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
      // 前景拱形角色移动整只点状眼睛，高角色只移动眼白内的瞳孔。
      const target = element.closest('.monster-arch, .monster-dome') ? element : element.querySelector<HTMLElement>('em') ?? element
      return { element, target, x: gsap.quickTo(target, 'x', follow), y: gsap.quickTo(target, 'y', follow) }
    })
    bodies = Array.from(root.querySelectorAll<HTMLElement>('.monster')).map((element, index) => {
      const base = element.classList.contains('monster-tower') ? -3 : 0
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
    const result = await authStore.login({
      username: form.username.trim(),
      password: form.password,
      captchaKey: captchaKey.value,
      captchaCode: form.captchaCode.trim()
    }, () => !disposed)
    if (disposed || authStore.token !== result.data.token) return
    ElMessage.success('登录成功')
    await router.replace('/')
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
        <BrandMark class="login-brand-mark" aria-hidden="true" />
        <strong>DataOcean</strong>
      </div>

      <div class="visual-copy">
        <h1>让数据<br />回答问题</h1>
        <p>先理解业务，再让数据回答。</p>
      </div>

      <div class="monster-scene" aria-hidden="true">
        <span class="monster monster-arch">
          <i class="eye eye-left"><em></em></i>
          <i class="eye eye-right"><em></em></i>
        </span>
        <span class="monster monster-tower">
          <i class="eye eye-left"><em></em></i>
          <i class="eye eye-right"><em></em></i>
        </span>
        <span class="monster monster-column">
          <i class="eye eye-left"><em></em></i>
          <i class="eye eye-right"><em></em></i>
        </span>
        <span class="monster monster-dome">
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
          <!-- 原生必填校验把焦点带到缺失字段，脚本仍保留完整提交校验与身份保护。 -->
          <label class="field">
            <span>账号</span>
            <input
              v-model="form.username"
              type="text"
              autocomplete="username"
              required
              placeholder="请输入账号"
            />
          </label>

          <!-- 密码必填沿用浏览器语义，不读取或裁剪用户实际提交的密码字节。 -->
          <label class="field">
            <span>密码</span>
            <div class="password-field">
              <input
                v-model="form.password"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="current-password"
                required
                placeholder="请输入密码"
              />
              <button type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
                <EyeOff v-if="showPassword" :size="19" />
                <Eye v-else :size="19" />
              </button>
            </div>
          </label>

          <!-- 验证码保留原刷新与错误描述，缺值时同样定位到当前字段。 -->
          <div class="field">
            <label for="login-captcha">验证码</label>
            <div class="captcha-field">
              <input id="login-captcha" v-model="form.captchaCode" type="text" autocomplete="off" required
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
  /* 登录保持独立双栏入口，画布与表单复用全站主题，不再叠加历史玻璃覆盖。 */
  position: relative;
  isolation: isolate;
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
  background: var(--do-bg);
}
.login-page::before {
  /* 单一细边界围合入口，视觉角色不需要额外发光或悬浮阴影。 */
  position: absolute;
  z-index: -1;
  inset: clamp(14px, 2.4vh, 30px) clamp(18px, 2.6vw, 40px);
  inset: var(--login-gutter-block) var(--login-gutter-inline);
  border-radius: var(--login-radius);
  border: 1px solid var(--do-line);
  content: "";
  pointer-events: none;
}
.login-visual {
  /* 以品牌浅色衬托互动角色，边界与表单沿用全站内容面。 */
  position: relative;
  isolation: isolate;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: clamp(30px, 4.6dvh, 52px) clamp(32px, 4.4vw, 64px) clamp(34px, 5.2dvh, 60px);
  overflow: hidden;
  border-right: 1px solid var(--do-line);
  border-radius: var(--login-radius) 0 0 var(--login-radius);
  color: var(--do-ink);
  background: color-mix(in srgb, var(--do-brand-lilac) 20%, var(--do-bg));
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}
.brand-pill { position: relative; z-index: 1; display: flex; align-items: center; gap: 14px; width: fit-content; }
/* 统一品牌标识保留独立尺寸，旁边的产品名已提供可访问名称。 */
.login-brand-mark { width: 56px; height: 56px; }
.brand-pill strong { color: var(--do-ink); font-size: clamp(24px, 1.9vw, 30px); font-weight: 700; letter-spacing: -1.2px; }
.visual-copy { position: relative; z-index: 1; margin-top: clamp(20px, 3.6dvh, 40px); }
/* 产品主张保持简短，正文使用同一套可读文字色。 */
.visual-copy h1 { margin: 0; color: var(--do-ink); font-size: clamp(42px, 5vw, 68px); font-weight: 760; line-height: 1.08; letter-spacing: -2px; }
.visual-copy p { margin: clamp(8px, 1.2dvh, 12px) 0 0; color: var(--do-muted); font-size: clamp(15px, 1.1vw, 17px); font-weight: 400; line-height: 1.5; }
/* 共用基线和四种轮廓，眼睛与身体保留原来的指针跟随区域。 */
.monster-scene { position: relative; z-index: 1; width: min(94%, 460px, 50dvh); aspect-ratio: 1.8; flex-shrink: 0; margin: auto 0 0; container-type: inline-size; }
.monster { position: absolute; bottom: 0; display: block; transform-origin: center bottom; }
.monster-arch { z-index: 5; left: 1%; width: 41%; height: 48%; border-radius: 110px 110px 3px 3px; background: var(--do-brand-warm); }
.monster-tower { z-index: 2; left: 28%; width: 26%; height: 96%; border-radius: 58px 58px 3px 3px; background: var(--do-primary); }
.monster-column { z-index: 4; left: 49%; width: 22%; height: 72%; border-radius: 12px 28px 3px 3px; background: var(--do-ink); }
.monster-dome { z-index: 6; left: 65%; width: 33%; height: 52%; border-radius: 100px 100px 3px 3px; background: var(--do-brand-lilac); }
.eye { position: absolute; display: block; width: 16px; height: 16px; width: clamp(10px, 4cqw, 20px); height: clamp(10px, 4cqw, 20px); overflow: hidden; border-radius: 50%; background: var(--do-on-primary); }
.eye em { position: absolute; top: calc(50% - 3.5px); left: calc(50% - 3.5px); width: 7px; height: 7px; border-radius: 50%; background: var(--do-ink); }
.monster-tower .eye { top: 21%; }
.monster-tower .eye-left { left: 42%; }
.monster-tower .eye-right { left: 65%; }
.monster-column .eye { top: 28%; }
.monster-column .eye-left { left: 39%; }
.monster-column .eye-right { left: 65%; }
.monster-arch .eye, .monster-dome .eye { width: 11px; height: 11px; width: clamp(7px, 2.2cqw, 11px); height: clamp(7px, 2.2cqw, 11px); background: var(--do-ink); overflow: visible; }
.monster-arch .eye em, .monster-dome .eye em { display: none; }
.monster-arch .eye { top: 49%; }
.monster-arch .eye-left { left: 46%; }
.monster-arch .eye-right { left: 58%; }
.monster-dome .eye { top: 31%; }
.monster-dome .eye-left { left: 42%; }
.monster-dome .eye-right { left: 58%; }
.mouth { position: absolute; left: 31%; top: 52%; width: 38%; height: 12%; border-bottom: 2px solid var(--do-ink); border-radius: 0 0 40px 40px; transition: height 180ms ease; }
.is-pointer-down .mouth { height: 18%; }
.ground-line { position: absolute; z-index: 7; inset: auto 0 -2px; height: 2px; border-radius: 50%; background: var(--do-line-strong); }
/* 认证表单使用不透明表面，避免透明材质降低字段与错误信息的对比。 */
.login-panel { position: relative; isolation: isolate; display: flex; align-items: center; align-items: safe center; justify-content: center; min-height: 0; padding: clamp(28px, 3.6dvh, 46px) clamp(28px, 3.6vw, 56px) clamp(40px, 5.2dvh, 64px); border-left: 0; border-radius: 0 var(--login-radius) var(--login-radius) 0; background: var(--do-surface); }
.form-shell { width: 100%; max-width: 430px; }
.form-heading { margin-bottom: clamp(20px, 2.8dvh, 28px); }
/* 登录标题与主要按钮同属主任务，不与左侧产品主张争抢尺寸。 */
.form-heading h2 { margin: 0; font-size: clamp(28px, 3vw, 36px); font-weight: 700; line-height: 1.15; letter-spacing: -.8px; }
.form-heading span { display: block; margin-top: 8px; color: var(--do-muted); font-size: clamp(15px, 1.05vw, 16px); line-height: 1.5; font-weight: 400; }
.login-form { display: grid; gap: clamp(16px, 2.2dvh, 20px); }
.field { display: grid; gap: 8px; font-size: 14.5px; font-weight: 500; min-width: 0; }
/* 控件边界与聚焦反馈来自统一令牌，保留原有键盘和密码显隐行为。 */
.field input { width: 100%; min-width: 0; height: 50px; border: 1px solid var(--do-line); border-radius: 9px; padding: 0 16px; color: var(--do-ink); background: var(--do-surface); font-size: 15.5px; font-weight: 400; transition: border-color 160ms ease, box-shadow 160ms ease, background-color 160ms ease; }
.field input::placeholder { color: var(--do-muted); }
.field input:focus { outline: none; border-color: var(--do-primary-strong); box-shadow: 0 0 0 3px var(--do-primary-soft); }
.password-field { position: relative; }
.password-field input { padding-right: 54px; }
.password-field button { position: absolute; inset: 5px 6px 5px auto; width: 40px; display: grid; place-items: center; border: 0; border-radius: 7px; color: var(--do-muted); background: transparent; cursor: pointer; }
.password-field button:hover { color: var(--do-primary-strong); background: var(--do-primary-soft); }
.captcha-field { display: flex; align-items: stretch; gap: 10px; }
.captcha-field input { flex: 1 1 0; }
/* 验证码保持真实图像与刷新入口，仅统一其表面和边界。 */
.captcha-image-button { width: clamp(108px, 9.2vw, 136px); height: 50px; flex-shrink: 0; padding: 0; overflow: hidden; border: 1px solid var(--do-line); border-radius: 8px; color: var(--do-muted); background: var(--do-surface); cursor: pointer; }
.captcha-img { width: 100%; height: 100%; object-fit: contain; }
.captcha-error { margin: 0; color: var(--do-danger); font-size: 12.5px; line-height: 1.5; font-weight: 400; }
/* 主操作、悬停与反白文字使用对应的品牌令牌。 */
.submit-button { display: flex; align-items: center; justify-content: center; gap: 10px; height: 50px; margin-top: 2px; border: 1px solid var(--do-primary); border-radius: 10px; color: var(--do-on-primary); background: var(--do-primary); font-size: 16px; font-weight: 650; cursor: pointer; transition: background 160ms ease, transform 160ms ease; }
.submit-button:hover { background: var(--do-primary-strong); }
.submit-button:active { transform: translateY(1px); }
.submit-button:disabled, .captcha-image-button:disabled { opacity: .65; cursor: wait; }
/* 包括密码显隐和验证码按钮在内，键盘焦点保持可见。 */
button:focus-visible { outline: 3px solid var(--do-primary); outline-offset: 3px; }
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
.login-error { padding:12px; border-left:3px solid var(--do-danger); color:var(--do-danger); font-size:13px; line-height:1.6; }
</style>
