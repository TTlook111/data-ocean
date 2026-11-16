# 前端设计

本目录负责页面结构、交互、视觉、组件使用和原型状态。业务含义来自[产品方案与解说](../product/README.md)，真实服务职责来自[后端设计](../backend/README.md)，跨端约定统一见[系统架构](../architecture/README.md)。

## 按任务读取

| 问题 | 读取 |
| --- | --- |
| 代码命名、模块目录、依赖、复用、检查和测试原则 | [三端共用总则](../engineering/代码风格与项目组织规范.md) |
| 已确认的仓库、目标目录、构建与测试定位 | [工程组织与版本基线](../engineering/工程组织与版本基线.md) |
| 开始任何页面设计或调整 | [设计约定](设计约定.md) |
| 某模块的页面和按钮 | [页面专题](pages/README.md)，只继续读相关模块 |
| 面包屑、控件、查询、分页及返回 | [交互规则](交互规则.md) |
| 字体、间距、布局和列预算 | [界面规范](界面规范.md) |
| 已有原型和待落实项 | [原型状态](原型状态.md) |
| 检查本次改动 | [验收要求](验收.md) |

## 前端技术选型

正式前端采用 React、TypeScript 和 Vite，单工程包含后台与问数布局，使用 npm 管理依赖；组织方式见[工程基线](../engineering/工程组织与版本基线.md#前端工程)。下列选型已确认，原型尚未迁移，真实重构未开始。

当前以本地启动为主，Node 由开发者在宿主机安装，前端通过本地开发服务运行；基础依赖及运行边界见[本地启动约定](../engineering/工程组织与版本基线.md#本地运行与依赖环境)。

当前 HTML 原型使用本地 Vue 3、Element Plus 及 ECharts，版本与许可见[组件资源](../../prototypes/shared/vendor/README.md)。现有业务前端代码可在正式重构时整体替换，不承担复用或兼容要求；承接的是已确认原型的布局、信息层级、业务流程和交互。重构边界与打磨原则见[设计约定](设计约定.md#原型基准与界面打磨)。

### 组件与配套库

| 范围 | 已确认选择 | 职责与约束 |
| --- | --- | --- |
| 基础组件 | [Ant Design](https://ant.design/docs/react/customize-theme) | 日期、下拉、树、分页、弹窗与抽屉等复用组件库；通过统一主题定制颜色、字体、圆角与尺寸 |
| 路由 | [React Router，Data Mode](https://reactrouter.com/start/modes) | 后台与问数布局、详情地址、导航及未保存离开保护；业务数据缓存统一交给 TanStack Query |
| 客户端共享状态 | [Zustand](https://zustand.docs.pmnd.rs/learn/getting-started/introduction) | 管理确需跨组件共享的交互状态；表单、弹窗等局部状态就近管理，不复制服务端数据缓存 |
| 接口数据状态 | [TanStack Query](https://tanstack.com/query/latest/docs/framework/react/overview) | 列表与详情请求、缓存、加载和错误状态，以及办理后的受影响数据刷新 |
| 表格 | [Ant Design Table](https://ant.design/components/table) | 普通业务列表使用服务端分页、筛选与排序；统一列预算、长文本、操作列、空态与分页 |
| 表单 | [Ant Design Form](https://ant.design/components/form) | 统一字段布局、联动、动态字段与校验反馈，优先使用同一套表单状态及校验机制 |
| 图表 | [Apache ECharts](https://echarts.apache.org/handbook/en/basics/import/) | 覆盖概览与问数图表，按需加载；统一字体、色谱、图例和提示样式 |
| SSE 客户端 | [eventsource-client](https://github.com/rexxars/eventsource-client) | 基于 Fetch 订阅事件，支持自定义认证头、重连及初始事件游标；业务恢复由项目按任务协议落实 |
| 样式 | CSS Modules 与统一设计变量 | 页面布局由项目维护，共用尺度集中管理；组件外观优先通过主题与公开配置调整 |

列表查询区分待提交输入与已应用条件，按[交互规则](交互规则.md#详情回退与筛选联动)恢复查询、页码和每页条数。TanStack Query 管理接口数据；已应用条件参与缓存标识，连续查询只应用最新上下文，不能由多个状态容器各存一份结果。

任务创建、补充与取消通过普通 HTTP 命令完成，SSE 只订阅事件。普通 API 与前端 SSE 统一使用 Cookie 服务端会话，配合 CSRF 保护，凭据不放入 URL；登录失效后停止订阅和自动重连、清除受限展示，规则见[认证方案](../architecture/认证与身份上下文.md)。客户端之上需落实游标恢复、事件去重及快照衔接，断线或重新登录不重新执行任务；具体语义统一引用[调用与任务协议](../architecture/调用与任务协议.md#流式输出与断线恢复)，不把库的重连能力当作业务恢复已实现。

### 工程版本与验证边界

| 内容 | 已认可的工程基线 | 约束 |
| --- | --- | --- |
| Node.js | 24 LTS | 工程初始化时固定补丁和配套 npm 版本，开发与 CI 保持一致 |
| React / React DOM | 19.3.1 | 两者保持相同版本，类型包选择匹配系列 |
| TypeScript | 6.0.3 | 按当前 typescript-eslint 支持范围选择，不仅按最新主版本升级 |
| Vite | 8.3.8 | React 插件和测试工具选择匹配版本并一起锁定 |
| 代码与格式检查 | ESLint、typescript-eslint、Prettier | 分开代码质量与格式职责，兼容补丁及规则配置在建工程时验证 |
| 组件测试 | Vitest、React Testing Library | 精确依赖随锁文件固定，测试布局见工程基线；浏览器验收仍走 ego-lite |

上述版本是正式前端工程的验证基线，React 业务工程尚未建立，依赖安装、生产构建和页面验收仍待完成。配套库精确版本在初始化时核对兼容性并随 `package-lock.json` 固定，实际结果以构建、组件交互及 SSE 恢复验证为准。版本依据参考 [Node.js 发布状态](https://nodejs.org/en/about/previous-releases)、[React 版本](https://react.dev/versions)、[typescript-eslint 兼容范围](https://typescript-eslint.io/users/dependency-versions/)及 [Vite 要求](https://vite.dev/guide/)。具体版本只在本节维护，其他工程文档引用本页。

## 内容归属

前端文档表达输入、交互和状态反馈，不重新定义权限或业务规则，也不承担真实身份校验和业务执行。新结论更新对应正文，进度只维护当前状态，不写操作流水。
