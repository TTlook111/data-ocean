<script setup lang="ts">
import brandMark from '../../assets/brand/dataocean-mark.png'
defineProps<{ title: string; description: string; label: string }>()
</script>
<template>
  <main class="guide-page">
    <div class="guide-frame">
      <nav class="guide-nav" aria-label="指南导航">
        <span class="guide-brand">
          <img :src="brandMark" alt="" />
          <strong>DataOcean</strong>
        </span>
        <slot name="navigation" />
      </nav>
      <header class="guide-heading">
        <p>{{ label }}</p>
        <h1>{{ title }}</h1>
        <div>{{ description }}</div>
      </header>
      <div class="guide-content"><slot /></div>
    </div>
  </main>
</template>
<style scoped>
.guide-page {
  min-height: 100dvh;
  background: var(--do-bg);
  color: var(--do-ink);
  padding: 32px 24px 64px;
}
.guide-frame {
  max-width: 1040px;
  margin: auto;
}
.guide-nav {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
  padding-bottom: 28px;
  border-bottom: 1px solid var(--do-line);
  font-size: 13px;
}
.guide-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-right: auto;
}
.guide-brand img {
  height: 36px;
  width: 22px;
  object-fit: contain;
}
.guide-brand strong {
  font-size: 18px;
}
.guide-nav :deep(a) {
  color: var(--do-muted);
}
.guide-nav :deep(a:hover) {
  color: var(--do-primary);
}
.guide-heading {
  max-width: 760px;
  padding: 40px 0 32px;
}
.guide-heading p {
  color: var(--do-primary);
  font-weight: 600;
  font-size: 12px;
  margin: 0 0 12px;
}
.guide-heading h1 {
  font-size: clamp(28px, 4vw, 38px);
  letter-spacing: -1px;
  line-height: 1.3;
  margin: 0 0 16px;
}
.guide-heading div {
  color: var(--do-muted);
  line-height: 1.8;
  font-size: 15px;
}
.guide-content :deep(.guide-steps) {
  list-style: none;
  padding: 0;
  margin: 0;
  counter-reset: step;
  display: grid;
  gap: 12px;
}
.guide-content :deep(.guide-step) {
  counter-increment: step;
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 18px;
  padding: 24px 28px;
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: 12px;
  scroll-margin: 20px;
}
.guide-content :deep(.guide-step::before) {
  content: counter(step, decimal-leading-zero);
  font-size: 15px;
  font-family: monospace;
  color: var(--do-primary);
  padding-top: 3px;
}
.guide-content :deep(h2) {
  font-size: 17px;
  margin: 0 0 8px;
}
.guide-content :deep(p) {
  margin: 0 0 10px;
  font-size: 14px;
  line-height: 1.8;
  color: var(--do-muted);
}
.guide-content :deep(p:last-child) {
  margin-bottom: 0;
}
.guide-content :deep(details) {
  margin-top: 12px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--do-muted);
}
.guide-content :deep(summary) {
  color: var(--do-ink);
  cursor: pointer;
}
.guide-content :deep(.guide-note) {
  padding: 20px 24px;
  border-left: 3px solid var(--do-primary);
  background: var(--do-primary-soft);
  margin: 24px 0;
  font-size: 14px;
  line-height: 1.8;
}
.guide-content :deep(.guide-actions) {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 28px;
  font-size: 14px;
}
.guide-content :deep(.guide-link) {
  display: inline-flex;
  color: var(--do-primary);
  font-size: 13px;
}
.guide-content :deep(a:focus-visible),
.guide-content :deep(summary:focus-visible) {
  outline: 2px solid var(--do-primary);
  outline-offset: 4px;
}
.guide-content :deep(.guide-primary) {
  padding: 11px 18px;
  background: var(--do-primary);
  color: white;
  border-radius: 8px;
}
.guide-content :deep(.guide-unavailable) {
  font-size: 12px;
  color: var(--do-muted);
}
@media (max-width: 560px) {
  .guide-page {
    padding: 24px 12px 40px;
  }
  .guide-heading {
    padding-top: 28px;
  }
  .guide-content :deep(.guide-step) {
    padding: 20px 18px;
    grid-template-columns: 28px minmax(0, 1fr);
    gap: 10px;
  }
}
</style>
