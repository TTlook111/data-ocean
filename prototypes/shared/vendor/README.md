# 原型使用的组件资源

本目录从项目本机已经安装的依赖复制官方发行文件，供 HTML 原型离线使用，无需 CDN 或构建服务。

- Vue 3.5.34 的全局生产构建，MIT 许可见 vue-LICENSE.txt。
- Element Plus 2.14.0 的全局构建、完整样式和中文语言包，MIT 许可见 element-plus-LICENSE.txt。
- Apache ECharts 6.0.0 的官方发行构建，供总览原型离线绘图；Apache-2.0 许可及声明见 echarts-LICENSE.txt、echarts-NOTICE.txt。

查询和分页通过 RecordControls 统一使用真实组件。表格通过 RecordTable 共用骨架。此处仅用于原型验证，未来正式前端采用 React；其他组件选择及正式重构须另行确认，不从此处依赖自动推定。

组件用法依据 [Element Plus 组件文档](https://element-plus.org/zh-CN/component/overview.html)，不修改发行文件。
