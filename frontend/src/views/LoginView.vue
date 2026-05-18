<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import type { CSSProperties } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Eye, EyeOff, Languages, LogIn, Sparkles } from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'

interface Mascot {
  id: string
  className: string
  eyeMode: 'white' | 'dot'
  x: number
  y: number
  baseRotate: number
  clickMood: 'smile' | 'surprised' | 'blink'
}

const router = useRouter()
const authStore = useAuthStore()
const stageRef = ref<HTMLElement | null>(null)
const showPassword = ref(false)
const loading = ref(false)
const remember = ref(true)
const pointer = reactive({
  stageX: 0,
  stageY: 0,
  normalizedX: 0,
  normalizedY: 0,
  pressed: false,
})
const form = reactive({
  username: 'admin',
  password: '',
})

const mascots: Mascot[] = [
  { id: 'orange', className: 'orange', eyeMode: 'dot', x: 24, y: 72, baseRotate: -1, clickMood: 'surprised' },
  { id: 'violet', className: 'violet', eyeMode: 'white', x: 40, y: 51, baseRotate: 4, clickMood: 'blink' },
  { id: 'charcoal', className: 'charcoal', eyeMode: 'white', x: 60, y: 58, baseRotate: 2, clickMood: 'smile' },
  { id: 'yellow', className: 'yellow', eyeMode: 'dot', x: 78, y: 68, baseRotate: 0, clickMood: 'surprised' },
]

const sceneStyle = computed(
  () =>
    ({
      '--scene-rotate-x': `${clamp(-pointer.normalizedY * 5, -8, 8)}deg`,
      '--scene-rotate-y': `${clamp(pointer.normalizedX * 6, -8, 8)}deg`,
      '--scene-shift-x': `${pointer.normalizedX * 8}px`,
      '--scene-shift-y': `${pointer.normalizedY * 6}px`,
    }) as CSSProperties,
)

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}

function updatePointer(event: PointerEvent) {
  const rect = stageRef.value?.getBoundingClientRect()
  pointer.stageX = rect ? event.clientX - rect.left : event.clientX
  pointer.stageY = rect ? event.clientY - rect.top : event.clientY
  pointer.normalizedX = clamp((event.clientX / Math.max(window.innerWidth, 1) - 0.5) * 2, -1, 1)
  pointer.normalizedY = clamp((event.clientY / Math.max(window.innerHeight, 1) - 0.5) * 2, -1, 1)
}

function handlePointerDown(event: PointerEvent) {
  updatePointer(event)
  pointer.pressed = true
}

function handlePointerUp() {
  pointer.pressed = false
}

function seedPointer() {
  const rect = stageRef.value?.getBoundingClientRect()
  pointer.stageX = rect ? rect.width * 0.62 : window.innerWidth * 0.3
  pointer.stageY = rect ? rect.height * 0.48 : window.innerHeight * 0.48
  pointer.normalizedX = -0.12
  pointer.normalizedY = -0.04
}

function mascotStyle(mascot: Mascot) {
  const bodyTilt = clamp(pointer.normalizedX * 7 + pointer.normalizedY * 2, -10, 10)
  return {
    '--base-rotate': `${mascot.baseRotate}deg`,
    '--body-tilt': `${bodyTilt}deg`,
  } as CSSProperties
}

function pupilStyle(mascot: Mascot) {
  const rect = stageRef.value?.getBoundingClientRect()
  const width = rect?.width || Math.max(window.innerWidth * 0.5, 1)
  const height = rect?.height || Math.max(window.innerHeight, 1)
  const centerX = (mascot.x / 100) * width
  const centerY = (mascot.y / 100) * height
  const dx = pointer.stageX - centerX
  const dy = pointer.stageY - centerY
  const length = Math.max(Math.hypot(dx, dy), 1)
  const distance = pointer.pressed ? 6 : 4.4

  return {
    '--pupil-x': `${(dx / length) * distance}px`,
    '--pupil-y': `${(dy / length) * distance}px`,
    '--pupil-rotate': `${Math.atan2(dy, dx) * (180 / Math.PI)}deg`,
  } as CSSProperties
}

function expressionClass(mascot: Mascot) {
  return pointer.pressed ? mascot.clickMood : 'calm'
}

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

onMounted(() => {
  seedPointer()
  window.addEventListener('pointermove', updatePointer)
  window.addEventListener('pointerdown', handlePointerDown)
  window.addEventListener('pointerup', handlePointerUp)
  window.addEventListener('pointercancel', handlePointerUp)
  window.addEventListener('blur', handlePointerUp)
})

onUnmounted(() => {
  window.removeEventListener('pointermove', updatePointer)
  window.removeEventListener('pointerdown', handlePointerDown)
  window.removeEventListener('pointerup', handlePointerUp)
  window.removeEventListener('pointercancel', handlePointerUp)
  window.removeEventListener('blur', handlePointerUp)
})
</script>

