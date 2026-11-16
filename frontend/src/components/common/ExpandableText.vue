<script setup lang="ts">
import { computed, ref, watch } from 'vue'
const props = withDefaults(defineProps<{ text?: string; lines?: number }>(), { text: '', lines: 4 })
const expanded = ref(false)
const long = computed(() => props.text.length > 180)
watch(() => props.text, () => { expanded.value = false })
</script>

<template>
  <div class="expandable-text">
    <p :class="{clamped:long && !expanded}" :style="{'--reading-lines':lines}" :title="long && !expanded ? text : undefined">{{ text || '未提供' }}</p>
    <button v-if="long" type="button" :aria-expanded="expanded" @click="expanded=!expanded">{{ expanded ? '收起全文' : '展开全文' }}</button>
  </div>
</template>

<style scoped>
.expandable-text{min-width:0;}.expandable-text p{margin:0;line-height:1.85;white-space:pre-wrap;overflow-wrap:anywhere;}
.clamped{display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:var(--reading-lines);overflow:hidden;}
button{border:0;background:transparent;color:var(--do-primary-strong);font:inherit;font-size:12px;min-height:32px;padding:4px 0;cursor:pointer;}
button:focus-visible{outline:2px solid var(--do-primary);outline-offset:3px;}@media(max-width:760px){button{min-height:44px;}}
</style>
