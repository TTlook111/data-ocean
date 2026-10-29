<script setup lang="ts">
import brandMark from '../../assets/brand/dataocean-mark.png'
defineProps<{ title: string; description: string; eyebrow?: string }>()
</script>
<template>
  <main class="account-page">
    <div class="account-frame">
      <header class="account-brand">
        <img :src="brandMark" alt="" />
        <strong>DataOcean</strong>
        <span>账号设置</span>
      </header>
      <nav class="account-nav" aria-label="账号导航"><slot name="navigation" /></nav>
      <section class="account-panel">
        <header class="account-heading">
          <p>{{ eyebrow || '个人账号' }}</p>
          <h1>{{ title }}</h1>
          <div>{{ description }}</div>
        </header>
        <div class="account-content"><slot /></div>
      </section>
    </div>
  </main>
</template>
<style scoped>
.account-page {
  min-height: 100dvh;
  padding: 48px 24px;
  background: var(--do-bg);
  color: var(--do-ink);
}
.account-frame {
  width: min(720px, 100%);
  margin: auto;
}
.account-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 32px;
}
.account-brand img {
  width: 24px;
  height: 40px;
  object-fit: contain;
}
.account-brand strong {
  font-size: 19px;
}
.account-brand span {
  margin-left: auto;
  color: var(--do-muted);
  font-size: 12px;
}
.account-nav {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 18px;
  font-size: 13px;
}
.account-nav :deep(a) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--do-muted);
}
.account-nav :deep(a:hover) {
  color: var(--do-primary);
}
.account-panel {
  border: 1px solid var(--do-line);
  border-radius: 16px;
  background: var(--do-surface);
  overflow: hidden;
}
.account-heading {
  padding: 28px 32px;
  border-bottom: 1px solid var(--do-line);
}
.account-heading p {
  margin: 0 0 8px;
  color: var(--do-primary);
  font-size: 12px;
  font-weight: 600;
}
.account-heading h1 {
  margin: 0 0 10px;
  font-size: 26px;
  letter-spacing: -0.6px;
}
.account-heading div {
  color: var(--do-muted);
  font-size: 13px;
  line-height: 1.7;
}
.account-content {
  padding: 28px 32px;
}
.account-content :deep(.el-form-item__label) {
  color: var(--do-muted);
}
.account-content :deep(.account-message) {
  padding: 12px 14px;
  margin: 0 0 20px;
  background: var(--do-bg);
  border-left: 3px solid var(--do-primary);
  font-size: 13px;
  line-height: 1.7;
}
.account-content :deep(.account-error) {
  color: var(--do-danger);
  border-left-color: var(--do-danger);
}
.account-content :deep(.form-actions) {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
}
.account-content :deep(.form-actions a) {
  font-size: 13px;
  color: var(--do-primary);
}
@media (max-width: 560px) {
  .account-page {
    padding: 24px 12px;
  }
  .account-heading,
  .account-content {
    padding: 22px 18px;
  }
  .account-content :deep(.el-form-item) {
    display: block;
  }
  .account-content :deep(.el-form-item__label) {
    display: block;
    text-align: left;
    width: auto !important;
    line-height: 28px;
    height: auto;
    float: none;
  }
  .account-content :deep(.el-form-item__content) {
    margin-left: 0 !important;
  }
  .account-brand {
    margin-bottom: 22px;
  }
}
</style>
