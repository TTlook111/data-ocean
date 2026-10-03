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

## 2026-10-03 后台产品体验实施与设计对照

本节只评价用户认可的工作台、数据源列表、数据源详情，以及沿该流程的治理和知识页面局部整理；不把它当作整个后台或生产系统验收。

### 来源、尺寸与方法

用户认可组合采用本轮三张设计图，并明确优先考虑真实用户任务。对应源图已保存到 `output/playwright/admin-product-implementation-20261003/references/`：工作台、数据源准备引导、数据源总览。每张实际尺寸为 1487×1058，设计目标为桌面 1440×1024。真实实现复用既有 Vue、Element Plus、Lucide、CSS 变量、七域两级侧栏、IAM-SIMPLE-1 能力与 API，不创建新原型、不伪造统计或改动生命周期。

使用 UI UX Pro Max 的已核验极简风格、键盘焦点、加载/错误恢复和表格溢出建议。关于“行操作层级”的两次检索未命中该主题的精确条目；主动作与低频操作分组来自用户任务分析。Product Design image-to-code / design-qa 用于参考解析和实际对照。没有需要新生成的照片、插图等栅格资产；保留现有品牌组件和用户要求的字母头像，图标继续使用项目已规定且与参考相符的 Lucide。

Browser 的 fullPage 与直接 CDP 截图在本机失败，不能用这些失败结果作证。改用内置 Browser 的原生视口截图：CSS 1440×1024，deviceScaleFactor=1，CDP display scale=0.627083333；外层面板 zoom=1.25。原生文件为 1150×1014，页面实际画面为约 1129×803，后方是面板空白。只裁去页面外空白并将源图统一到 1129×803，不改动画面内容。用 `compare-design.ps1` 生成同一个输入中的左右对照；逐一查看整体、主内容和共享账号区域，未把两张独立打开的图声称为并排对照。

### 对照结果与五项表面

| 检查项 | 结论 |
| --- | --- |
| 字体与排版 | 采用既有 Inter / 系统中文字体；主标题 24–32px、常规说明 14px、表格辅助文字 12–13px，按原组件保持企业界面密度。生成图字号更大，属于明确的项目适配；未发现文字覆盖、错误断行或正文裁剪。主内容裁剪和账号裁剪已分别检查。 |
| 间距与布局 | 工作台合并重复就绪面板，详情保持清单与关联资料两栏，列表保留主操作与更多菜单。重复标题改为正文一个主标题、顶部位置提示。保留 252px 侧栏和 24px 页面边距，未逐像素照搬生成图尺寸。桌面图片和规范化对照中无覆盖或页面横向溢出。 |
| 色彩与令牌 | 沿用蓝色品牌、浅色面板、绿色状态。主按钮使用深蓝令牌，状态文字适当加深；成功状态有明确文字与图标。已生效知识索引使用成功色，待审核使用警示色。没有用新营销配色或装饰渐变替换产品风格。 |
| 图像与图标质量 | 对照图是参考素材，未作为页面背景或可点击假 UI；没有照片类缺失资产。品牌、字母账号头像和 Lucide 图标均为现有真实组件，截图没有被合成成运行界面。 |
| 文案与内容 | 减少 readiness、build 等实现说明；状态、快照与文档数来自实际接口。采集/发布/索引构建保持独立。资料刷新失败显示失败，不显示“暂无问题”；未知或部分失败不会显示为全部就绪。 |

可接受的设计差异：工作台简易接口没有主机信息，因此不编造连接地址；增加可问数数量以区分总数与已就绪数。列表保留启用状态和连接筛选，便于管理停用对象。详情保留真实治理问题及折叠采集活动，便于管理员继续处理。字体、按钮高度和清单图标沿用现有系统。上述差异服务于用户认可的产品与任务优先目标，不声称是逐像素复制。

### 浏览器与自动化证据

真实页面证据位于 `output/playwright/admin-product-implementation-20261003/`：

| 步骤 | 证据 | 结论 |
| --- | --- | --- |
| 工作台 | `13-workbench-final.jpg`、`workbench-comparison.png` | 一处主标题、任务摘要、真实数据源、查看详情与底部身份；无重复 100% 面板 |
| 数据源列表 | `12-sources-final.jpg`、`sources-comparison.png` | 查看详情为主，编辑/测试连接/启停/删除收进更多；保留实际状态、快照与过滤 |
| 搜索与更多 | `05-search-empty.jpg`、`06-more-menu.jpg` | 无匹配结果明确说明；清空并按 Enter 恢复；菜单可打开、Escape 关闭后进入详情 |
| 数据源详情 | `07-detail-desktop.jpg`、`detail-comparison.png` | 准备清单、当前发布快照、知识资料、治理入口；进入治理携带 datasourceId=1 与 snapshotId=1 |
| 治理总览 | `08-governance-desktop.jpg`、`09-rules-open.jpg` | 一处快照范围；结果与问题先展示；规则和流程可展开；没有执行校验或切换规则 |
| 语义知识 | `10-knowledge-desktop.jpg`、`11-knowledge-records-open.jpg` | 版本对照集中显示；文档列表可见；构建记录和表关联配置按需展开；索引更新仍需原有确认 |
| 较窄桌面 | `14-workbench-1024.jpg` | CSS 1024×900，documentWidth=1024；工作台摘要重排，没有页面横向溢出 |
| 小屏结构 | `15/16/17` 文件及本轮 DOM 检查 | CSS 390×844，工作台 documentWidth=375、数据源列表 documentWidth=390，均未超过视口，自动折叠侧栏。小屏原生截图被 Browser 导出缩小，不能据此认证完整移动视觉或字号质量；只确认结构、范围和溢出。 |
| 最终日志 | `final-console.json` | 最终重新加载后的错误/警告为空；此前有临时热更新导致的旧组件/旧变量警告，已在完整模板替换后排除，未归因成 Browser 错误 |

`04-sources-desktop.jpg` 实际仍为导航前的工作台，拒绝作为列表证据；使用稳定后的 12。02 属于视口变更前一帧，不作为最终桌面依据。桌面与主内容/账号并排比较均已检查，未发现未解决 P0/P1/P2。移动完整视觉质量是后续检查边界，未用放大或生成图伪装高清截图。

前端全量 Vitest：**20 files / 133 tests passed**。新增 7 项覆盖准备状态失败、部分失败、已发布快照与最新草稿区别、辅助请求失败、未知状态禁止问数跳转、治理问题失败以及普通管理员不能启停全局规则。后续知识表格整理后的定向 **15 tests passed**。TypeScript 与生产构建通过，最后日志保存为 `final-build.log`；`git diff --check` 通过。既有约 1.1MB 资源包体积提示仍在。

### 结果与边界

选定的三个桌面页面通过产品设计与实现对照，核心浏览器查看/导航/搜索/菜单/折叠检查通过。本机验证只使用已有隔离环境的一个测试数据源与测试账号；未运行新 NL2SQL、质量校验、授权变更、知识构建或生产发布。没有完成所有后台流程、所有真实用户权限场景、独立屏幕阅读器或完整移动端视觉验收。共享标题、颜色和范围条的改动不代表所有业务页面已逐页重新验收。

final result: passed
