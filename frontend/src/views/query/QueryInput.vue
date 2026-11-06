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
  <!-- 输入区只负责草稿与发送/停止；来源选择由工作区页头统一呈现。 -->
  <div class="query-input">
    <footer class="chat-composer" aria-label="查询输入区" :aria-busy="isQuerying">
      <div v-if="selectedId && !canAsk" class="composer-readiness" role="status">
        {{ readinessLoading ? '正在确认数据源状态…' : (selectedBlockReason?.message || '数据源状态尚未确认，请重试状态检查') }}
        <button type="button" :disabled="readinessLoading" @click="emit('retry-readiness')">重试状态检查</button>
      </div>
      <textarea
        ref="questionInputRef"
        aria-label="输入查询问题"
        :value="question"
        :disabled="!selectedId || !canAsk"
        rows="2"
        :placeholder="!selectedId ? '请先选择数据源' : canAsk ? `向 ${selectedDatasourceName || '当前数据源'} 提问` : '当前数据源暂不可询问'"
        @input="handleInput"
        @keydown.enter="handleEnter"
      ></textarea>
      <!-- 操作行与草稿分开，自动增高时发送和停止始终保持同一位置。 -->
      <div class="composer-toolbar">
        <small class="composer-hint">Enter 发送 · Shift + Enter 换行</small>
        <button v-if="!isQuerying" class="send-button" type="button" aria-label="发送" :disabled="!selectedId || !canAsk || !question.trim()" @click="emit('send')">
          <SendHorizontal :size="17" aria-hidden="true" /><span>发送</span>
        </button>
        <button v-else class="cancel-button" type="button" aria-label="停止查询" @click="emit('cancel')">
          <Square :size="14" fill="currentColor" aria-hidden="true" /><span>停止</span>
        </button>
      </div>
    </footer>
    <p class="composer-note">结果请结合业务口径核对</p>
  </div>
</template>

<style scoped>
/* 输入与连续回答共用阅读宽度；唯一输入框承载主动作，不再嵌套范围工具栏。 */
.query-input { width: 100%; }
.chat-composer { width: min(900px, 100%); margin: 0 auto; display: flex; flex-direction: column; gap: 12px; padding: 16px 16px 12px; border: 1px solid var(--do-line-strong); border-radius: var(--do-radius-lg); background: var(--do-surface); box-shadow: var(--do-shadow); transition: border-color var(--do-transition-fast), box-shadow var(--do-transition-fast); }
.chat-composer:focus-within { border-color: var(--do-primary); box-shadow: 0 0 0 3px var(--do-primary-soft); }

/* 就绪失败保持在草稿旁，重试只刷新来源状态，不直接发送业务问题。 */
.composer-readiness { padding: 2px 0; color: color-mix(in srgb, var(--do-warning) 65%, var(--do-ink)); font-size: 12px; line-height: 1.6; }
.composer-readiness button { margin-left: 8px; padding: 2px 6px; border: 1px solid var(--do-line); border-radius: 5px; background: var(--do-surface); color: var(--do-primary-strong); font: inherit; cursor: pointer; }
.composer-readiness button:disabled { opacity: .6; cursor: wait; }
.composer-readiness button:focus-visible { outline: 2px solid var(--do-primary); outline-offset: 2px; }
.chat-composer textarea { width: 100%; min-height: 56px; max-height: 160px; resize: none; overflow-y: hidden; padding: 2px 0; border: 0; outline: 0; color: var(--do-ink); background: transparent; font: inherit; font-size: 15px; line-height: 1.7; }
.chat-composer textarea::placeholder { color: var(--do-muted); opacity: 1; }
.chat-composer textarea:disabled { cursor: not-allowed; }

/* 快捷键提示弱化，发送为唯一主动作；执行期间在原位展示停止按钮。 */
.composer-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.composer-hint { color: var(--do-muted); font-size: 11px; line-height: 1.5; }
.send-button, .cancel-button { flex: none; min-width: 78px; height: 38px; display: inline-flex; align-items: center; justify-content: center; gap: 7px; border: 0; border-radius: var(--do-radius-sm); color: var(--do-surface); background: var(--do-primary); font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: background var(--do-transition-fast), transform var(--do-transition-fast); }
.send-button:hover:not(:disabled) { background: var(--do-primary-strong); }
.send-button:active:not(:disabled), .cancel-button:active { transform: translateY(1px); }
.send-button:disabled { border: 1px solid var(--do-line); color: var(--do-muted); background: var(--do-soft); cursor: not-allowed; }
.cancel-button { min-width: 76px; color: var(--do-danger); background: var(--do-danger-soft); }
.cancel-button:hover { background: color-mix(in srgb, var(--do-danger) 13%, var(--do-surface)); }
.composer-note { margin: 10px 0 0; color: var(--do-muted); font-size: 11px; line-height: 1.5; text-align: center; }
.send-button:focus-visible, .cancel-button:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 30%, transparent); outline-offset: 2px; }
@media (max-width: 768px) {
  /* 窄窗口保持同一草稿和操作行，文字输入不因缩小而触发浏览器放大。 */
  .chat-composer { gap: 8px; padding: 12px; }
  .chat-composer textarea { min-height: 56px; font-size: 16px; }
  .send-button, .cancel-button { min-width: 70px; height: 40px; }
}
</style>
