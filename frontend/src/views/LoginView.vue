<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Eye, EyeOff, Languages, Sparkles } from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'

interface Mascot {
  id: string
  className: string
  label: string
  x: number
  y: number
  color: string
  eye: 'light' | 'dark'
  mood: 'calm' | 'smile' | 'surprise'
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
  {
    id: 'orange',
    className: 'orange',
    label: 'asker',
    x: 24,
    y: 68,
    color: '#ffb26f',
    eye: 'dark',
    mood: 'calm',
  },
  {
    id: 'violet',
    className: 'violet',
    label: 'schema',
    x: 42,
    y: 39,
    color: '#8c76f6',
    eye: 'light',
    mood: 'calm',
  },
  {
    id: 'ink',
    className: 'ink',
    label: 'guard',
    x: 61,
    y: 51,
    color: '#2b2d30',
    eye: 'light',
    mood: 'calm',
  },
  {
    id: 'yellow',
    className: 'yellow',
    label: 'answer',
    x: 76,
    y: 64,
    color: '#eadf5d',
    eye: 'dark',
    mood: 'calm',
  },
])

const pointerStyle = computed(() => ({
  '--mx': `${pointer.x}px`,
  '--my': `${pointer.y}px`,
}))

function updatePointer(event: PointerEvent) {
  if (!stageRef.value) {
    pointer.x = event.clientX
    pointer.y = event.clientY
    return
  }
  const rect = stageRef.value.getBoundingClientRect()
  pointer.x = event.clientX - rect.left
  pointer.y = event.clientY - rect.top
}

function handlePointerDown(event: PointerEvent) {
  updatePointer(event)
  pointer.pressed = true
  mascots.value = mascots.value.map((mascot, index) => ({
    ...mascot,
    mood: index % 2 === 0 ? 'surprise' : 'smile',
  }))
}

function handlePointerUp() {
  pointer.pressed = false
  mascots.value = mascots.value.map((mascot) => ({ ...mascot, mood: 'calm' }))
}

function eyeTransform(mascot: Mascot) {
  if (!stageRef.value) {
    return 'translate(0px, 0px)'
  }
  const rect = stageRef.value.getBoundingClientRect()
  const mascotX = (mascot.x / 100) * rect.width
  const mascotY = (mascot.y / 100) * rect.height
  const dx = pointer.x - mascotX
  const dy = pointer.y - mascotY
  const length = Math.max(Math.hypot(dx, dy), 1)
  const distance = pointer.pressed ? 5 : 3.4
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
  pointer.x = window.innerWidth * 0.25
  pointer.y = window.innerHeight * 0.45
})

onUnmounted(() => {
  window.removeEventListener('pointermove', updatePointer)
  window.removeEventListener('pointerup', handlePointerUp)
})
</script>

<template>
  <main class="login-page" :class="{ pressed: pointer.pressed }" :style="pointerStyle">
    <section
      ref="stageRef"
      class="story-stage"
      aria-label="DataOcean login mascots"
      @pointerdown="handlePointerDown"
    >
      <div class="sky" />
      <div class="paper-plane" />
      <div class="vine vine-a" />
      <div class="vine vine-b" />
      <div class="hydrangea hydrangea-a" />
      <div class="hydrangea hydrangea-b" />
      <div class="hydrangea hydrangea-c" />

      <div class="brand-mark">
        <span class="brand-dot" />
        <div>
          <strong>DataOcean</strong>
          <span>可信数据入口</span>
        </div>
      </div>

      <div class="stage-copy">
        <p>NL2SQL</p>
        <h1>让数据查询<br />像提问一样自然</h1>
      </div>

      <div
        v-for="mascot in mascots"
        :key="mascot.id"
        class="mascot"
        :class="[mascot.className, mascot.mood]"
      >
        <span class="mascot-label">{{ mascot.label }}</span>
        <span class="eye left" :class="mascot.eye">
          <i :style="{ transform: eyeTransform(mascot) }" />
        </span>
        <span class="eye right" :class="mascot.eye">
          <i :style="{ transform: eyeTransform(mascot) }" />
        </span>
        <span class="mouth" />
      </div>
    </section>

    <section class="login-panel" aria-label="登录表单">
      <div class="mode-row">
        <span><Languages :size="14" /> 中文</span>
        <span><Sparkles :size="14" /> Playful</span>
      </div>

      <div class="form-heading">
        <p>欢迎回来！</p>
        <h2>请输入你的账号信息</h2>
      </div>

      <form class="login-form" @submit.prevent="submit">
        <div class="segment">
          <button type="button" class="active">登录</button>
          <button type="button">注册</button>
        </div>

        <label>
          <span>账号</span>
          <input v-model="form.username" type="text" autocomplete="username" placeholder="admin" />
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
            <span>30天内记住我</span>
          </label>
          <button type="button" class="link-button">忘记密码？</button>
        </div>

        <button class="submit-button" type="submit" :disabled="loading">
          {{ loading ? '登录中...' : '登录' }}
        </button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  --ink: #1e2b39;
  --muted: #6f7a72;
  --line: rgba(43, 62, 55, 0.14);
  --leaf: #84b63f;
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(420px, 1.05fr) minmax(420px, 0.95fr);
  background: #fbfcf4;
  overflow: hidden;
}

