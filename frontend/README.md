# DataOcean Frontend

DataOcean 前端项目，基于 Vue 3 + TypeScript + Vite + Element Plus 构建。

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
