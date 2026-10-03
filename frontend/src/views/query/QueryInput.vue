/**
 * QueryInput — 极简问答输入区
 */
<script setup lang="ts">
import { nextTick, ref } from 'vue'
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

const questionInputRef = ref<HTMLTextAreaElement>()

async function focusQuestionInput() {
  await nextTick()
  questionInputRef.value?.focus()
}

defineExpose({ focusQuestionInput })

function handleEnter(event: KeyboardEvent) {
  if (event.shiftKey || event.isComposing) return
  event.preventDefault()
  if (!props.isQuerying) emit('send')
}
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
        :placeholder="!selectedId ? '请先从左侧选择数据源' : canAsk ? `向 ${selectedDatasourceName || '当前数据源'} 提问` : '当前数据源暂不可询问'"
        @input="emit('update:question', ($event.target as HTMLTextAreaElement).value)"
        @keydown.enter="handleEnter"
      ></textarea>
      <button v-if="!isQuerying" class="send-button" type="button" aria-label="发送" :disabled="!selectedId || !canAsk || !question.trim()" @click="emit('send')">
        <SendHorizontal :size="18" /><span>发送</span>
      </button>
      <button v-else class="cancel-button" type="button" aria-label="停止查询" @click="emit('cancel')">
        <Square :size="15" fill="currentColor" /><span>停止</span>
      </button>
    </footer>
    <small class="composer-hint">Enter 发送，Shift + Enter 换行</small>
  </div>
</template>

<style scoped>
.query-input { width: min(900px, calc(100% - 44px)); margin: 0 auto 18px; }
.chat-composer { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; padding: 9px 9px 9px 16px; border: 1px solid var(--do-line); border-radius: 13px; background: rgba(255, 255, 255, .98); box-shadow: 0 12px 34px rgba(15, 23, 42, .1); transition: border-color 150ms ease, box-shadow 150ms ease; }
.chat-composer:focus-within { border-color: rgba(77, 143, 220, .5); box-shadow: 0 0 0 3px rgba(77, 143, 220, .08), 0 12px 34px rgba(15, 23, 42, .1); }
.composer-readiness { grid-column: 1 / -1; padding: 2px 0 0; color: #92400e; font-size: 11px; }
.chat-composer textarea { min-height: 42px; max-height: 128px; resize: vertical; padding: 10px 0 7px; border: 0; outline: 0; color: var(--do-ink); background: transparent; font: inherit; font-size: 14px; line-height: 1.55; }
.chat-composer textarea::placeholder { color: #94a3b8; }
.send-button, .cancel-button { min-width: 92px; height: 42px; display: inline-flex; align-items: center; justify-content: center; align-self: end; gap: 7px; border: 0; border-radius: 9px; color: #fff; background: var(--do-primary); font: inherit; font-size: 13px; font-weight: 800; cursor: pointer; transition: background 150ms ease, transform 150ms ease, opacity 150ms ease; }
.send-button:hover:not(:disabled) { background: var(--do-primary-strong); }
.send-button:active:not(:disabled), .cancel-button:active { transform: translateY(1px); }
.send-button:disabled { cursor: not-allowed; opacity: .42; }
.cancel-button { min-width: 82px; color: #b42318; background: #fef2f2; }
.cancel-button:hover { background: #fee2e2; }
.composer-hint { display: block; margin-top: 7px; color: #94a3b8; font-size: 10px; text-align: center; }
.send-button:focus-visible, .cancel-button:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 30%, transparent); outline-offset: 2px; }
</style>
