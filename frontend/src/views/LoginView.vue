<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Eye, EyeOff, Languages, LogIn, Sparkles } from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'

interface Mascot {
  id: string
  className: string
  title: string
  x: number
  y: number
  eye: 'light' | 'dark'
  mood: 'calm' | 'happy' | 'wow'
}

const router = useRouter()
const authStore = useAuthStore()
const stageRef = ref<HTMLElement | null>(null)
const showPassword = ref(false)
const loading = ref(false)
const remember = ref(true)
const pointer = reactive({ x: 0, y: 0, pressed: false })
const form = reactive({
  username: 'admin',
  password: '',
})

const mascots = ref<Mascot[]>([
  { id: 'sprout', className: 'sprout', title: 'ask', x: 25, y: 67, eye: 'dark', mood: 'calm' },
  { id: 'violet', className: 'violet', title: 'sql', x: 43, y: 42, eye: 'light', mood: 'calm' },
  { id: 'charcoal', className: 'charcoal', title: 'safe', x: 61, y: 53, eye: 'light', mood: 'calm' },
  { id: 'sunny', className: 'sunny', title: 'ok', x: 77, y: 63, eye: 'dark', mood: 'calm' },
])

function updatePointer(event: PointerEvent) {
  const rect = stageRef.value?.getBoundingClientRect()
  pointer.x = rect ? event.clientX - rect.left : event.clientX
  pointer.y = rect ? event.clientY - rect.top : event.clientY
}

function handlePointerDown(event: PointerEvent) {
  updatePointer(event)
  pointer.pressed = true
  mascots.value = mascots.value.map((mascot, index) => ({
    ...mascot,
    mood: index % 2 === 0 ? 'wow' : 'happy',
  }))
}

function handlePointerUp() {
  pointer.pressed = false
  mascots.value = mascots.value.map((mascot) => ({ ...mascot, mood: 'calm' }))
}

function eyeTransform(mascot: Mascot) {
  const rect = stageRef.value?.getBoundingClientRect()
  if (!rect) {
    return 'translate(0px, 0px)'
  }
  const mascotX = (mascot.x / 100) * rect.width
  const mascotY = (mascot.y / 100) * rect.height
  const dx = pointer.x - mascotX
  const dy = pointer.y - mascotY
  const length = Math.max(Math.hypot(dx, dy), 1)
  const distance = pointer.pressed ? 5.2 : 3.5
  return `translate(${(dx / length) * distance}px, ${(dy / length) * distance}px)`
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
  window.addEventListener('pointermove', updatePointer)
  window.addEventListener('pointerup', handlePointerUp)
  pointer.x = window.innerWidth * 0.24
  pointer.y = window.innerHeight * 0.5
})

onUnmounted(() => {
  window.removeEventListener('pointermove', updatePointer)
  window.removeEventListener('pointerup', handlePointerUp)
})
</script>

