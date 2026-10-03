# DataOcean Frontend

DataOcean 前端项目，基于 Vue 3 + TypeScript + Vite + Element Plus 构建。

后台产品体验约定见 [后台产品体验优化建议](../docs/development/DataOcean-后台产品体验优化建议.md)；导航及当前验收边界见 [后台重构状态文档](../docs/development/completed/DataOcean后台重构状态与整改计划.md)。账号入口统一放在侧栏左下角，正文以用户任务、状态和下一步为主。

## 启动

```bash
npm install
npm run dev
```

## 构建

```bash
npm run build
```

## 类型检查

```bash
npx vue-tsc --noEmit
```

## 目录说明

```
src/
├── api/           API 请求模块
├── components/    公共组件（AppShell 布局等）
├── router/        路由配置与守卫
├── stores/        Pinia 状态管理
├── views/
│   ├── admin/     管理端页面
│   ├── login/     登录页
│   ├── profile/   个人资料
│   └── query/     问答端页面
└── style.css      全局样式与设计令牌
```
