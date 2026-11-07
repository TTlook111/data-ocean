<template>
  <!-- 沿用原图标轮廓。名称由相邻的文字或链接提供，图形本身不重复朗读。 -->
  <span class="dataocean-mark" aria-hidden="true" />
</template>

<style scoped>
.dataocean-mark {
  display: inline-grid;
  flex: none;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 10px;
  background: var(--do-primary);
}

/* 使用透明轮廓统一反白图标，侧栏、登录和消息署名可以各自控制外框尺寸。 */
.dataocean-mark::before {
  content: '';
  width: 74%;
  height: 78%;
  background: var(--do-on-primary);
  mask: url('../../assets/brand/dataocean-mark.png') center / contain no-repeat;
}
</style>
