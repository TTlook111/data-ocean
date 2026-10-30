/**
 * QueryInput — 极简问答输入区
 */
<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { SendHorizontal, Square } from 'lucide-vue-next'

const props = defineProps<{
  question: string
  isQuerying: boolean
  selectedId: number | undefined
  selectedDatasourceName?: string
  canAsk: boolean
  readinessLoading: boolean
  selectedBlockReason?: { message?: string } | undefined
}>()

const emit = defineEmits<{
  'update:question': [value: string]
  'send': []
  'cancel': []
  'retry-readiness': []
}>()

const MAX_TEXTAREA_HEIGHT = 160
const questionInputRef = ref<HTMLTextAreaElement>()

async function focusQuestionInput() {
  await nextTick()
  questionInputRef.value?.focus()
}

defineExpose({ focusQuestionInput })

function resizeTextarea() {
  const element = questionInputRef.value
  if (!element) return
  element.style.height = 'auto'
  const nextHeight = Math.min(element.scrollHeight, MAX_TEXTAREA_HEIGHT)
  element.style.height = `${nextHeight}px`
  element.style.overflowY = element.scrollHeight > MAX_TEXTAREA_HEIGHT ? 'auto' : 'hidden'
}

function handleInput(event: Event) {
  emit('update:question', (event.target as HTMLTextAreaElement).value)
  resizeTextarea()
}

function handleEnter(event: KeyboardEvent) {
  if (event.shiftKey || event.isComposing) return
  event.preventDefault()
  if (!props.isQuerying) emit('send')
}

watch(() => props.question, () => { void nextTick(resizeTextarea) })
onMounted(() => { void nextTick(resizeTextarea) })
</script>

<template>
  <div class="query-input">
    <footer class="chat-composer" aria-label="查询输入区" :aria-busy="isQuerying">
      <div class="composer-context"><slot name="context" /></div>
      <div v-if="selectedId && !canAsk" class="composer-readiness" role="status">
        {{ readinessLoading ? '正在确认数据源状态…' : (selectedBlockReason?.message || '数据源状态尚未确认，请重试状态检查') }}
        <button type="button" :disabled="readinessLoading" @click="emit('retry-readiness')">重试状态检查</button>
      </div>
      <textarea
        ref="questionInputRef"
        aria-label="输入查询问题"
        :value="question"
        :disabled="!selectedId || !canAsk"
        rows="1"
        :placeholder="!selectedId ? '请先选择数据源' : canAsk ? `向 ${selectedDatasourceName || '当前数据源'} 提问` : '当前数据源暂不可询问'"
        @input="handleInput"
        @keydown.enter="handleEnter"
      ></textarea>
      <button v-if="!isQuerying" class="send-button" type="button" aria-label="发送" :disabled="!selectedId || !canAsk || !question.trim()" @click="emit('send')">
        <SendHorizontal :size="17" aria-hidden="true" /><span>发送</span>
      </button>
      <button v-else class="cancel-button" type="button" aria-label="停止查询" @click="emit('cancel')">
        <Square :size="14" fill="currentColor" aria-hidden="true" /><span>停止</span>
      </button>
    </footer>
    <div class="composer-bottom"><span>结果请结合业务口径核对</span><small class="composer-hint">Enter 发送 · Shift + Enter 换行</small></div>
  </div>
</template>

<style scoped>
.query-input { width: 100%; }
.chat-composer { width: min(820px, 100%); margin: 0 auto; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 8px; padding: 10px 12px 12px 16px; border: 1px solid var(--do-line-strong); border-radius: 16px; background: var(--do-surface); box-shadow: 0 4px 18px rgb(36 39 36 / 4%); transition: border-color 150ms ease, box-shadow 150ms ease; }
.composer-context { grid-column: 1 / -1; min-width: 0; display:flex; align-items:center; flex-wrap:wrap; gap:4px; }
.chat-composer:focus-within { border-color: var(--do-primary); box-shadow: 0 0 0 3px color-mix(in srgb, var(--do-primary) 14%, transparent), 0 6px 18px rgba(33, 78, 140, .07); }
.composer-readiness { grid-column: 1 / -1; padding: 2px 0 0; color: color-mix(in srgb, var(--do-warning) 65%, var(--do-ink)); font-size: 11px; line-height: 1.45; }
.composer-readiness button { margin-left: 8px; padding: 2px 6px; border: 1px solid var(--do-line); border-radius: 5px; background: var(--do-surface); color: var(--do-primary-strong); font: inherit; cursor: pointer; }
.composer-readiness button:disabled { opacity: .6; cursor: wait; }
.composer-readiness button:focus-visible { outline: 2px solid var(--do-primary); outline-offset: 2px; }
.chat-composer textarea { width: 100%; min-height: 38px; max-height: 160px; resize: none; overflow-y: hidden; padding: 8px 0; border: 0; outline: 0; color: var(--do-ink); background: transparent; font: inherit; font-size: 15px; line-height: 1.6; }
.chat-composer textarea::placeholder { color: var(--do-muted); opacity: .75; }
.chat-composer textarea:disabled { cursor: not-allowed; }
.send-button, .cancel-button { min-width: 78px; height: 38px; display: inline-flex; align-items: center; justify-content: center; gap: 6px; border: 0; border-radius: 9px; color: #fff; background: var(--do-primary); font: inherit; font-size: 12.5px; font-weight: 700; cursor: pointer; transition: background 150ms ease, transform 150ms ease, opacity 150ms ease; }
.send-button:hover:not(:disabled) { background: var(--do-primary-strong); }
.send-button:active:not(:disabled), .cancel-button:active { transform: translateY(1px); }
.send-button:disabled { border: 1px solid var(--do-line); color: var(--do-muted); background: var(--do-soft); cursor: not-allowed; }
.cancel-button { min-width: 76px; color: var(--do-danger); background: var(--do-danger-soft); }
.cancel-button:hover { background: #fee2e2; }
.composer-bottom { width: min(820px, 100%); display: flex; justify-content: space-between; flex-wrap: wrap; gap: 4px 12px; margin: 9px auto 0; padding-inline: 4px; color: var(--do-muted); font-size: 11px; line-height: 1.5; }
.composer-hint { font-size: inherit; }
.send-button:focus-visible, .cancel-button:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 30%, transparent); outline-offset: 2px; }
@media (max-width: 768px) {
  .chat-composer { gap: 7px; padding: 6px 6px 6px 11px; }
  .chat-composer textarea { min-height: 40px; font-size: 16px; }
  .send-button, .cancel-button { min-width: 70px; height: 40px; }
}
</style>
