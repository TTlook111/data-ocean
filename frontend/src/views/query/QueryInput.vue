/**
 * QueryInput — 输入区域组件
 * 包含输入框、示例问题、发送/取消按钮
 */
<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { SendHorizontal } from 'lucide-vue-next'
import type { DatasourceReadiness } from '../../api/datasource'

const props = defineProps<{
  question: string
  isQuerying: boolean
  selectedId: number | undefined
  canAsk: boolean
  readinessLoading: boolean
  selectedBlockReason?: { message?: string } | undefined
  selectedReadiness?: DatasourceReadiness | undefined
  exampleQuestions: string[]
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

// 暴露 focus 方法
defineExpose({ focusQuestionInput })

function handleEnter(event: KeyboardEvent) {
  if (event.shiftKey || props.isQuerying) return
  event.preventDefault()
  emit('send')
}
</script>

<template>
  <div>
    <!-- 示例问题 -->
    <section class="example-strip" aria-label="示例问题">
      <button
        v-for="item in exampleQuestions"
        :key="item"
        type="button"
        :disabled="!canAsk"
        @click="emit('apply-example', item)"
      >
        {{ item }}
      </button>
    </section>

    <!-- 底部输入栏 -->
    <footer class="chat-composer">
      <div v-if="selectedId && !canAsk" class="composer-readiness">
        {{ selectedBlockReason?.message || (readinessLoading ? '正在确认该数据源是否可询问' : '未能确认该数据源上线状态，请刷新后重试') }}
      </div>
      <textarea
        ref="questionInputRef"
        :value="question"
        :disabled="!selectedId || !canAsk"
        rows="1"
        :placeholder="!selectedId ? '请先选择左侧数据源' : canAsk ? '向当前数据源提问，例如：上个月销售额最高的10个产品' : '当前数据源暂未完成上线流程'"
        @input="emit('update:question', ($event.target as HTMLTextAreaElement).value)"
        @keydown.enter="handleEnter"
      ></textarea>
      <button type="button" :disabled="!selectedId || !canAsk || !question.trim() || isQuerying" @click="emit('send')">
        <SendHorizontal :size="18" />
        <span>{{ isQuerying ? '查询中...' : '发送' }}</span>
      </button>
      <button v-if="isQuerying" type="button" class="cancel-btn" @click="emit('cancel')">
        取消
      </button>
    </footer>
  </div>
</template>
