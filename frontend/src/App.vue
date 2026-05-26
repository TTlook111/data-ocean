<script setup lang="ts">
import { gsap } from 'gsap'

function reduceMotion() {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
}

function handleBeforeEnter(el: Element) {
  if (reduceMotion()) return
  gsap.set(el, { autoAlpha: 0, y: 12 })
}

function handleEnter(el: Element, done: () => void) {
  if (reduceMotion()) {
    done()
    return
  }
  gsap.to(el, {
    autoAlpha: 1,
    y: 0,
    duration: 0.28,
    ease: 'power2.out',
    clearProps: 'transform,opacity,visibility',
    onComplete: done,
  })
}

function handleLeave(el: Element, done: () => void) {
  if (reduceMotion()) {
    done()
    return
  }
  gsap.to(el, {
    autoAlpha: 0,
    y: -8,
    duration: 0.18,
    ease: 'power1.in',
    onComplete: done,
  })
}
</script>

<template>
  <RouterView v-slot="{ Component, route }">
    <Transition
      mode="out-in"
      :css="false"
      @before-enter="handleBeforeEnter"
      @enter="handleEnter"
      @leave="handleLeave"
    >
      <component :is="Component" :key="route.fullPath" />
    </Transition>
  </RouterView>
</template>
