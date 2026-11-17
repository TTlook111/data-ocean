# DataOcean 前端

本目录为正式前端工程。新增、修改和重构前，从[开发前必读与完成验收](../docs/frontend/README.md#开发前必读与完成验收)进入，按顺序读取共用总则、设计与交互规范、模块专题和原型，完成前逐项核对。

技术选型、当前实现及验证边界统一维护在[前端开发文档](../docs/frontend/README.md)，实际代码入口见[源码定位](../docs/source-map/README.md#首批正式实现)。本页只提供导航，不重复维护规则正文。

项目工作约定见根目录 [AGENTS.md](../AGENTS.md)。

## 本地开发

两台设备分别按本机私有环境说明选择兼容的 Node.js 与 npm，范围见[工程版本](../docs/frontend/README.md#工程版本与验证边界)，不需要安装在相同路径或使用相同补丁。每台设备拉取后，在本目录先核对 `node --version`、`npm --version` 并执行 `npm ci`，按同一锁文件安装本机依赖。

代理默认指向 `http://127.0.0.1:18080`，其他地址用 `DATAOCEAN_API_PROXY_URL` 覆盖。在本目录执行 `npm run dev` 启动，`npm run build` 构建，`npm run lint` 检查代码；从仓库根目录也可使用随项目提供的 `tools/dev/start-frontend.ps1`，完整说明见[运行与验收](../docs/backend/运行与验收.md)。
