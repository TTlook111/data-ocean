/**
 * QueryInput — 极简问答输入区
 */
<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { Database, SendHorizontal, Square } from 'lucide-vue-next'
import type { DatasourceReadiness } from '../../api/datasource'

const props = defineProps<{
  question: string
  isQuerying: boolean
  selectedId: number | undefined
  selectedDatasourceName?: string
  canAsk: boolean
  readinessLoading: boolean
  selectedBlockReason?: { message?: string } | undefined
  selectedReadiness?: DatasourceReadiness | undefined
  exampleQuestions: string[]
  showExamples: boolean
}>()

const emit = defineEmits<{
  'update:question': [value: string]
  'send': []
  'cancel': []
  'apply-example': [text: string]
}>()

const questionInputRef = ref<HTMLTextAreaElement>()

async function focusQuestionInput() {
  await nextTick()
  questionInputRef.value?.focus()
}

defineExpose({ focusQuestionInput })

function handleEnter(event: KeyboardEvent) {
  if (event.shiftKey || props.isQuerying) return
  event.preventDefault()
  emit('send')
}
</script>

<template>
  <div class="query-input">
    <section v-if="showExamples" class="example-strip" aria-label="示例问题">
      <button v-for="item in exampleQuestions" :key="item" type="button" :disabled="!canAsk" @click="emit('apply-example', item)">{{ item }}</button>
    </section>

    <div v-if="selectedId" class="composer-context" :class="{ blocked: !canAsk }">
      <Database :size="13" />
      <span>{{ selectedDatasourceName || '当前数据源' }}</span>
      <i></i>
      <span>{{ canAsk ? '已就绪' : (selectedReadiness?.stageLabel || '暂不可询问') }}</span>
    </div>

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
.example-strip { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 7px; margin-bottom: 14px; }
.example-strip button { min-height: 30px; padding: 0 11px; border: 1px solid var(--do-line); border-radius: 999px; color: var(--do-muted); background: rgba(255, 255, 255, .8); font: inherit; font-size: 11px; cursor: pointer; transition: border-color 150ms ease, color 150ms ease, background 150ms ease; }
.example-strip button:hover:not(:disabled) { border-color: rgba(77, 143, 220, .45); color: var(--do-primary-strong); background: var(--do-primary-soft); }
.example-strip button:disabled { cursor: not-allowed; opacity: .45; }
.composer-context { width: fit-content; max-width: 100%; height: 28px; display: flex; align-items: center; gap: 6px; margin: 0 0 8px 12px; padding: 0 9px; border: 1px solid var(--do-line); border-radius: 999px; color: var(--do-muted); background: rgba(255, 255, 255, .9); font-size: 11px; }
.composer-context svg { color: var(--do-primary-strong); }
.composer-context i { width: 4px; height: 4px; border-radius: 50%; background: #22c55e; }
.composer-context.blocked { color: #92400e; background: #fffbeb; }
.composer-context.blocked i { background: #f59e0b; }
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
.send-button:focus-visible, .cancel-button:focus-visible, .example-strip button:focus-visible { outline: 3px solid rgba(77, 143, 220, .2); outline-offset: 2px; }
@media (max-width: 720px) {
  .query-input { width: calc(100% - 24px); }
  .send-button, .cancel-button { min-width: 44px; width: 44px; }
  .send-button span, .cancel-button span { display: none; }
}
</style>