<template>
  <main class="login-page" :class="{ pressed: pointer.pressed }">
    <section
      ref="stageRef"
      class="story-stage"
      aria-label="DataOcean login illustration"
      @pointerdown="handlePointerDown"
    >
      <div class="brand-mark">
        <span class="brand-icon" />
        <strong>DataOcean</strong>
      </div>

      <div class="stage-title">
        <span>NL2SQL 工作台</span>
        <h1>自然语言查询入口</h1>
      </div>

      <div class="flower-field" aria-hidden="true">
        <i />
        <i />
        <i />
        <i />
        <i />
      </div>
      <div class="paper-plane" aria-hidden="true" />
      <div class="wall-sheet" aria-hidden="true" />

      <div class="mascot-line" aria-hidden="true">
        <div
          v-for="mascot in mascots"
          :key="mascot.id"
          class="mascot"
          :class="[mascot.className, mascot.mood]"
        >
          <span class="mascot-tag">{{ mascot.title }}</span>
          <span class="eye left" :class="mascot.eye">
            <i :style="{ transform: eyeTransform(mascot) }" />
          </span>
          <span class="eye right" :class="mascot.eye">
            <i :style="{ transform: eyeTransform(mascot) }" />
          </span>
          <span class="mouth" />
        </div>
      </div>
    </section>

    <section class="login-panel" aria-label="登录表单">
      <div class="panel-inner">
        <div class="mode-row">
          <span><Languages :size="14" /> 中文</span>
          <span><Sparkles :size="14" /> 轻快</span>
        </div>

        <header class="form-heading">
          <p>欢迎回来！</p>
          <h2>登录你的 DataOcean 工作台</h2>
        </header>

        <form class="login-form" @submit.prevent="submit">
          <div class="segment" aria-label="登录注册切换">
            <button type="button" class="active">登录</button>
            <button type="button">注册</button>
          </div>

          <label>
            <span>账号</span>
            <input v-model="form.username" type="text" autocomplete="username" placeholder="请输入账号" />
          </label>

          <label>
            <span>密码</span>
            <div class="password-input">
              <input
                v-model="form.password"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="current-password"
                placeholder="请输入密码"
              />
              <button type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
                <EyeOff v-if="showPassword" :size="18" />
                <Eye v-else :size="18" />
              </button>
            </div>
          </label>

          <div class="form-tools">
            <label class="checkbox">
              <input v-model="remember" type="checkbox" />
              <span>30 天内记住我</span>
            </label>
            <button type="button" class="link-button">忘记密码？</button>
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
  --ink: #1d2b35;
  --muted: #6d7a72;
  --line: rgba(63, 84, 72, 0.15);
  --sky: #8ed8ee;
  --leaf: #8bbd41;
  --paper: #fff5dc;
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(500px, 1.05fr) minmax(460px, 0.95fr);
  color: var(--ink);
  background:
    radial-gradient(circle at 86% 12%, rgba(117, 194, 222, 0.2), transparent 28%),
    linear-gradient(120deg, #f9fbf0 0%, #fffdf5 58%, #f4f8ee 100%);
  overflow: hidden;
}

.story-stage {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  cursor: default;
  background:
    radial-gradient(circle at 27% 11%, rgba(255, 255, 255, 0.98) 0 22px, transparent 23px),
    radial-gradient(circle at 31% 10%, rgba(255, 255, 255, 0.96) 0 34px, transparent 35px),
    radial-gradient(circle at 37% 12%, rgba(255, 255, 255, 0.98) 0 24px, transparent 25px),
    linear-gradient(178deg, #8dd8ef 0%, #cdeec8 31%, #fff6db 70%, #fffaf0 100%);
}

.story-stage::before {
  content: "";
  position: absolute;
  inset: 0;
  background:
    linear-gradient(rgba(255, 255, 255, 0.22) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.2) 1px, transparent 1px);
  background-size: 36px 36px;
  mask-image: linear-gradient(180deg, rgba(0, 0, 0, 0.8), transparent 72%);
}

.story-stage::after {
  content: "";
  position: absolute;
  left: -4%;
  right: -4%;
  bottom: 0;
  height: 18%;
  background:
    radial-gradient(ellipse at 18% 0%, rgba(140, 184, 63, 0.44), transparent 40%),
    linear-gradient(180deg, rgba(255, 247, 212, 0), #f4e8ba 74%);
}

.brand-mark {
  position: absolute;
  top: 44px;
  left: 52px;
  z-index: 8;
  display: inline-flex;
  align-items: center;
  gap: 11px;
  min-height: 42px;
  padding: 0 15px;
  border: 1px solid rgba(255, 255, 255, 0.58);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.42);
  color: #25404f;
  backdrop-filter: blur(14px);
  box-shadow: 0 14px 32px rgba(77, 119, 104, 0.13);
}

.brand-icon {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.65), transparent 38%),
    linear-gradient(135deg, #78c5f2, #9ac74d 58%, #f6d45c);
}

.brand-mark strong {
  font-size: 16px;
  line-height: 1;
}

.stage-title {
  position: absolute;
  top: 18%;
  left: 11%;
  z-index: 8;
}

.stage-title span {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  color: #56763a;
  background: rgba(255, 255, 255, 0.5);
  font-size: 13px;
  font-weight: 900;
}

.stage-title h1 {
  margin: 12px 0 0;
  max-width: 320px;
  color: #203745;
  font-size: clamp(28px, 2.6vw, 38px);
  line-height: 1.16;
  letter-spacing: 0;
  text-shadow: 0 2px 0 rgba(255, 255, 255, 0.65);
}