<template>
  <main class="login-page" :class="{ pressed: pointer.pressed }">
    <section ref="stageRef" class="character-stage" aria-label="登录插画">
      <div class="brand-chip">
        <span />
        <strong>DataOcean</strong>
      </div>

      <div class="stage-copy">
        <p>你的数据海洋</p>
        <h1>从自然语言开始探索</h1>
      </div>

      <div class="mascot-scene" :style="sceneStyle" aria-hidden="true">
        <div class="stage-floor" />
        <div
          v-for="mascot in mascots"
          :key="mascot.id"
          class="mascot"
          :class="[mascot.className, mascot.eyeMode, expressionClass(mascot)]"
          :style="mascotStyle(mascot)"
        >
          <span class="eye eye-left" :style="pupilStyle(mascot)">
            <i />
          </span>
          <span class="eye eye-right" :style="pupilStyle(mascot)">
            <i />
          </span>
          <span class="mouth" />
        </div>
      </div>
    </section>

    <section class="form-panel" aria-label="登录表单">
      <div class="form-shell">
        <div class="mode-row">
          <button type="button">
            <Languages :size="14" />
            <span>中文</span>
          </button>
          <button type="button">
            <Sparkles :size="14" />
            <span>Playful</span>
          </button>
        </div>

        <header class="form-heading">
          <h2>欢迎回来！</h2>
          <p>请输入你的账号信息</p>
        </header>

        <form class="login-form" @submit.prevent="submit">
          <div class="segment-tabs" aria-label="登录注册切换">
            <button type="button" class="active">登录</button>
            <button type="button">注册</button>
          </div>

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

          <div class="form-tools">
            <label class="remember">
              <input v-model="remember" type="checkbox" />
              <span>30天内记住我</span>
            </label>
            <button type="button" class="text-button">忘记密码？</button>
          </div>

          <button class="submit-button" type="submit" :disabled="loading">
            <LogIn :size="18" />
            <span>{{ loading ? '登录中...' : '登录' }}</span>
          </button>

          <button type="button" class="register-link">没有账号？ 去注册</button>
        </form>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  --ink: #101827;
  --muted: #6c7280;
  --line: #e6e7ec;
  --purple: #6f35f2;
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(460px, 1fr) minmax(460px, 1fr);
  color: var(--ink);
  background: #ffffff;
  overflow: hidden;
}

.character-stage {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  background:
    linear-gradient(rgba(255, 255, 255, 0.075) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.075) 1px, transparent 1px),
    linear-gradient(135deg, #5524d6 0%, #7439f4 48%, #4a1ab1 100%);
  background-size: 30px 30px, 30px 30px, auto;
}

.character-stage::before {
  content: "";
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.09), transparent 36%),
    repeating-linear-gradient(0deg, transparent 0 94px, rgba(214, 191, 255, 0.08) 95px 96px);
  pointer-events: none;
}

.character-stage::after {
  content: "";
  position: absolute;
  right: -18px;
  top: 50%;
  z-index: 5;
  width: 44px;
  height: 68px;
  border-radius: 999px 0 0 999px;
  background: rgba(255, 255, 255, 0.86);
  transform: translateY(-50%);
}

.brand-chip {
  position: absolute;
  top: 46px;
  left: 52px;
  z-index: 6;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  height: 44px;
  padding: 0 17px 0 13px;
  border: 1px solid rgba(226, 211, 255, 0.32);
  border-radius: 13px;
  color: #ffffff;
  background: rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(14px);
  box-shadow: 0 16px 34px rgba(25, 7, 80, 0.16);
}

.brand-chip span {
  width: 24px;
  height: 24px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.18);
  box-shadow: inset 10px 0 18px rgba(255, 255, 255, 0.18);
}

.brand-chip strong {
  font-size: 17px;
  letter-spacing: 0;
}

.stage-copy {
  position: absolute;
  top: 15%;
  left: 12%;
  z-index: 6;
  max-width: 430px;
  color: #ffffff;
}

.stage-copy p {
  margin: 0 0 15px;
  font-size: 20px;
  font-weight: 900;
  color: rgba(255, 255, 255, 0.74);
}

.stage-copy h1 {
  margin: 0;
  font-size: 44px;
  line-height: 1.16;
  letter-spacing: 0;
  text-shadow: 0 3px 0 rgba(10, 7, 40, 0.28);
}

.mascot-scene {
  position: absolute;
  left: 6%;
  right: 8%;
  bottom: 8%;
  z-index: 7;
  height: 54%;
  min-height: 380px;
  transform:
    perspective(900px)
    translate3d(var(--scene-shift-x), var(--scene-shift-y), 0)
    rotateX(var(--scene-rotate-x))
    rotateY(var(--scene-rotate-y));
  transform-style: preserve-3d;
  transition: transform 180ms ease-out;
}

