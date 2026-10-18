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
      <div v-if="selectedId && !canAsk" class="composer-readiness" role="status">
        {{ selectedBlockReason?.message || (readinessLoading ? '正在确认数据源状态' : '当前数据源暂未完成上线流程') }}
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
        <SendHorizontal :size="17" /><span>发送</span>
      </button>
      <button v-else class="cancel-button" type="button" aria-label="停止查询" @click="emit('cancel')">
        <Square :size="14" fill="currentColor" /><span>停止</span>
      </button>
    </footer>
    <small class="composer-hint">Enter 发送，Shift + Enter 换行</small>
  </div>
</template>

<style scoped>
.query-input { width: 100%; }
.chat-composer { width: min(780px, 100%); margin: 0 auto; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 8px; padding: 7px 7px 7px 14px; border: 1px solid var(--do-line); border-radius: 13px; background: rgba(255, 255, 255, .97); box-shadow: 0 4px 16px rgba(33, 78, 140, .05); transition: border-color 150ms ease, box-shadow 150ms ease; }
.chat-composer:focus-within { border-color: var(--do-primary); box-shadow: 0 0 0 3px color-mix(in srgb, var(--do-primary) 14%, transparent), 0 6px 18px rgba(33, 78, 140, .07); }
.composer-readiness { grid-column: 1 / -1; padding: 2px 0 0; color: color-mix(in srgb, var(--do-warning) 65%, var(--do-ink)); font-size: 11px; line-height: 1.45; }
.chat-composer textarea { width: 100%; min-height: 38px; max-height: 160px; resize: none; overflow-y: hidden; padding: 8px 0; border: 0; outline: 0; color: var(--do-ink); background: transparent; font: inherit; font-size: 15px; line-height: 1.6; }
.chat-composer textarea::placeholder { color: var(--do-muted); opacity: .75; }
.chat-composer textarea:disabled { cursor: not-allowed; }
.send-button, .cancel-button { min-width: 78px; height: 38px; display: inline-flex; align-items: center; justify-content: center; gap: 6px; border: 0; border-radius: 9px; color: #fff; background: var(--do-primary); font: inherit; font-size: 12.5px; font-weight: 700; cursor: pointer; transition: background 150ms ease, transform 150ms ease, opacity 150ms ease; }
.send-button:hover:not(:disabled) { background: var(--do-primary-strong); }
.send-button:active:not(:disabled), .cancel-button:active { transform: translateY(1px); }
.send-button:disabled { border: 1px solid var(--do-line); color: #8298b4; background: #eaf2fd; cursor: not-allowed; }
.cancel-button { min-width: 76px; color: var(--do-danger); background: var(--do-danger-soft); }
.cancel-button:hover { background: #fee2e2; }
.composer-hint { width: min(780px, 100%); display: block; margin: 5px auto 0; color: var(--do-muted); font-size: 10.5px; text-align: right; }
.send-button:focus-visible, .cancel-button:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 30%, transparent); outline-offset: 2px; }
@media (max-width: 768px) {
  .chat-composer { gap: 7px; padding: 6px 6px 6px 11px; }
  .chat-composer textarea { min-height: 40px; font-size: 15px; }
  .send-button, .cancel-button { min-width: 70px; height: 40px; }
}
</style>
