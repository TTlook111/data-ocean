<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'

const props = defineProps<{ conceal: boolean; attentive?: boolean }>()
const scene = ref<HTMLElement>()
const characters = ['arch', 'tall', 'ink', 'dome'] as const
let motion: MediaQueryList | undefined
let hover: MediaQueryList | undefined
let frame: number | undefined
let pointer: { x: number; y: number } | undefined
let figures: HTMLElement[] = []
let tap: { figure: HTMLElement; id: number; x: number; y: number } | undefined
let greeting: Animation | undefined

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

// 单帧合并鼠标移动。手指移动超过轻点容差就交给滚动，即使滑回原点也不触发问候。
function onPointerMove(event: PointerEvent) {
  if (tap?.id === event.pointerId && Math.hypot(event.clientX - tap.x, event.clientY - tap.y) > 8) {
    tap = undefined
  }
  if (event.pointerType !== 'mouse') {
    restGaze()
    return
  }
  if (props.conceal || !motion?.matches || !hover?.matches || document.hidden) return
  pointer = { x: event.clientX, y: event.clientY }
  if (frame === undefined) frame = window.requestAnimationFrame(followPointer)
}

// 释放指针方向后，CSS 可按当前键盘焦点决定视线的默认朝向。
function restGaze() {
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
  if (event.pointerType === 'touch') restGaze()
}

// 触屏抬手后浏览器可先发 pointerleave 再发 click，这不是用户离开窗口。
function onPointerLeave(event: PointerEvent) {
  if (event.pointerType !== 'touch') rest()
}

// 借用浏览器原生 click 确认点按完成。只记录从角色上按下的主指针，不捕获手指或阻断滚动。
function startTap(event: PointerEvent) {
  tap = undefined
  if (!event.isPrimary || event.button !== 0 || props.conceal || !motion?.matches) return
  tap = { figure: event.currentTarget as HTMLElement, id: event.pointerId, x: event.clientX, y: event.clientY }
}
function greet(event: MouseEvent) {
  const figure = tap?.figure
  tap = undefined
  if (!figure || figure !== event.currentTarget || !motion?.matches || props.conceal || document.hidden) return
  // 用独立的缩放和平移做一次起落，保留身体跟随鼠标的旋转；再次轻点可以立即重播。
  greeting?.cancel()
  greeting = figure.animate([
    { scale: '1', translate: '0 0' },
    { scale: '1.06 .91', translate: '0 0', offset: .25 },
    { scale: '.98 1.01', translate: '0 var(--hop)', offset: .52 },
    { scale: '1.02 .98', translate: '0 0', offset: .78 },
    { scale: '1', translate: '0 0' },
  ], { duration: 410, easing: 'ease-in-out' })
}

// 离开页面、切换动态偏好或进入密码状态时，同时撤销在途手势、动画与跟随帧。
function rest() {
  tap = undefined
  greeting?.cancel()
  greeting = undefined
  restGaze()
}
function onVisibilityChange() {
  if (document.hidden) rest()
}

// 表单焦点接管旧鼠标方向，密码模式还需立即中止刚触发的回弹。
watch(() => props.conceal, rest, { flush: 'sync' })
watch(() => props.attentive, restGaze, { flush: 'sync' })

onMounted(() => {
  figures = Array.from(scene.value!.querySelectorAll<HTMLElement>('.character'))
  // 触屏也能主动轻点角色，只有连续的指针跟随需要悬停能力。
  motion = window.matchMedia('(prefers-reduced-motion: no-preference)')
  hover = window.matchMedia('(any-hover: hover) and (any-pointer: fine)')
  motion.addEventListener('change', rest)
  hover.addEventListener('change', rest)
  window.addEventListener('pointermove', onPointerMove, { passive: true })
  window.addEventListener('pointerdown', onTouchStart, { passive: true })
  window.addEventListener('pointercancel', rest)
  document.documentElement.addEventListener('pointerleave', onPointerLeave)
  window.addEventListener('blur', rest)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  rest()
  motion?.removeEventListener('change', rest)
  hover?.removeEventListener('change', rest)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerdown', onTouchStart)
  window.removeEventListener('pointercancel', rest)
  document.documentElement.removeEventListener('pointerleave', onPointerLeave)
  window.removeEventListener('blur', rest)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  figures = []
})
</script>

<template>
  <div ref="scene" class="login-characters" :class="{ 'is-concealed': conceal, 'is-attentive': attentive && !conceal }" aria-hidden="true">
    <!-- 角色是可轻点的装饰。按鼠标时保留输入框焦点，并沿用原键盘顺序和移动端滚动。 -->
    <div
      v-for="shape in characters"
      :key="shape"
      class="character"
      :class="`character--${shape}`"
      @pointerdown="startTap"
      @mousedown.left.prevent
      @click="greet"
    >
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
  --hop: -5%;
  position: absolute;
  bottom: 0;
  pointer-events: auto;
  user-select: none;
  transform-origin: center bottom;
  transform: rotate(calc(var(--rest-tilt, 0deg) + var(--look-x, 0) * var(--turn, 0deg)));
  transition: transform 150ms ease-out;
}
.character--arch {
  --turn: 3deg;
  --hop: -7%;
  z-index: 3;
  left: 0;
  width: 40%;
  height: 41%;
  border-radius: 50% 50% 2px 2px / 91% 91% 2px 2px;
  background: #e7a07b;
}
.character--tall {
  --rest-tilt: -3deg;
  --turn: 5deg;
  --hop: -2%;
  left: 27%;
  width: 29%;
  height: 94%;
  border-radius: 2.8cqw 2.8cqw 2px 2px;
  background: #8197ce;
}
.character--ink {
  --turn: 3.8deg;
  --lid: var(--eye-white);
  z-index: 1;
  left: 48%;
  width: 23%;
  height: 72%;
  border-radius: 1.5cqw 1.5cqw 2px 2px;
  background: #374250;
}
.character--dome {
  --turn: 4.5deg;
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
  transform: translate(calc(var(--look-x, 0) * 1.65cqw), calc(var(--look-y, 0) * .95cqw));
  transition: transform 150ms ease-out;
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
/* 键盘填表时默认望向表单，鼠标移动后的局部坐标优先；输密码时身体向另一侧微倾。 */
@media (prefers-reduced-motion: no-preference) {
  .is-attentive { --look-x: .9; --look-y: -.28; }
  .is-concealed { --look-x: -.72; --look-y: .32; }
  .login-characters:not(.is-concealed) .character { cursor: pointer; }
}
@media (max-width: 760px) and (prefers-reduced-motion: no-preference) {
  .is-attentive { --look-x: -.32; --look-y: .9; }
}
@media (prefers-reduced-motion: reduce) {
  .character, .eyes, .eye, .eye::after { transition: none; }
}
</style>