.story-stage {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  cursor: default;
  background:
    linear-gradient(rgba(255, 248, 223, 0.62) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 248, 223, 0.62) 1px, transparent 1px),
    radial-gradient(circle at 5% 18%, rgba(165, 217, 77, 0.5), transparent 24%),
    radial-gradient(circle at 22% 10%, rgba(126, 204, 232, 0.8), transparent 26%),
    linear-gradient(160deg, #8bd5ec 0%, #fff5cf 42%, #fffaf0 100%);
  background-size:
    34px 34px,
    34px 34px,
    auto,
    auto,
    auto;
}

.story-stage::before {
  content: "";
  position: absolute;
  inset: 9% 8% 0 21%;
  border-radius: 42px 42px 0 0;
  background:
    linear-gradient(120deg, rgba(242, 179, 64, 0.22), transparent 22%),
    linear-gradient(90deg, rgba(181, 148, 67, 0.12) 1px, transparent 1px),
    linear-gradient(#fff5d6, #fff9e8);
  background-size: auto, 28px 28px, auto;
  box-shadow:
    inset 0 0 0 1px rgba(171, 152, 105, 0.12),
    0 28px 90px rgba(95, 122, 85, 0.18);
  transform: rotate(0.4deg);
}

.story-stage::after {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 18%;
  background:
    linear-gradient(90deg, rgba(134, 177, 72, 0.34), transparent 18%),
    linear-gradient(180deg, transparent, rgba(254, 243, 192, 0.9));
}

.sky {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 26% 10%, rgba(255, 255, 255, 0.95) 0 20px, transparent 22px),
    radial-gradient(circle at 31% 9%, rgba(255, 255, 255, 0.95) 0 30px, transparent 32px),
    radial-gradient(circle at 36% 11%, rgba(255, 255, 255, 0.95) 0 22px, transparent 24px),
    linear-gradient(115deg, transparent 0 61%, rgba(255, 255, 255, 0.85) 61.2% 61.5%, transparent 61.7%);
  opacity: 0.78;
}

.brand-mark {
  position: absolute;
  top: 46px;
  left: 54px;
  z-index: 3;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 10px 15px;
  border: 1px solid rgba(255, 255, 255, 0.48);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.3);
  color: #29404f;
  backdrop-filter: blur(12px);
}