.stage-floor {
  position: absolute;
  left: 0;
  right: 1%;
  bottom: 0;
  height: 3px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.34);
  box-shadow: 0 24px 48px rgba(18, 8, 70, 0.22);
}

.mascot {
  position: absolute;
  bottom: 0;
  display: block;
  background: var(--body);
  transform-origin: 50% 100%;
  transform: rotate(calc(var(--base-rotate) + var(--body-tilt)));
  transition:
    transform 190ms ease-out,
    border-radius 160ms ease,
    filter 160ms ease;
  filter: drop-shadow(0 22px 22px rgba(22, 8, 70, 0.24));
}

.mascot::before {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(115deg, rgba(255, 255, 255, 0.2), transparent 42%);
  pointer-events: none;
}

.orange {
  --body: #ff9969;
  left: 1%;
  width: 45%;
  height: 43%;
  border-radius: 999px 999px 0 0;
}

.violet {
  --body: #7043ef;
  left: 29%;
  width: 32%;
  height: 92%;
  border-radius: 18px 18px 0 0;
}

.charcoal {
  --body: #252525;
  left: 54%;
  width: 23%;
  height: 70%;
  border-radius: 10px 10px 0 0;
  filter: drop-shadow(0 22px 22px rgba(22, 8, 70, 0.32));
}

.yellow {
  --body: #f0df55;
  left: 68%;
  width: 30%;
  height: 56%;
  border-radius: 999px 999px 0 0;
}

.pressed .orange,
.pressed .yellow {
  filter: drop-shadow(0 28px 26px rgba(22, 8, 70, 0.22));
}

.eye {
  --pupil-x: 0px;
  --pupil-y: 0px;
  --pupil-rotate: 0deg;
  position: absolute;
  top: 28%;
  width: 25px;
  height: 25px;
  border-radius: 50%;
  background: #ffffff;
  overflow: hidden;
  box-shadow: 0 3px 6px rgba(0, 0, 0, 0.18);
}

.eye-left {
  left: 42%;
}

.eye-right {
  left: calc(42% + 34px);
}

.eye i {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #1f2933;
  transform: translate(calc(-50% + var(--pupil-x)), calc(-50% + var(--pupil-y))) rotate(var(--pupil-rotate));
  transition: transform 75ms linear, height 120ms ease;
}

.eye i::after {
  content: "";
  position: absolute;
  top: 2px;
  left: 2px;
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.82);
}

.dot .eye {
  width: 26px;
  height: 26px;
  background: transparent;
  box-shadow: none;
  overflow: visible;
}

.dot .eye i {
  width: 15px;
  height: 15px;
  background: #303136;
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.22);
}

.orange .eye {
  top: 46%;
}

.orange .eye-left {
  left: 43%;
}

.orange .eye-right {
  left: calc(43% + 26px);
}

.violet .eye {
  top: 17%;
}

.charcoal .eye {
  top: 22%;
}

.yellow .eye {
  top: 29%;
}

.yellow .eye-left {
  left: 42%;
}

.yellow .eye-right {
  left: calc(42% + 32px);
}

.mouth {
  position: absolute;
  left: 50%;
  top: 48%;
  width: 56px;
  height: 18px;
  border-bottom: 4px solid currentColor;
  border-radius: 0 0 999px 999px;
  color: #2f3338;
  opacity: 0;
  transform: translateX(-50%);
  transition: opacity 140ms ease, width 140ms ease, height 140ms ease, border 140ms ease;
}

.charcoal .mouth,
.violet .mouth {
  color: #ffffff;
}

.yellow .mouth {
  top: 50%;
  width: 72px;
  opacity: 1;
}

.smile .mouth {
  opacity: 1;
  width: 60px;
  height: 28px;
}

.surprised .mouth {
  opacity: 1;
  width: 24px;
  height: 24px;
  border: 4px solid currentColor;
  border-radius: 50%;
}

.blink .eye i {
  height: 3px;
  border-radius: 999px;
}

.blink .mouth {
  opacity: 1;
  width: 50px;
  height: 24px;
}

.form-panel {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 64px;
  background: #ffffff;
}

.form-shell {
  width: 100%;
  max-width: 500px;
}

.mode-row {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-bottom: 28px;
}

.mode-row button {
  height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: 1px solid #ece7f6;
  border-radius: 999px;
  padding: 0 15px;
  color: #6041b9;
  background: #fbf8ff;
  font-size: 14px;
  font-weight: 800;
  cursor: pointer;
  box-shadow: 0 6px 16px rgba(95, 68, 153, 0.07);
}

.form-heading {
  margin-bottom: 28px;
}

