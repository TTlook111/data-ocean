import { nextTick, onMounted, onUnmounted, type Ref } from 'vue'
import { gsap } from 'gsap'

const MOTION_QUERY = '(prefers-reduced-motion: reduce)'

type MotionTarget = string | Element | Element[] | NodeListOf<Element>

function shouldReduceMotion() {
  return window.matchMedia?.(MOTION_QUERY).matches ?? false
}

export function useGsapMotion(scope: Ref<HTMLElement | null>) {
  let ctx: gsap.Context | undefined
  const tweens: gsap.core.Tween[] = []

  function track(tween: gsap.core.Tween) {
    tweens.push(tween)
    return tween
  }

  function withContext(callback: () => void) {
    const root = scope.value
    if (!root || shouldReduceMotion()) return
    ctx = gsap.context(callback, root)
  }

  function refresh(callback: () => void) {
    ctx?.revert()
    withContext(callback)
  }

  function reveal(targets: MotionTarget, options: gsap.TweenVars = {}) {
    if (shouldReduceMotion()) return
    track(gsap.from(targets, {
      autoAlpha: 0,
      y: 18,
      duration: 0.46,
      ease: 'power2.out',
      stagger: 0.055,
      clearProps: 'transform,opacity,visibility',
      ...options,
    }))
  }

  function lift(target: MotionTarget, options: gsap.TweenVars = {}) {
    if (shouldReduceMotion()) return
    track(gsap.fromTo(
      target,
      { autoAlpha: 0, y: 12, scale: 0.985 },
      {
        autoAlpha: 1,
        y: 0,
        scale: 1,
        duration: 0.32,
        ease: 'power2.out',
        clearProps: 'transform,opacity,visibility',
        ...options,
      },
    ))
  }

  async function revealAfterTick(targets: MotionTarget, options?: gsap.TweenVars) {
    await nextTick()
    reveal(targets, options)
  }

  onMounted(() => {
    const root = scope.value
    if (!root || shouldReduceMotion()) return
    gsap.set(root, { autoAlpha: 1 })
  })

  onUnmounted(() => {
    ctx?.revert()
    tweens.forEach((tween) => tween.kill())
  })

  return {
    gsap,
    lift,
    reduceMotion: shouldReduceMotion,
    refresh,
    reveal,
    revealAfterTick,
    withContext,
  }
}