.brand-dot {
  width: 24px;
  height: 24px;
  border-radius: 7px;
  background: linear-gradient(135deg, #9dccff, #94c957);
  box-shadow: 0 8px 22px rgba(89, 139, 139, 0.22);
}

.brand-mark strong,
.brand-mark span {
  display: block;
}

.brand-mark strong {
  line-height: 1.1;
}

.brand-mark span {
  color: rgba(42, 64, 79, 0.68);
  font-size: 12px;
}

.stage-copy {
  position: absolute;
  top: 16%;
  left: 12%;
  z-index: 3;
  color: #1e3441;
}

.stage-copy p {
  margin: 0 0 12px;
  color: #6a9546;
  font-weight: 800;
  letter-spacing: 0;
}

.stage-copy h1 {
  margin: 0;
  font-size: clamp(32px, 4vw, 58px);
  line-height: 1.12;
  letter-spacing: 0;
  text-shadow: 0 2px 0 rgba(255, 255, 255, 0.78);
}

.paper-plane {
  position: absolute;
  top: 21%;
  left: 17%;
  z-index: 3;
  width: 58px;
  height: 30px;
  clip-path: polygon(0 45%, 100% 0, 74% 100%, 56% 62%);
  background: #fff;
  box-shadow: 0 6px 20px rgba(56, 94, 102, 0.2);
  transform: rotate(-16deg);
}

.vine {
  position: absolute;
  z-index: 2;
  border-radius: 999px;
  background: rgba(126, 151, 80, 0.46);
  transform-origin: top;
}

.vine-a {
  top: 7%;
  right: 20%;
  width: 7px;
  height: 170px;
  transform: rotate(18deg);
}

.vine-b {
  top: 5%;
  right: 15%;
  width: 5px;
  height: 120px;
  transform: rotate(-11deg);
}

.hydrangea {
  position: absolute;
  z-index: 2;
  width: 96px;
  height: 96px;
  border-radius: 50%;
  background:
    radial-gradient(circle at 35% 35%, #d7ecff 0 8px, transparent 9px),
    radial-gradient(circle at 55% 28%, #a9d4ff 0 10px, transparent 11px),
    radial-gradient(circle at 64% 56%, #6baae7 0 13px, transparent 14px),
    radial-gradient(circle at 34% 64%, #3d82d5 0 15px, transparent 16px),
    #8dc6f4;
  filter: drop-shadow(0 12px 18px rgba(64, 122, 80, 0.18));
}

.hydrangea-a {
  top: 14%;
  left: 3%;
}

.hydrangea-b {
  top: 25%;
  left: 10%;
  transform: scale(0.85);
}

.hydrangea-c {
  top: 34%;
  left: -2%;
  transform: scale(1.12);
}

.mascot {
  position: absolute;
  z-index: 5;
  display: block;
  border-radius: 44px 44px 0 0;
  background: var(--body);
  box-shadow: inset -12px -12px 0 rgba(0, 0, 0, 0.04);
  transition:
    transform 180ms ease,
    border-radius 180ms ease;
}

.mascot::before {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: radial-gradient(circle at 26% 18%, rgba(255, 255, 255, 0.25), transparent 22%);
  pointer-events: none;
}

.mascot-label {
  position: absolute;
  left: 50%;
  bottom: -30px;
  transform: translateX(-50%);
  color: rgba(32, 47, 55, 0.48);
  font-size: 12px;
  font-weight: 700;
}

.orange {
  --body: #ffad74;
  left: 8%;
  bottom: 10%;
  width: clamp(170px, 27vw, 305px);
  height: clamp(140px, 24vw, 245px);
  border-radius: 999px 999px 0 0;
}

.violet {
  --body: #8067f2;
  left: 27%;
  bottom: 10%;
  width: clamp(145px, 20vw, 245px);
  height: clamp(330px, 50vw, 520px);
  transform: rotate(4deg);
}

.ink {
  --body: #2d3031;
  left: 52%;
  bottom: 10%;
  width: clamp(110px, 16vw, 170px);
  height: clamp(260px, 38vw, 390px);
  transform: rotate(2deg);
  box-shadow: inset -10px -10px 0 rgba(255, 255, 255, 0.03);
}

.yellow {
  --body: #efe15f;
  left: 64%;
  bottom: 10%;
  width: clamp(132px, 18vw, 205px);
  height: clamp(210px, 30vw, 320px);
  border-radius: 999px 999px 0 0;
}

.pressed .violet {
  transform: rotate(4deg) translateY(-8px);
}

.pressed .ink {
  transform: rotate(2deg) translateY(4px);
}

.eye {
  position: absolute;
  top: 34%;
  width: 23px;
  height: 23px;
  border-radius: 50%;
  background: #fff;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
}

.eye.dark {
  width: 15px;
  height: 15px;
  background: #2a3034;
}

.eye.left {
  left: 42%;
}

.eye.right {
  left: calc(42% + 34px);
}

.orange .eye {
  top: 45%;
}

.orange .eye.left {
  left: 46%;
}

.orange .eye.right {
  left: calc(46% + 22px);
}

.yellow .eye {
  top: 29%;
}

.yellow .eye.left {
  left: 43%;
}

.yellow .eye.right {
  left: calc(43% + 27px);
}

.eye i {
  position: absolute;
  left: 7px;
  top: 7px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #262d34;
  transition: transform 80ms linear;
}

.eye.dark i {
  left: 4px;
  top: 4px;
  width: 7px;
  height: 7px;
  background: #111821;
}

.mouth {
  position: absolute;
  left: 42%;
  top: 46%;
  width: 86px;
  height: 24px;
  border-bottom: 5px solid #39404a;
  transform: translateX(-2px);
}

.orange .mouth,
.violet .mouth,
.ink .mouth {
  opacity: 0;
}

.mascot.smile .mouth {
  opacity: 1;
  width: 64px;
  height: 32px;
  border-bottom-color: #fff;
  border-radius: 0 0 50% 50%;
}

.mascot.surprise .mouth {
  opacity: 1;
  width: 24px;
  height: 24px;
  border: 4px solid currentColor;
  border-radius: 50%;
}

.login-panel {
  position: relative;
  z-index: 10;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(28px, 7vw, 84px);
  background:
    radial-gradient(circle at 84% 12%, rgba(116, 198, 228, 0.15), transparent 26%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(250, 252, 245, 0.96));
  box-shadow: -28px 0 80px rgba(78, 90, 78, 0.12);
}

.mode-row {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-bottom: 24px;
}

.mode-row span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 32px;
  padding: 0 14px;
  border: 1px solid #e6eadc;
  border-radius: 999px;
  color: #50724d;
  background: rgba(255, 255, 255, 0.75);
  font-size: 13px;
  font-weight: 800;
}

.form-heading {
  margin-bottom: 28px;
}

.form-heading p {
  margin: 0;
  color: #1f2b38;
  font-size: clamp(32px, 4vw, 46px);
  font-weight: 900;
  line-height: 1.05;
  letter-spacing: 0;
}

.form-heading h2 {
  margin: 14px 0 0;
  color: #79847d;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0;
}

.login-form {
  display: grid;
  gap: 17px;
}

.segment {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 4px;
}

.segment button,
.submit-button,
.link-button {
  border: 0;
  cursor: pointer;
}

.segment button {
  height: 48px;
  border: 1px solid #dfe7dc;
  border-radius: 8px;
  color: #59645f;
  background: rgba(255, 255, 255, 0.72);
  font-weight: 800;
}

.segment button.active {
  color: #fff;
  background: #182536;
  border-color: #182536;
  box-shadow: 0 14px 30px rgba(28, 39, 55, 0.16);
}

.login-form label {
  display: grid;
  gap: 9px;
  color: #202c38;
  font-weight: 800;
}

.login-form input[type="text"],
.login-form input[type="password"] {
  width: 100%;
  height: 54px;
  border: 1px solid #dde7da;
  border-radius: 8px;
  padding: 0 16px;
  color: #22313e;
  background: rgba(255, 255, 255, 0.82);
  outline: none;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease;
}

.login-form input:focus {
  border-color: #91c95f;
  box-shadow: 0 0 0 4px rgba(145, 201, 95, 0.15);
}

.password-input {
  position: relative;
}

.password-input input {
  padding-right: 50px;
}

.password-input button {
  position: absolute;
  top: 50%;
  right: 12px;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 0;
  color: #718077;
  background: transparent;
  transform: translateY(-50%);
  cursor: pointer;
}

.form-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: #65736f;
  font-size: 14px;
}

.checkbox {
  display: flex !important;
  grid-template-columns: none;
  align-items: center;
  gap: 9px !important;
  font-weight: 700 !important;
}

.checkbox input {
  width: 18px;
  height: 18px;
  accent-color: #86b957;
}

.link-button {
  color: #6d8a48;
  background: transparent;
  font-weight: 800;
}

.submit-button {
  height: 52px;
  border-radius: 8px;
  color: #fff;
  background: #182536;
  font-weight: 900;
  box-shadow: 0 16px 34px rgba(24, 37, 54, 0.16);
}

.submit-button:disabled {
  cursor: wait;
  opacity: 0.68;
}

@media (max-width: 920px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .story-stage {
    min-height: 45vh;
  }

  .stage-copy {
    top: 18%;
  }

  .login-panel {
    min-height: auto;
    box-shadow: 0 -20px 60px rgba(78, 90, 78, 0.12);
  }
}

@media (max-width: 560px) {
  .story-stage {
    min-height: 280px;
  }

  .brand-mark {
    top: 18px;
    left: 18px;
    z-index: 8;
    padding: 8px 10px;
  }

  .brand-dot {
    width: 20px;
    height: 20px;
  }

  .stage-copy {
    display: none;
  }

  .hydrangea {
    transform: scale(0.56);
  }

  .orange {
    left: 4%;
    bottom: 28px;
    width: 150px;
    height: 120px;
  }

  .violet {
    left: 30%;
    bottom: 28px;
    width: 112px;
    height: 218px;
  }

  .ink {
    left: 53%;
    bottom: 28px;
    width: 88px;
    height: 186px;
  }

  .yellow {
    left: 64%;
    bottom: 28px;
    width: 124px;
    height: 168px;
  }

  .mascot-label {
    bottom: -25px;
  }

  .login-panel {
    padding: 28px 20px 34px;
  }
}
</style>
