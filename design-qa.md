# DataOcean 问数页方案二 Design QA

日期：2026-10-03。检查对象为现有 `/query` Vue 页面，按照已选“并排分析工作区”参考图进行实现对照。

## Source and implementation evidence

- Source visual truth: `output/design/query-experience-20261003/selected-option-2.png` (1487 × 1058 px).
- Main implementation capture: `output/playwright/query-option-2-20261003/02-success-1440.jpg` (1440 × 1000 px; CSS viewport 1440 × 1000; devicePixelRatio 1).
- Full-view side-by-side comparison: `output/playwright/query-option-2-20261003/design-qa-comparison-1440.jpg` (2880 × 1000 px).
- Focused result-panel comparison: `output/playwright/query-option-2-20261003/design-qa-result-focus.jpg` (1080 × 835 px).
- State: live `G0 Test Reader` session, `G0 Isolated Fixture` datasource, existing completed regional-sales conversation selected from history. The rendered rows are the service-protected `North 50.00`, `South 120.00`, and `East 40.00` result; the service returned `FINAL_PROTECTED`. No mock data or request interception was active in the page.

The source image was resized proportionally to 1440 px wide (1440 × 1025 px) and cropped by 25 px at the bottom to match the 1440 × 1000 CSS viewport. The implementation capture was not scaled. The full-view comparison places both at equal dimensions. The focused comparison aligns the source result region (x=861, y=88, 626 × 968 px) and implementation result region (x=900, y=76, 540 × 835 px), resampling the source crop to 540 × 835 px for inspection.

## Full-view and focused review

The full-view comparison confirms the selected hierarchy: a history rail, conversation and persistent composer, and a true adjacent results column. The focused comparison confirms chart, protected table, export and the server-backed protection status remain grouped in the result panel. At 1440 px the actual tracks are 240 / 660 / 540 px; the composer ends at x=880 and the result panel begins at x=900. At 1280 px they are 240 / 572 / 468 px; the composer ends at x=792 and the result panel begins at x=812.

The narrower panel remains within the implementation guide's 400–560 px range. Its chart and data rows come from the selected historical task. The page keeps the returned question and column names instead of copying the mock's sample labels. The visible “已按当前权限校验” badge is derived only from `FINAL_PROTECTED` or `FINAL_MASKED`; other values such as `NO_RESULT` do not receive a green pass label.

### Required fidelity surfaces

- **Typography:** Reuses the app font token and hierarchy; Chinese message text is 14 px, section headings 15–20 px, and supporting text 11–13 px. Long history and source names truncate within their own controls. No clipped input text was seen at the checked widths.
- **Spacing and layout:** Reuses the existing spacing, border, radius, and surface tokens. Desktop is an actual three-column grid. Message/result scroll areas are independent; the composer occupies its own grid row. Medium/mobile result switching does not overlay the composer.
- **Colors and tokens:** Uses existing `--do-*` colors and semantic status colors. The graph consumes the returned chart configuration. No new page theme or global token set was introduced.
- **Image quality and assets:** The page keeps the existing DataOcean mark and Lucide icon family. The selected reference contains no photographic or illustrative assets that require replacement; no CSS artwork or fake assets were added.
- **Copy and content:** Initial guidance is neutral. History, username, datasource, question, columns, result, readiness, capabilities, SQL, and protection state remain server or session supplied. Demo labels and suggested questions from the design image are not hard-coded.

## Responsive and interaction evidence

| CSS viewport | Evidence and measured result |
| --- | --- |
| 1440 × 1000 | `02-success-1440.jpg`, `07-clarification-1440.jpg`, `08-sql-details-1440.jpg`, `18-trust-details-1440.jpg`. Real history loads; chart, data, details and input are reachable. |
| 1280 × 1000 | `03-layout-1280.jpg`. Three columns remain separate; no document horizontal overflow; composer and results have a 20 px gap. |
| 920 × 900 | `04-layout-920-conversation.jpg`, `05-layout-920-result.jpg`. Conversation/result switch changes the single main view. In result mode the result panel spans y=76–791 px; composer spans y=804–867 px. |
| 768 × 900 | `09-layout-768-result.jpg`, `10-layout-768-conversation.jpg`, `11-mobile-history-open-768.jpg`. History is collapsed by default and opens on request; document width stays 768 px. |
| 390 × 844 | `12-layout-390-conversation.jpg`, `13-layout-390-result.jpg`, `14-mobile-history-open-390.jpg`, `15-new-session-390.jpg`, `16-success-390-result.jpg`, `17-data-390.jpg`. History starts collapsed, the datasource and workspace controls remain reachable, and the composer stays visible. Document width stays 390 px; the 351 px data table is contained in the result scroller. |

Chart resize was checked while changing the panel/container width: the chart container and ECharts canvas matched at 492 px (1440), 420 px (1280), 652 px (920), 729 px (768), and 351 px (390). Hiding the result view reduced its chart width to zero; reopening restored it to 652 px without a stale-size canvas. The browser remained on `?datasourceId=1`; the current account exposes one query datasource, so switching between multiple eligible datasources was not available to exercise.

