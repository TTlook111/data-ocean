<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'

const props = defineProps<{ conceal: boolean }>()
const scene = ref<HTMLElement>()
const characters = ['arch', 'tall', 'ink', 'dome'] as const
let motion: MediaQueryList | undefined
let frame: number | undefined
let pointer: { x: number; y: number } | undefined
let figures: HTMLElement[] = []

// 先读取每张脸的中心，再统一更新 CSS 变量。视线位移的半径由插画尺寸决定。
function followPointer() {
  frame = undefined
  if (!pointer || !motion?.matches || props.conceal) return
  const position = pointer
  const offsets = figures.map(figure => {
    const face = figure.querySelector<HTMLElement>('.eyes')!
    const rect = face.getBoundingClientRect()
    const dx = position.x - rect.left - rect.width / 2
    const dy = position.y - rect.top - rect.height / 2
    const distance = Math.max(140, Math.hypot(dx, dy))
    return [dx / distance, dy / distance] as const
  })
  figures.forEach((figure, index) => {
    const [x, y] = offsets[index]!
    figure.style.setProperty('--look-x', x.toFixed(3))
    figure.style.setProperty('--look-y', y.toFixed(3))
  })
}

// 一帧内只采用最后一个鼠标坐标；触屏、密码模式和减少动态偏好使用静态表情。
function onPointerMove(event: PointerEvent) {
  if (event.pointerType !== 'mouse') {
    rest()
    return
  }
  if (props.conceal || !motion?.matches || document.hidden) return
  pointer = { x: event.clientX, y: event.clientY }
  if (frame === undefined) frame = window.requestAnimationFrame(followPointer)
}

// 窗口失焦、隐藏、指针离开或偏好改变时取消待执行帧，角色回到默认站姿。
function rest() {
  if (!pointer && frame === undefined) return
  if (frame !== undefined) window.cancelAnimationFrame(frame)
  frame = undefined
  pointer = undefined
  figures.forEach(figure => {
    figure.style.removeProperty('--look-x')
    figure.style.removeProperty('--look-y')
  })
}

function onTouchStart(event: PointerEvent) {
  if (event.pointerType === 'touch') rest()
}

function onVisibilityChange() {
  if (document.hidden) rest()
}

// 装饰组件只接收表单的遮挡状态，不需要知道输入了什么。
watch(() => props.conceal, rest, { flush: 'sync' })

onMounted(() => {
  figures = Array.from(scene.value!.querySelectorAll<HTMLElement>('.character'))
  motion = window.matchMedia('(any-hover: hover) and (any-pointer: fine) and (prefers-reduced-motion: no-preference)')
  motion.addEventListener('change', rest)
  window.addEventListener('pointermove', onPointerMove, { passive: true })
  window.addEventListener('pointerdown', onTouchStart, { passive: true })
  document.documentElement.addEventListener('pointerleave', rest)
  window.addEventListener('blur', rest)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  rest()
  motion?.removeEventListener('change', rest)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerdown', onTouchStart)
  document.documentElement.removeEventListener('pointerleave', rest)
  window.removeEventListener('blur', rest)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  figures = []
})
</script>

<template>
  <div ref="scene" class="login-characters" :class="{ 'is-concealed': conceal }" aria-hidden="true">
    <!-- 四个角色共用地平线，整幅插画不进入键盘顺序或读屏内容。 -->
    <div v-for="shape in characters" :key="shape" class="character" :class="`character--${shape}`">
      <span class="eyes"><i class="eye"></i><i class="eye"></i></span>
      <span v-if="shape === 'dome'" class="mouth"></span>
    </div>
  </div>
</template>

<style scoped>
.login-characters {
  /* 插画局部颜色用于辨认角色，表单状态继续使用站点的业务色。 */
  --face-ink: #253547;
  --eye-white: #fffdf7;
  position: relative;
  isolation: isolate;
  overflow: clip;
  container-type: inline-size;
  width: 100%;
  aspect-ratio: 1.7;
  pointer-events: none;
}
.login-characters::after {
  position: absolute;
  inset: auto 0 0;
  height: 1px;
  background: var(--do-line-strong);
  content: '';
}

/* 后排高个略倾向表单，前排拱形和圆顶围合两侧，保持旧版辨识度。 */
.character {
  position: absolute;
  bottom: 0;
  transform-origin: center bottom;
  transform: rotate(calc(var(--rest-tilt, 0deg) + var(--look-x, 0) * var(--turn, 0deg)));
  transition: transform 150ms ease-out;
}
.character--arch {
  z-index: 3;
  left: 0;
  width: 40%;
  height: 41%;
  border-radius: 50% 50% 2px 2px / 91% 91% 2px 2px;
  background: #e7a07b;
}
.character--tall {
  --rest-tilt: -3deg;
  --turn: 1.8deg;
  left: 27%;
  width: 29%;
  height: 98%;
  border-radius: 2.8cqw 2.8cqw 2px 2px;
  background: #8197ce;
}
.character--ink {
  --turn: 1.2deg;
  --lid: var(--eye-white);
  z-index: 1;
  left: 48%;
  width: 23%;
  height: 72%;
  border-radius: 1.5cqw 1.5cqw 2px 2px;
  background: #374250;
}
.character--dome {
  z-index: 2;
  left: 67%;
  width: 31%;
  height: 56%;
  border-radius: 52% 52% 2px 2px / 51% 51% 2px 2px;
  background: #e6c775;
}

/* 后排眼眶中的瞳孔和前排点状眼睛分别限制位移，尺寸随图形同比变化。 */
.eyes {
  position: absolute;
  display: flex;
  justify-content: space-between;
  left: 39%;
  top: 24%;
  width: 40%;
}
.character--ink .eyes { top: 29%; width: 46%; }
.character--arch .eyes { left: 45%; top: 50%; width: 19%; }
.character--dome .eyes { left: 41%; top: 36%; width: 25%; }
.eye {
  position: relative;
  display: block;
  flex: none;
  width: 3.8cqw;
  aspect-ratio: 1;
  border-radius: 50%;
  background: var(--eye-white);
  transition: transform 120ms ease-out;
}
.eye::after {
  position: absolute;
  inset: 28%;
  border-radius: 50%;
  background: var(--face-ink);
  transform: translate(calc(var(--look-x, 0) * .78cqw), calc(var(--look-y, 0) * .78cqw));
  transition: transform 110ms ease-out;
  content: '';
}
.character--arch .eye,
.character--dome .eye {
  width: 2.1cqw;
  background: var(--face-ink);
  transform: translate(calc(var(--look-x, 0) * .55cqw), calc(var(--look-y, 0) * .55cqw));
}
.character--arch .eye::after,
.character--dome .eye::after { content: none; }
.mouth {
  position: absolute;
  top: 55%;
  left: 32%;
  width: 37%;
  height: 12%;
  border-bottom: max(1px, .45cqw) solid var(--face-ink);
  border-radius: 0 0 50% 50%;
}

/* 输入密码时四双眼睛合上，深色角色使用浅色眼睑。 */
.is-concealed .character .eye {
  transform: scaleY(.15);
  background: var(--lid, var(--face-ink));
}
.is-concealed .eye::after { opacity: 0; }
@media (prefers-reduced-motion: reduce) {
  .character, .eye, .eye::after { transition: none; }
}
</style>
