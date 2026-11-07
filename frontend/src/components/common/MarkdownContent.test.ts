// @vitest-environment jsdom
// DOMPurify 官方不将 happy-dom 视为安全 DOM；该文件独立使用受支持的 jsdom，不改变其它测试环境。
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DOMPurify from 'dompurify'
import MarkdownContent from './MarkdownContent.vue'

describe('安全 Markdown 阅读', () => {
  // 验证成熟解析器的实际阅读输出，不能把所有正文转义为一段原始 Markdown 来满足安全断言。
  it('展示标题、列表、表格、代码与正常链接，原始元数据不出现在阅读面', () => {
    const content = `## 业务口径

<!-- dataocean-fact: {"contentHash":"unchanged","sourceIds":[16]} -->

已确认 **收入** 与 *退款* 的口径。

- 完成订单
- [x] 审核完成

| 指标 | 口径 |
| --- | --- |
| 收入 | SUM(amount) |

~~~sql
SELECT '<script>alert(1)</script>' AS example
~~~

[官方说明](https://example.com/docs)
`
    const wrapper = mount(MarkdownContent, { props: { content } })
    try {
      expect(wrapper.find('h2').text()).toBe('业务口径')
      expect(wrapper.find('strong').text()).toBe('收入')
      expect(wrapper.find('em').text()).toBe('退款')
      expect(wrapper.findAll('li')).toHaveLength(2)
      expect(wrapper.findAll('li')[1]!.text()).toContain('[x] 审核完成')
      expect(wrapper.find('table tbody td').text()).toBe('收入')
      expect(wrapper.find('pre code').text()).toContain("SELECT '<script>alert(1)</script>'")
      expect(wrapper.find('a').attributes('href')).toBe('https://example.com/docs')
      expect(wrapper.find('script').exists()).toBe(false)
      expect(wrapper.html()).not.toContain('dataocean-fact')
      expect(wrapper.props('content')).toBe(content)
      expect(content).toContain('"contentHash":"unchanged"')
    } finally { wrapper.unmount() }
  })

  // 原始 HTML 先成为文字，脚本、事件、样式、命名节点及嵌入内容不能形成活动 DOM。
  it('原始脚本、事件处理器、嵌入页面、表单及样式都只作为文字展示', () => {
    const wrapper = mount(MarkdownContent, { props: { content: `
<script>window.__markdownExecuted = true</script>
<style>body { display:none }</style>
<iframe srcdoc="<img src=x onerror=alert(1)>"></iframe>
<form action="/mutate"><input autofocus name="token"></form>
<svg onload="alert(1)"><a xlink:href="javascript:alert(2)">svg</a></svg>
<math><mi href="data:x,<script>alert(3)</script>">math</mi></math>
<p id="app" name="location" class="hidden" style="display:none" data-target="/mutate" onclick="alert(4)">正文仍可阅读</p>
<img src="https://example.com/chart.png" onerror="alert(5)" srcset="javascript:alert(6) 2x" alt="安全图表">
` } })
    try {
      expect(wrapper.find('script, style, iframe, form, input, svg, math').exists()).toBe(false)
      expect(wrapper.text()).toContain('正文仍可阅读')
      expect(wrapper.find('img').exists()).toBe(false)
      expect(wrapper.text()).toContain('alt="安全图表"')
      expect(wrapper.text()).toContain('<p id="app"')
      expect(wrapper.find('[onclick], [onerror], [srcdoc], [srcset], [style], [id], [name], [data-target]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  // 字符实体与控制字符不能绕过链接策略；图片一律只保留说明，不留下任何资源节点。
  it.each([
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    'java&#x0a;script:alert(1)',
    '&#x6a;avascript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'data:image/svg+xml,%3Csvg%20onload=alert(1)%3E',
    'vbscript:msgbox(1)',
    'file:///etc/passwd',
    'blob:https://example.com/payload',
    '//example.com/protocol-relative',
    '\\example.com/backslash',
  ])('拒绝链接与图片的非阅读地址：%s', (url) => {
    const wrapper = mount(MarkdownContent, { props: { content: `[链接文本](<${url}>)\n\n<a href="${url}">原始链接</a><img src="${url}" alt="图片说明">` } })
    try {
      expect(wrapper.find('a[href]').exists()).toBe(false)
      expect(wrapper.find('img').exists()).toBe(false)
      expect(wrapper.text()).toContain('链接文本')
      expect(wrapper.text()).toContain('图片说明')
    } finally { wrapper.unmount() }
  })

  // 合法绝对与相对链接仍可使用，安全策略不能将普通业务文档变成不可导航的纯文本。
  it.each(['https://example.com/a', 'http://example.com/a', '/manual/page', '../guide', '#section', '?tab=source'])('保留合法阅读地址：%s', (url) => {
    const wrapper = mount(MarkdownContent, { props: { content: `[说明](${url})` } })
    try { expect(wrapper.find('a').attributes('href')).toBe(url) } finally { wrapper.unmount() }
  })

  // 新版本或本地预览更新时重新净化，并验证本组件的地址 hook 没有污染全局 DOMPurify。
  it('每次内容更新都净化，且策略不泄漏到其他净化实例', async () => {
    const wrapper = mount(MarkdownContent, { props: { content: '# 初始正文' } })
    try {
      await wrapper.setProps({ content: '[危险链接](javascript:alert%281%29)\n\n<img src="data:image/png;base64,AA==" onerror="alert(1)">' })
      expect(wrapper.find('h1').exists()).toBe(false)
      expect(wrapper.find('a').attributes('href')).toBeUndefined()
      expect(wrapper.find('img').exists()).toBe(false)
      expect(DOMPurify.sanitize('<a href="mailto:reader@example.com">mail</a>')).toContain('mailto:reader@example.com')
    } finally { wrapper.unmount() }
  })

  // Markdown 与原始 HTML 的远程资源均不得进入阅读 DOM，图片说明保留而原始正文不被改写。
  it('远程图片和嵌入资源不会自动加载，说明只作为转义文本展示', () => {
    const content = `![Markdown 图表](https://untrusted.example/markdown.png)

![<img src=x onerror=alert(1)>](https://untrusted.example/alt.png)

<img src="https://untrusted.example/html.png" alt="原始 HTML 图表 &lt;strong&gt;说明&lt;/strong&gt;">
<iframe src="https://untrusted.example/frame"></iframe>
<object data="https://untrusted.example/object"></object>
<video src="https://untrusted.example/video"><source src="https://untrusted.example/source"></video>
<audio src="https://untrusted.example/audio"></audio>
<embed src="https://untrusted.example/embed">
<link rel="stylesheet" href="https://untrusted.example/style.css">

[显式打开说明](https://example.com/manual)
`
    const wrapper = mount(MarkdownContent, { props: { content } })
    try {
      expect(wrapper.find('img, iframe, object, video, audio, source, embed, link').exists()).toBe(false)
      expect(wrapper.find('[src], [srcset], [poster]').exists()).toBe(false)
      expect(wrapper.text()).toContain('[图片：Markdown 图表]')
      expect(wrapper.text()).toContain('alt="原始 HTML 图表 &lt;strong&gt;说明&lt;/strong&gt;"')
      expect(wrapper.find('strong').exists()).toBe(false)
      expect(wrapper.text()).toContain('<img src="https://untrusted.example/html.png"')
      expect(wrapper.find('a').attributes('href')).toBe('https://example.com/manual')
      expect(wrapper.props('content')).toBe(content)
    } finally { wrapper.unmount() }
  })

  // HTML 注释只从阅读输出隐藏，原始 HTML 与 fenced code 均不被误当成可执行结构。
  it('HTML按文字展示且注释隐藏，普通Markdown和代码原文仍保留', () => {
    const content = `## 正文

<div><em>HTML原文</em><img src="https://untrusted.example/image.png" alt="原图"></div>

<!-- dataocean-fact: {"contentHash":"preserved"} -->

**Markdown 加粗**

~~~html
<!-- 代码块中的字面注释 -->
~~~`
    const wrapper = mount(MarkdownContent, { props: { content } })
    try {
      expect(wrapper.find('h2').text()).toBe('正文')
      expect(wrapper.find('strong').text()).toBe('Markdown 加粗')
      expect(wrapper.find('em, img').exists()).toBe(false)
      expect(wrapper.text()).toContain('<em>HTML原文</em>')
      expect(wrapper.text()).not.toContain('dataocean-fact')
      expect(wrapper.find('pre code').text()).toContain('<!-- 代码块中的字面注释 -->')
      expect(wrapper.props('content')).toBe(content)
    } finally { wrapper.unmount() }
  })
})
