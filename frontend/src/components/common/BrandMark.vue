<template>
  <!-- 沿用原图标轮廓。名称由相邻的文字或链接提供，图形本身不重复朗读。 -->
  <span class="dataocean-mark" aria-hidden="true" />
</template>

<style scoped>
.dataocean-mark {
  /* 图标直接使用原轮廓，与各页相邻的文字组成品牌标识。 */
  display: inline-block;
  flex: none;
  width: 28px;
  height: 36px;
  background: var(--do-primary-strong);
  mask: url('../../assets/brand/dataocean-mark.png') center / contain no-repeat;
}
</style>