.form-heading h2 {
  margin: 0;
  font-size: 36px;
  line-height: 1.18;
  letter-spacing: 0;
}

.form-heading p {
  margin: 13px 0 0;
  color: var(--muted);
  font-size: 16px;
  font-weight: 700;
}

.login-form {
  display: grid;
  gap: 18px;
}

.segment-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.segment-tabs button,
.submit-button,
.text-button,
.register-link {
  border: 0;
  cursor: pointer;
}

.segment-tabs button {
  height: 46px;
  border: 1px solid var(--line);
  border-radius: 12px;
  color: #303641;
  background: #ffffff;
  font-weight: 900;
  box-shadow: 0 5px 16px rgba(12, 18, 28, 0.04);
}

.segment-tabs button.active {
  color: #ffffff;
  border-color: #101827;
  background: #101827;
  box-shadow: 0 13px 26px rgba(16, 24, 39, 0.16);
}

.field {
  display: grid;
  gap: 9px;
  color: #141a24;
  font-size: 15px;
  font-weight: 900;
}

.field input {
  width: 100%;
  height: 54px;
  border: 1px solid var(--line);
  border-radius: 13px;
  padding: 0 16px;
  color: var(--ink);
  background: #ffffff;
  outline: none;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.field input::placeholder {
  color: #a7acb7;
}

.field input:focus {
  border-color: #7752f4;
  box-shadow: 0 0 0 4px rgba(119, 82, 244, 0.12);
}

.password-field {
  position: relative;
}

.password-field input {
  padding-right: 54px;
}

.password-field button {
  position: absolute;
  top: 50%;
  right: 10px;
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 10px;
  color: #7b8190;
  background: transparent;
  transform: translateY(-50%);
  cursor: pointer;
}

.password-field button:hover {
  color: #101827;
  background: #f5f3fb;
}

.form-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  color: #5f6673;
  font-size: 15px;
}

.remember {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  font-weight: 800;
}

.remember input {
  width: 19px;
  height: 19px;
  margin: 0;
  accent-color: #6f35f2;
}

.text-button {
  color: #6f35f2;
  background: transparent;
  font-weight: 900;
  white-space: nowrap;
}

.submit-button {
  height: 54px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  border-radius: 13px;
  color: #ffffff;
  background: #101827;
  font-weight: 950;
  box-shadow: 0 18px 34px rgba(16, 24, 39, 0.18);
}

.submit-button:disabled {
  cursor: wait;
  opacity: 0.68;
}

.register-link {
  height: 50px;
  border: 1px solid var(--line);
  border-radius: 13px;
  color: #6f35f2;
  background: #ffffff;
  font-weight: 900;
}

@media (max-width: 980px) {
  .login-page {
    min-height: 100vh;
    grid-template-columns: 1fr;
    overflow: auto;
  }

  .character-stage {
    min-height: 430px;
  }

  .character-stage::after {
    display: none;
  }

  .stage-copy {
    top: 100px;
    left: 40px;
  }

  .stage-copy h1 {
    font-size: 34px;
  }

  .mascot-scene {
    left: 40px;
    right: 40px;
    bottom: 32px;
    height: 250px;
    min-height: 250px;
  }

  .form-panel {
    min-height: auto;
    padding: 42px 32px 52px;
  }
}

@media (max-width: 560px) {
  .character-stage {
    min-height: 330px;
  }

  .brand-chip {
    top: 22px;
    left: 20px;
    height: 40px;
  }

  .stage-copy {
    top: 82px;
    left: 22px;
    right: 22px;
  }

  .stage-copy p {
    margin-bottom: 8px;
    font-size: 16px;
  }

  .stage-copy h1 {
    font-size: 28px;
  }

  .mascot-scene {
    left: 18px;
    right: 18px;
    bottom: 24px;
    height: 165px;
    min-height: 165px;
  }

  .eye {
    width: 18px;
    height: 18px;
  }

  .eye i {
    width: 8px;
    height: 8px;
  }

  .dot .eye {
    width: 19px;
    height: 19px;
  }

  .dot .eye i {
    width: 11px;
    height: 11px;
  }

  .eye-right {
    left: calc(42% + 24px);
  }

  .orange .eye-right {
    left: calc(43% + 20px);
  }

  .yellow .eye-right {
    left: calc(42% + 22px);
  }

  .mouth {
    width: 38px;
  }

  .yellow .mouth {
    width: 42px;
  }

  .form-panel {
    padding: 30px 20px 38px;
  }

  .mode-row {
    justify-content: flex-start;
    margin-bottom: 22px;
  }

  .form-heading h2 {
    font-size: 30px;
  }

  .login-form {
    gap: 15px;
  }

  .segment-tabs button,
  .field input,
  .submit-button {
    height: 52px;
  }

  .form-tools {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
  }
}
</style>
