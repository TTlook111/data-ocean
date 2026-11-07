<script setup lang="ts">
import { computed } from 'vue'
import createDOMPurify from 'dompurify'
import { Marked } from 'marked'

// 阅读层只消费原文，不产生编辑值或回写事件，结构元数据继续由调用方原样保存。
const props = withDefaults(defineProps<{ content?: string | null }>(), { content: '' })

// 图片说明只作为惰性文本输出；转义描述中的标记，不能让 alt 反向生成 HTML。
function escapeText(value: string) {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

// 使用独立解析器，避免为阅读组件修改其他场景的全局 Markdown 配置。
const markdown = new Marked({
  async: false,
  gfm: true,
  breaks: false,
  renderer: {
    // 清单保留原有勾选语义，但不把不可信文档转成可交互表单。
    checkbox({ checked }) { return checked ? '[x] ' : '[ ] ' },
    // 阅读知识不会自动访问正文里的图片地址，原文仍完整保留给编辑和保存。
    image({ text }) { return escapeText(`[图片：${text || '未提供说明'}]`) },
    // 原始 HTML 在进入 DOM 净化器之前即变成文本，避免解析资源标签时产生请求；HTML 注释不进入阅读输出。
    html({ text }) { return escapeText(text.replace(/<!--[\s\S]*?(?:-->|$)/g, '')) },
  },
})

// URL 交给浏览器标准解析器规范化；同时拒绝 marked 已编码成 %5C 的反斜杠，避免解析前后策略不同。
function isReadableUrl(value: string) {
  const url = value.trim()
  if (!url || /[\u0000-\u0020\u007f\\]|%5c/i.test(url) || url.startsWith('//')) return false
  try {
    const parsed = new URL(url, 'https://markdown.invalid/')
    return parsed.protocol === 'https:' || parsed.protocol === 'http:'
  } catch { return false }
}

// 每个实例拥有自己的净化器与 hook，不污染其他组件；只保留用户主动点击的安全链接。
const purifier = createDOMPurify(window)
purifier.addHook('uponSanitizeAttribute', (_node, data) => {
  if (data.attrName === 'href' && !isReadableUrl(data.attrValue)) {
    data.keepAttr = false
  }
})

// 只保留阅读所需 HTML；样式、事件、表单、嵌入内容、命名节点及自定义数据属性不进入文档 DOM。
const readableTags = [
  'p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'blockquote',
  'pre', 'code', 'strong', 'em', 'del', 'ul', 'ol', 'li',
  'table', 'thead', 'tbody', 'tr', 'th', 'td', 'a',
]
const safeHtml = computed(() => {
  if (!purifier.isSupported) return null
  try {
    const parsed = markdown.parse(props.content || '', { async: false })
    return purifier.sanitize(parsed, {
      ALLOWED_TAGS: readableTags,
      ALLOWED_ATTR: ['href', 'title', 'align', 'start', 'colspan', 'rowspan'],
      ALLOW_ARIA_ATTR: false,
      ALLOW_DATA_ATTR: false,
      ALLOW_UNKNOWN_PROTOCOLS: false,
      SANITIZE_NAMED_PROPS: true,
      RETURN_TRUSTED_TYPE: false,
    })
  } catch { return null }
})
</script>

<template>
  <!-- v-html 仅接收净化结果；解析或净化不可用时通过 Vue 插值安全展示原文，不降级为原始 HTML。 -->
  <article v-if="safeHtml !== null" class="markdown-content" v-html="safeHtml" />
  <pre v-else class="markdown-content markdown-content--plain">{{ content }}</pre>
</template>

<style scoped>
/* 正文使用共享令牌和可阅读行距，长技术值允许换行，不把来源页面撑出视口。 */
.markdown-content { min-width: 0; color: var(--do-ink); font-size: 14px; line-height: 1.85; overflow-wrap: anywhere; }
.markdown-content--plain { margin: 0; white-space: pre-wrap; font: inherit; }
.markdown-content :deep(> :first-child) { margin-top: 0; }
.markdown-content :deep(> :last-child) { margin-bottom: 0; }
.markdown-content :deep(p), .markdown-content :deep(ul), .markdown-content :deep(ol) { margin: 0 0 16px; }
.markdown-content :deep(ul), .markdown-content :deep(ol) { padding-left: 1.6em; }
.markdown-content :deep(li + li) { margin-top: 5px; }
.markdown-content :deep(li > p) { margin-bottom: 8px; }

/* 标题和引用保留文档结构，视觉层级不依赖原文提供 style 或 class。 */
.markdown-content :deep(h1), .markdown-content :deep(h2), .markdown-content :deep(h3),
.markdown-content :deep(h4), .markdown-content :deep(h5), .markdown-content :deep(h6) { margin: 24px 0 12px; font-weight: 650; line-height: 1.45; }
.markdown-content :deep(h1) { font-size: 25px; }
.markdown-content :deep(h2) { font-size: 21px; }
.markdown-content :deep(h3) { font-size: 17px; }
.markdown-content :deep(h4), .markdown-content :deep(h5), .markdown-content :deep(h6) { font-size: 15px; }
.markdown-content :deep(blockquote) { margin: 18px 0; padding: 8px 16px; border-left: 3px solid var(--do-line-strong); background: var(--do-soft); color: var(--do-muted); }
.markdown-content :deep(blockquote > :last-child) { margin-bottom: 0; }
.markdown-content :deep(hr) { margin: 24px 0; border: 0; border-top: 1px solid var(--do-line); }

/* 行内代码保留正文节奏，独立代码块与问数 SQL 共享代码表面并自行横向滚动。 */
.markdown-content :deep(code) { border-radius: 4px; padding: 2px 5px; background: var(--do-soft); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: .9em; }
.markdown-content :deep(pre) { max-width: 100%; overflow-x: auto; margin: 16px 0; padding: 16px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); background: var(--do-code-bg); color: var(--do-code-text); line-height: 1.65; }
.markdown-content :deep(pre code) { padding: 0; background: transparent; overflow-wrap: normal; }
.markdown-content :deep(table) { display: block; max-width: 100%; width: max-content; overflow-x: auto; margin: 18px 0; border-collapse: collapse; font-size: 13px; }
.markdown-content :deep(th), .markdown-content :deep(td) { padding: 9px 12px; border: 1px solid var(--do-line); text-align: left; vertical-align: top; }
.markdown-content :deep(th) { background: var(--do-soft); font-weight: 600; }

/* 仅显式点击的链接保留安全地址，键盘焦点与长地址仍可辨认；阅读面没有图片资源节点。 */
.markdown-content :deep(a[href]) { color: var(--do-primary-strong); text-decoration: underline; text-underline-offset: 3px; }
.markdown-content :deep(a:focus-visible) { outline: 2px solid var(--do-primary); outline-offset: 3px; }
</style>