Keyboard behavior has component tests for Enter, Shift+Enter, and IME composition. Browser accessibility inspection confirmed named datasource/history/workspace controls and selected tab state. A screen-reader session, OS soft keyboard, browser zoom, and full contrast audit were not run.

## Comparison history and fixes

1. **[P2] Chart title and export duplication.** The first live completed-result render showed the ECharts `title` object as serialized text and had two CSV buttons. The title now reads a returned string or `title.text`; the result keeps one CSV action. Post-fix evidence: `02-success-1440.jpg` and the full/focused comparison images.
2. **[P2] Mobile result-tab row occupied too much height.** The first 390 px success capture showed the result tabs sharing the flexible result row, pushing the chart heading into the badge area. The result panel now uses explicit `auto / minmax(0, 1fr)` rows, with the status/details panel spanning the body. Post-fix evidence: `16-success-390-result.jpg`; measured tabs end at y=350.8 px where the chart content begins, and the composer remains at y=755.8–815.4 px.
3. **[P1] Terminal query states could look like successful empty results.** The previous page allowed clarification, failure, cancellation, and timeout to coexist with stale rows, empty-success text, and result actions. A single query display-state resolver now gates those branches; table, export, and feedback controls require a completed result with rows. Post-fix browser evidence: `07-clarification-1440.jpg` has no table, success-empty message, export, or feedback. Unit fixtures cover failed, cancelled, timeout, processing, and completed-empty.
4. **[P2] Terminal progress branches were shown as completed.** Progress steps now appear only for a server-reported live `progressNode`; terminal states show their final state and recognized final-protection values without inferring skipped branches. Post-fix evidence is covered by `useQuerySubmit.test.ts` and `queryDisplayState.test.ts`; the real completed result reports `FINAL_PROTECTED` in `18-trust-details-1440.jpg`.

## Findings

No actionable P0, P1, or P2 design-to-implementation findings remain. Remaining content differences are intentional: the source uses demo labels and mock values, while the page keeps live session content; the result column follows the implementation guide's width range; and SQL, snapshot facts, and trust details are opened on demand.

## Open limits and follow-up

- No new NL2SQL request was submitted because the user asked not to classify or fake the possible provider `Arrearage` response. The real historical clarification and completed result were used. Browser captures do not claim live PROCESSING, FAILED, CANCELLED, or TIMEOUT service executions; those state branches are covered with isolated component/composable fixtures.
- The existing local build reports a chunk-size warning for the approximately 1.1 MB ECharts bundle. Dependency and build-splitting changes are outside this frontend experience task.
- PNG export remains absent as requested; the visible CSV route remains server-side.

## 2026-10-03 图表标题与切换修正补充

此前整页对照主要验证了默认柱状图。本次根据用户提供的商品类别饼图截图，补充了图表切换分支的功能与视觉验证。

- 完整问题保留在用户消息和可展开的详细信息；对话标题固定为“当前对话”，结果头不重复问题。图表区域优先使用返回的短指标名“商品类别销售额”，隐藏 ECharts 内部标题，避免同一句话重复出现。
- 柱/线/饼每次从原始配置生成独立展示配置。商品类别 180 / 30 转为明确的 Furniture / Accessories 切片，饼图移除 xAxis、yAxis、grid 和 dataZoom，并显示 85.7% / 14.3%；切回后恢复原始类别与数值。
- 配色从既有 CSS 令牌读取，类型切换采用紧凑的选中按钮；柱宽、圆角、数值标签与折线节点作了局部整理。多个指标、负值、全零、类别/数据不匹配等情况不会被静默转成错误的占比图。
- 没有修改 Java/Python、权限规则、SQL 或原始受保护结果；没有提交新的 NL2SQL 任务。验证使用本轮重新读取的既有 G0 商品类别成功任务，应用服务仍使用已验证的 V64 隔离验收库。

接受的最终截图位于 `output/playwright/query-chart-polish-20261003/`：

| 证据 | 条件与结论 |
| --- | --- |
| `05-pie-wide-final.jpg` | 1440×1000，短标题仅在图表区域显示一次，无饼图坐标轴，类别、金额、占比和明细对应 |
| `06-line-wide-final.jpg` | 同视口，切回折线后恢复 180 / 30，无残留饼图半径或类别图例 |
| `07-bar-wide-final.jpg` | 同视口，切回柱状图后类别、数值、轴线正常，窄柱与数值标签清楚 |
| `08-pie-mobile-final.jpg` | 390×844，文档宽度为 390px，图表容器约 351px，标签可读、输入可达、无页面横向溢出 |

临时视口覆盖已清除；独立验证标签保留供用户查看。早期验证中遇到页面被切换和热更新重载，相关非最终截图不作为通过证据。

自动化：全量前端 Vitest **18 files / 123 tests passed**；后续局部样式与标题调整后，图表与结果组件定向 **26 tests passed**。TypeScript/前端构建通过；`git diff --check` 通过。最后一次独立验证标签读取的 error/warn 日志为空。现有 ECharts/主包的构建体积提示仍在。

这些结论仅覆盖当前图表展示修正及已有前端回归，不等同于新的 AI 问数成功验收或完整可访问性认证。

final result: passed