.wall-sheet {
  position: absolute;
  z-index: 1;
  top: 20%;
  right: 8%;
  bottom: 9%;
  width: 52%;
  border-radius: 34px 34px 10px 10px;
  background:
    radial-gradient(circle at 16% 16%, rgba(240, 180, 61, 0.16), transparent 18%),
    linear-gradient(90deg, rgba(192, 169, 105, 0.1) 1px, transparent 1px),
    linear-gradient(#fff7df, #fffaf0);
  background-size: auto, 30px 30px, auto;
  box-shadow:
    inset 0 0 0 1px rgba(158, 137, 91, 0.1),
    0 34px 80px rgba(84, 112, 80, 0.16);
  transform: rotate(0.7deg);
}

.paper-plane {
  position: absolute;
  top: 42%;
  left: 14%;
  z-index: 6;
  width: 58px;
  height: 34px;
  clip-path: polygon(0 47%, 100% 0, 72% 100%, 55% 62%);
  background:
    linear-gradient(135deg, #fff 0 42%, #d7ecff 43% 55%, #fff 56%);
  filter: drop-shadow(0 11px 18px rgba(60, 94, 103, 0.2));
  transform: rotate(-14deg);
}

.flower-field {
  position: absolute;
  z-index: 4;
  top: 25%;
  left: -58px;
  width: 208px;
  height: 400px;
  background:
    radial-gradient(circle at 32% 18%, #9bc849 0 27px, transparent 28px),
    radial-gradient(circle at 52% 34%, #79ad31 0 40px, transparent 41px),
    radial-gradient(circle at 30% 47%, #a6d353 0 38px, transparent 39px),
    radial-gradient(circle at 58% 62%, #83ba3e 0 34px, transparent 35px),
    radial-gradient(circle at 22% 72%, #9ccf4e 0 48px, transparent 49px);
  filter: drop-shadow(0 22px 28px rgba(75, 114, 56, 0.18));
  opacity: 0.92;
}

.flower-field i {
  position: absolute;
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background:
    radial-gradient(circle at 34% 30%, #e9f5ff 0 8px, transparent 9px),
    radial-gradient(circle at 58% 28%, #b8ddff 0 10px, transparent 11px),
    radial-gradient(circle at 67% 58%, #69a9e8 0 12px, transparent 13px),
    radial-gradient(circle at 35% 63%, #3e82d8 0 14px, transparent 15px),
    #86c6f4;
}

.flower-field i:nth-child(1) {
  top: 4px;
  left: 72px;
}

.flower-field i:nth-child(2) {
  top: 86px;
  left: 118px;
  transform: scale(0.88);
}

.flower-field i:nth-child(3) {
  top: 158px;
  left: 52px;
  transform: scale(1.08);
}

.flower-field i:nth-child(4) {
  top: 240px;
  left: 104px;
  transform: scale(0.82);
}

.flower-field i:nth-child(5) {
  top: 318px;
  left: 42px;
  transform: scale(0.92);
}

.mascot-line {
  position: absolute;
  z-index: 7;
  inset: auto 6% 8% 5%;
  height: min(44vw, 505px);
  min-height: 342px;
}

.mascot {
  position: absolute;
  bottom: 0;
  display: block;
  background: var(--body);
  border-radius: 42px 42px 0 0;
  box-shadow:
    inset -12px -12px 0 rgba(0, 0, 0, 0.05),
    0 18px 34px rgba(51, 65, 58, 0.13);
  transition:
    transform 180ms ease,
    border-radius 180ms ease,
    box-shadow 180ms ease;
}

.mascot::before {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: radial-gradient(circle at 25% 15%, rgba(255, 255, 255, 0.28), transparent 23%);
  pointer-events: none;
}

.mascot-tag {
  position: absolute;
  left: 50%;
  bottom: -28px;
  transform: translateX(-50%);
  color: rgba(34, 48, 56, 0.45);
  font-size: 12px;
  font-weight: 900;
}

.sprout {
  --body: #ffad78;
  left: 2%;
  width: clamp(176px, 28vw, 318px);
  height: clamp(142px, 23vw, 238px);
  border-radius: 999px 999px 0 0;
}

.violet {
  --body: #7563ea;
  left: 28%;
  width: clamp(136px, 18vw, 224px);
  height: clamp(286px, 44vw, 470px);
  transform: rotate(3.5deg);
}

.charcoal {
  --body: #2e3131;
  left: 52%;
  width: clamp(102px, 14vw, 158px);
  height: clamp(230px, 34vw, 356px);
  transform: rotate(1.5deg);
  box-shadow:
    inset -10px -10px 0 rgba(255, 255, 255, 0.03),
    0 18px 34px rgba(51, 65, 58, 0.13);
}

.sunny {
  --body: #efe16a;
  left: 65%;
  width: clamp(126px, 17vw, 198px);
  height: clamp(194px, 28vw, 300px);
  border-radius: 999px 999px 0 0;
}

.pressed .violet {
  transform: rotate(3.5deg) translateY(-10px);
}

.pressed .charcoal {
  transform: rotate(1.5deg) translateY(6px);
}

.pressed .sprout,
.pressed .sunny {
  transform: translateY(-5px);
}

.eye {
  position: absolute;
  top: 33%;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #fff;
  overflow: hidden;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.12);
}

.eye.dark {
  width: 16px;
  height: 16px;
  background: #283036;
  box-shadow: none;
}

.eye.left {
  left: 42%;
}

.eye.right {
  left: calc(42% + 34px);
}

.sprout .eye {
  top: 48%;
}

.sprout .eye.left {
  left: 46%;
}

.sprout .eye.right {
  left: calc(46% + 23px);
}

.sunny .eye {
  top: 29%;
}

.sunny .eye.left {
  left: 42%;
}

.sunny .eye.right {
  left: calc(42% + 29px);
}

.eye i {
  position: absolute;
  left: 7px;
  top: 7px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #222a31;
  transition: transform 75ms linear;
}

.eye.dark i {
  left: 4px;
  top: 4px;
  width: 8px;
  height: 8px;
  background: #0d141a;
}

.mouth {
  position: absolute;
  left: 43%;
  top: 46%;
  width: 76px;
  height: 21px;
  border-bottom: 5px solid #384048;
  transform: translateX(-2px);
}

.sprout .mouth,
.violet .mouth,
.charcoal .mouth {
  opacity: 0;
}

.mascot.happy .mouth {
  opacity: 1;
  width: 58px;
  height: 30px;
  border-bottom-color: currentColor;
  border-radius: 0 0 50% 50%;
}

.charcoal.happy .mouth,
.violet.happy .mouth {
  border-bottom-color: #fff;
}

.mascot.wow .mouth {
  opacity: 1;
  width: 24px;
  height: 24px;
  border: 4px solid currentColor;
  border-radius: 50%;
}

.charcoal.wow .mouth,
.violet.wow .mouth {
  border-color: #fff;
}

.login-panel {
  position: relative;
  z-index: 12;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: clamp(32px, 6vw, 86px);
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.82), rgba(250, 252, 245, 0.96)),
    radial-gradient(circle at 88% 10%, rgba(117, 194, 222, 0.14), transparent 28%);
  box-shadow: -30px 0 80px rgba(72, 91, 76, 0.12);
}

.panel-inner {
  width: min(100%, 500px);
}

.mode-row {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-bottom: 30px;
}

.mode-row span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 32px;
  padding: 0 13px;
  border: 1px solid #e2eadb;
  border-radius: 999px;
  color: #577a48;
  background: rgba(255, 255, 255, 0.74);
  font-size: 13px;
  font-weight: 900;
}

.form-heading {
  margin-bottom: 30px;
}

.form-heading p {
  margin: 0;
  color: #172536;
  font-size: clamp(34px, 4vw, 48px);
  font-weight: 950;
  line-height: 1.08;
  letter-spacing: 0;
}

.form-heading h2 {
  margin: 12px 0 0;
  color: #748176;
  font-size: 16px;
  font-weight: 800;
  letter-spacing: 0;
}

.login-form {
  display: grid;
  gap: 18px;
}

.segment {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 4px;
  border: 1px solid #e3eadf;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.62);
}

.segment button,
.submit-button,
.link-button {
  border: 0;
  cursor: pointer;
}

.segment button {
  height: 42px;
  border-radius: 8px;
  color: #65716b;
  background: transparent;
  font-weight: 900;
}

.segment button.active {
  color: #fff;
  background: #182536;
  box-shadow: 0 12px 25px rgba(24, 37, 54, 0.14);
}

.login-form label {
  display: grid;
  gap: 9px;
  color: #20303c;
  font-weight: 900;
}

.login-form input[type="text"],
.login-form input[type="password"] {
  width: 100%;
  height: 54px;
  border: 1px solid #dce6d9;
  border-radius: 10px;
  padding: 0 16px;
  color: #22313e;
  background: rgba(255, 255, 255, 0.86);
  outline: none;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease,
    background 160ms ease;
}

.login-form input::placeholder {
  color: #a2aaa2;
}

.login-form input:focus {
  border-color: #8fbd4d;
  background: #fff;
  box-shadow: 0 0 0 4px rgba(143, 189, 77, 0.14);
}

.password-input {
  position: relative;
}

.password-input input {
  padding-right: 52px;
}

.password-input button {
  position: absolute;
  top: 50%;
  right: 11px;
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 8px;
  color: #6f7d75;
  background: transparent;
  transform: translateY(-50%);
  cursor: pointer;
}

.password-input button:hover {
  background: #f0f5e9;
}

.form-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: #66756d;
  font-size: 14px;
}

.checkbox {
  display: flex !important;
  grid-template-columns: none;
  align-items: center;
  gap: 9px !important;
  font-weight: 800 !important;
}

.checkbox input {
  width: 18px;
  height: 18px;
  accent-color: #84b63f;
}

.link-button {
  color: #60813f;
  background: transparent;
  font-weight: 900;
}

.submit-button {
  height: 54px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  border-radius: 10px;
  color: #fff;
  background: #182536;
  font-weight: 950;
  box-shadow: 0 18px 34px rgba(24, 37, 54, 0.18);
}

.submit-button:disabled {
  cursor: wait;
  opacity: 0.68;
}

@media (max-width: 980px) {
  .login-page {
    grid-template-columns: 1fr;
    overflow: auto;
  }

  .story-stage {
    min-height: 400px;
  }

  .stage-title {
    top: 88px;
  }

  .stage-title h1 {
    font-size: 30px;
  }

  .wall-sheet {
    top: 18%;
    right: 5%;
    width: 54%;
  }

  .flower-field {
    top: 22%;
    transform: scale(0.76);
    transform-origin: top left;
  }

  .mascot-line {
    height: 280px;
    min-height: 280px;
    bottom: 34px;
  }

  .login-panel {
    min-height: auto;
    box-shadow: 0 -20px 60px rgba(72, 91, 76, 0.1);
  }
}

@media (max-width: 560px) {
  .story-stage {
    min-height: 285px;
  }

  .brand-mark {
    top: 20px;
    left: 20px;
    min-height: 38px;
  }

  .stage-title {
    display: none;
  }

  .flower-field,
  .paper-plane,
  .wall-sheet {
    display: none;
  }

  .mascot-line {
    inset: auto 12px 18px 12px;
    height: 146px;
    min-height: 146px;
  }

  .sprout {
    left: 0;
    width: 124px;
    height: 88px;
  }

  .violet {
    left: 30%;
    width: 78px;
    height: 142px;
  }

  .charcoal {
    left: 54%;
    width: 64px;
    height: 122px;
  }

  .sunny {
    left: 67%;
    width: 92px;
    height: 108px;
  }

  .mascot-tag {
    display: none;
  }

  .eye {
    width: 18px;
    height: 18px;
  }

  .eye i {
    left: 5px;
    top: 5px;
    width: 7px;
    height: 7px;
  }

  .eye.dark {
    width: 13px;
    height: 13px;
  }

  .eye.dark i {
    left: 3px;
    top: 3px;
  }

  .eye.right {
    left: calc(42% + 24px);
  }

  .login-panel {
    padding: 26px 20px 34px;
  }

  .mode-row {
    justify-content: flex-start;
    margin-bottom: 20px;
  }

  .form-heading p {
    font-size: 30px;
  }

  .form-heading {
    margin-bottom: 22px;
  }

  .login-form {
    gap: 14px;
  }

  .login-form input[type="text"],
  .login-form input[type="password"],
  .submit-button {
    height: 52px;
  }

  .form-tools {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
